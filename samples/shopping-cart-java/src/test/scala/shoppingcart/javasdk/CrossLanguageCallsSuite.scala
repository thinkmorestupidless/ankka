package shoppingcart.javasdk

import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, Settings}
import com.thinkmorestupidless.ankka.testkit as stk
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import shoppingcart.api.{CallersEndpoint, ShoppingCartEndpoint}
import shoppingcart.application.ShoppingCartEntity

import java.net.{InetAddress, ServerSocket, URI}
import java.net.http.{HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.util.Using

/**
 * A Java service and a Scala service in one project, calling each other's entities through their
 * service clients: the Java cart beside the Scala cart, each told where the other is as
 * `ankka.local-services` tells a service on a developer's machine.
 */
class CrossLanguageCallsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var java: AnkkaTestKit      = null
  private var scala: stk.AnkkaTestKit = null

  // The Scala cart's port is chosen first, so each kit can be told the other's address.
  private val scalaPort =
    Using.resource(ServerSocket(0, 1, InetAddress.getLoopbackAddress))(_.getLocalPort)

  override def beforeAll(): Unit =
    java = AnkkaTestKit.start(
      Main.service().withEndpoint(classOf[Neighbour]),
      Settings.defaults().withLocalService("scala-cart", URI.create(s"http://127.0.0.1:$scalaPort"))
    )
    scala = stk.AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor),
      extensions = Seq(
        HttpServer.at("127.0.0.1", scalaPort)(
          clients => ShoppingCartEndpoint(clients.componentClient),
          clients => CallersEndpoint(clients.services)
        )
      ),
      localServices = Map("java-cart" -> java.http().toString.stripSuffix("/"))
    )

  override def afterAll(): Unit =
    try if scala != null then scala.stop()
    finally if java != null then java.close()

  private def send(base: URI, method: String, path: String, body: Option[String] = None) =
    val builder = HttpRequest.newBuilder(base.resolve(path))
    body match
      case Some(json) =>
        builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit
    val response = java.httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def scalaHttp = URI.create(s"http://127.0.0.1:$scalaPort")

  private val item = """{"productId":"p-1","name":"Pen","quantity":2}"""

  test("the Java service writes and reads the Scala service's entity, in its own records") {
    assertEquals(send(java.http(), "POST", "/neighbour/carts/s-1/items", Some(item))._1, 204)
    val (status, body) = send(java.http(), "GET", "/neighbour/carts/s-1")
    assertEquals(status, 200, body)
    // The Scala cart holds it, and the Java cart read it back through its own Cart record.
    assertEquals(send(scalaHttp, "GET", "/carts/s-1")._2, body)
    assert(body.contains(""""productId":"p-1"""") && body.contains(""""quantity":2"""), body)
  }

  test("the Scala service reads the Java service's entity") {
    assertEquals(send(java.http(), "POST", "/carts/j-1/items", Some(item))._1, 204)
    val (status, body) = send(scalaHttp, "GET", "/callers/call/java-cart?path=/carts/j-1")
    assertEquals(status, 200, body)
    assertEquals(body, send(java.http(), "GET", "/carts/j-1")._2)
  }

  test("the Scala service sees the Java service's call as it sees any call on this machine") {
    val fromJava  = send(java.http(), "GET", "/neighbour/whoami")
    val fromScala = send(scalaHttp, "GET", "/callers/whoami")
    assertEquals(fromJava._1, 200, fromJava._2)
    assert(fromJava._2.contains(fromScala._2), s"${fromJava._2} / ${fromScala._2}")
  }
