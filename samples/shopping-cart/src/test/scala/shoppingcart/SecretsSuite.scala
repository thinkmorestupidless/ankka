package shoppingcart

import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.http.{Caller, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import shoppingcart.api.SecretsEndpoint
import shoppingcart.application.ShoppingCartEntity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

/**
 * The sample's secrets route, which the platform's cluster suite reads a secret through: it keeps,
 * answers only that something is kept, and admits only this service's own instances.
 */
class SecretsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private var base                  = ""
  private val http                  = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(clients => SecretsEndpoint(clients.secrets))
    testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(server))
    base = s"http://127.0.0.1:${server.boundPort.get}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String] = None,
      as: Option[Caller] = None
  ): (Int, String) =
    val b = HttpRequest
      .newBuilder(URI.create(base + path))
      .method(
        method,
        body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
      )
      .header("Content-Type", "application/json")
    as.foreach { c =>
      val (name, value) = testKit.asCaller(c)
      b.header(name, value): Unit
    }
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode, r.body)

  test("a kept secret is answered as kept, and never with its value") {
    assertEquals(send("PUT", "/secrets/acme", Some("""{"value":"sk-acme-1"}"""))._1, 204)
    val (status, body) = send("GET", "/secrets/acme")
    assertEquals(status, 200)
    assert(body.contains("kept"), body)
    assert(!body.contains("sk-acme-1"), body)
  }

  test("a secret never kept is not found") {
    assertEquals(send("GET", "/secrets/never-kept")._1, 404)
  }

  test("each read through the route leaves a record") {
    send("PUT", "/secrets/recorded", Some("""{"value":"v"}""")): Unit
    send("GET", "/secrets/recorded"): Unit
    val reads = testKit.recordedReads.filter(r =>
      r.name == "recorded" && r.operation == ReadRecord.Operation.Get
    )
    assertEquals(reads.map(_.outcome), Vector(ReadRecord.Outcome.Read))
  }

  test("another service and the internet are refused") {
    assertEquals(send("GET", "/secrets/acme", as = Some(Caller.Service("local", "orders")))._1, 403)
    assertEquals(send("GET", "/secrets/acme", as = Some(Caller.Gateway))._1, 403)
  }
