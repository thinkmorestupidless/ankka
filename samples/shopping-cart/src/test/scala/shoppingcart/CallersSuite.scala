package shoppingcart

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.http.{Caller, HttpServer}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.api.CallersEndpoint
import shoppingcart.application.ShoppingCartEntity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

/** The caller-naming ACLs of the sample, tested without a cluster by naming the caller. */
class CallersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private var base                  = ""
  private val http                  = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(clients => CallersEndpoint(clients.services))
    testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(server))
    base = s"http://127.0.0.1:${server.boundPort.get}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def get(path: String, as: Option[Caller] = None): (Int, String) =
    val b = HttpRequest.newBuilder(URI.create(base + path))
    as.foreach { c =>
      val (name, value) = testKit.asCaller(c)
      b.header(name, value): Unit
    }
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode, r.body)

  // docs:start test-as-caller
  test("the orders service is admitted and the payments service is not") {
    assertEquals(get("/callers/only-orders", Some(Caller.Service("local", "orders")))._1, 200)
    assertEquals(get("/callers/only-orders", Some(Caller.Service("local", "payments")))._1, 403)
  }
  // docs:end test-as-caller

  test("a route that admits one service by name admits that service") {
    assertEquals(
      get("/callers/orders-alone", Some(Caller.Service("local", "orders"))),
      (200, "admitted: local/orders")
    )
  }

  test("a route that admits only one service by name refuses the gateway") {
    assertEquals(get("/callers/orders-alone", Some(Caller.Gateway))._1, 403)
  }

  test("a route that admits only one service by name refuses another service") {
    assertEquals(get("/callers/orders-alone", Some(Caller.Service("local", "carts")))._1, 403)
  }

  test("the route that admits the module's service admits it, and nobody else") {
    assertEquals(
      get("/callers/rust-cart-alone", Some(Caller.Service("local", "rust-cart"))),
      (200, "admitted: local/rust-cart")
    )
    assertEquals(get("/callers/rust-cart-alone", Some(Caller.Service("local", "orders")))._1, 403)
    assertEquals(get("/callers/rust-cart-alone", Some(Caller.Gateway))._1, 403)
  }

  test("without a named caller every request is from this machine, and admitted") {
    assertEquals(get("/callers/whoami"), (200, "this machine"))
    assertEquals(get("/callers/only-orders")._1, 200)
  }

  test("the gateway reads as the internet") {
    assertEquals(
      get("/callers/whoami", Some(Caller.Gateway)),
      (200, "the internet, through the gateway")
    )
    assertEquals(get("/callers/only-self", Some(Caller.Gateway))._1, 403)
  }

  test("a socket is told who opened it, for every frame, as the whoami route tells a request") {
    val (name, value) = testKit.asCaller(Caller.Gateway)
    val socket =
      testKit
        .socket("/callers/socket", Map(name -> value))
        .fold(r => fail(s"refused: $r"), identity)
    socket.send("who")
    assertEquals(socket.receive(), Some("the internet, through the gateway"))
    socket.send("who")
    assertEquals(socket.receive(), Some("the internet, through the gateway"))
    socket.close()
  }
