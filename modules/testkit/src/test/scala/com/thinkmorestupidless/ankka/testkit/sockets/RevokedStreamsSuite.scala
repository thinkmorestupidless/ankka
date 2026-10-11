package com.thinkmorestupidless.ankka.testkit.sockets

import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, TestSocket}
import org.apache.pekko.stream.scaladsl.Source

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/**
 * A grant ends what it admitted (feature 040): a socket and a server-sent event stream opened by a
 * granted caller are ended when the grant is revoked — the socket "revoked", the stream completed —
 * and the next opening is refused. One the grant did not admit stays open.
 */
class RevokedStreamsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val merchant = Caller.Service("payments", "merchant")
  private val events   = GrantTarget.Route("GET", "/v1/wallets/{player}/events")
  private val ledger   = GrantTarget.Route("GET", "/v1/wallets/{player}/ledger")
  private val grants   = Grants.of()

  private final class Wallets extends HttpEndpoint("/v1/wallets"):
    val acl: Acl = Acl.allowCallers(Callers.granted)

    socket[String]("/{player}/events") { (_, socket) =>
      Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach(socket.send)
    }

    sse[String]("/{player}/ledger")(player =>
      Source.tick(0.millis, 100.millis, s"entry for $player").mapMaterializedValue(_ => ())
    )

    withAcl(Acl.AllowAll) {
      socket[String]("/{player}/public")((_, socket) =>
        Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach(socket.send)
      )
    }

  private var testKit: AnkkaTestKit = null
  private var base: String          = ""

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(_ => Wallets()).withGrants(grants)
    testKit = AnkkaTestKit.start(Nil, Seq(server))
    base = s"127.0.0.1:${server.boundPort.getOrElse(fail("not bound"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    grants.set(Vector(GrantEntry(merchant, events), GrantEntry(merchant, ledger)))

  private def asMerchant = Map(LocalCallers.header(merchant))

  test("a revoked grant closes the socket it admitted, and the next opening is refused") {
    val socket = TestSocket.open(s"ws://$base/v1/wallets/p1/events", asMerchant).toOption.get
    socket.send("hello")
    assertEquals(socket.receive(), Some("hello"))
    grants.set(Vector(GrantEntry(merchant, ledger)))
    assertEquals(socket.closed(10.seconds), TestSocket.Closed(1008, "revoked"))
    assertEquals(
      TestSocket.open(s"ws://$base/v1/wallets/p1/events", asMerchant).left.map(_.status),
      Left(403)
    )
  }

  test("a revoked grant ends the stream it admitted, and the next request is refused") {
    val client = HttpClient.newHttpClient()
    val request = HttpRequest
      .newBuilder(URI.create(s"http://$base/v1/wallets/p1/ledger"))
      .header(LocalCallers.Header, LocalCallers.header(merchant)._2)
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
    assertEquals(response.statusCode, 200)
    val lines = response.body.iterator
    assert(lines.next().nonEmpty || lines.next().nonEmpty, "the stream is flowing")
    grants.set(Vector(GrantEntry(merchant, events)))
    val ended = java.util.concurrent.CompletableFuture.supplyAsync(() =>
      while lines.hasNext do lines.next(): Unit
      true
    )
    assert(ended.get(10, TimeUnit.SECONDS), "the stream completed")
    assertEquals(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode, 403)
  }

  test("a socket no grant admitted is left open when grants change") {
    val socket = TestSocket.open(s"ws://$base/v1/wallets/p1/public", asMerchant).toOption.get
    grants.set(Vector.empty)
    socket.send("still here")
    assertEquals(socket.receive(), Some("still here"))
    socket.close()
  }

  test("a socket the grant still admits is left open when another grant is revoked") {
    val socket = TestSocket.open(s"ws://$base/v1/wallets/p1/events", asMerchant).toOption.get
    grants.set(Vector(GrantEntry(merchant, events)))
    socket.send("still here")
    assertEquals(socket.receive(), Some("still here"))
    socket.close()
  }
