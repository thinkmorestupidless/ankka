package shoppingcart

import nakka.http.HttpServer
import nakka.testkit.NakkaTestKit
import shoppingcart.api.ShoppingCartEndpoint
import shoppingcart.application.ShoppingCartEntity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * The full stack over real HTTP: JDK client -> pekko-http -> endpoint -> ComponentClient -> sharded
 * entity -> Postgres.
 *
 * Uses the JDK's own HTTP client rather than a pekko one, so the test exercises nakka from outside
 * as an ordinary web service.
 */
class ShoppingCartHttpSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: NakkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    // Port 0 so concurrent test runs cannot collide.
    server = HttpServer.at("127.0.0.1", 0)(ShoppingCartEndpoint(_))
    testKit = NakkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(server))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String] = None
  ): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(30))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None =>
        builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def item(productId: String, name: String, quantity: Int) =
    s"""{"productId":"$productId","name":"$name","quantity":$quantity}"""

  test("health is served without an endpoint or an acl") {
    assertEquals(send("GET", "/_nakka/health"), (200, "ok"))
  }

  test("posting an item returns 204 and the cart reflects it") {
    val (postStatus, _) = send("POST", "/carts/http-1/items", Some(item("p1", "Widget", 2)))
    assertEquals(postStatus, 204, "Done maps to 204, not an empty 200")

    val (getStatus, body) = send("GET", "/carts/http-1")
    assertEquals(getStatus, 200)
    assert(body.contains("\"cartId\":\"http-1\""), body)
    assert(body.contains("\"productId\":\"p1\""), body)
    assert(body.contains("\"quantity\":2"), body)
  }

  test("a scalar response is bare JSON") {
    val _ = send("POST", "/carts/http-total/items", Some(item("p1", "Widget", 3)))
    val _ = send("POST", "/carts/http-total/items", Some(item("p2", "Gadget", 4)))
    assertEquals(send("GET", "/carts/http-total/total"), (200, "7"))
  }

  test("a domain rule violation becomes a 400 with the entity's own message") {
    val (status, body) = send("POST", "/carts/http-bad/items", Some(item("p1", "Widget", 0)))
    assertEquals(status, 400)
    assert(body.contains("greater than zero"), body)
    assert(body.startsWith("{\"status\":400"), body)
  }

  test("a NotFound rejection from deep inside becomes a 404") {
    val (status, body) = send("DELETE", "/carts/http-404/items/ghost")
    assertEquals(status, 404)
    assert(body.contains("does not contain"), body)
  }

  test("a Conflict rejection becomes a 409") {
    val _ = send("POST", "/carts/http-conflict/items", Some(item("p1", "Widget", 1)))
    val (checkoutStatus, _) = send("POST", "/carts/http-conflict/checkout")
    assertEquals(checkoutStatus, 200)

    // Cart was deleted by checkout, so it is empty — which fails a different rule.
    val (again, body) = send("POST", "/carts/http-conflict/checkout")
    assertEquals(again, 400, body)
  }

  test("checkout returns the checked-out cart as JSON") {
    val _              = send("POST", "/carts/http-checkout/items", Some(item("p1", "Widget", 5)))
    val (status, body) = send("POST", "/carts/http-checkout/checkout")
    assertEquals(status, 200)
    assert(body.contains("\"checkedOut\":true"), body)
    assert(body.contains("\"quantity\":5"), body)
  }

  test("removing an item works through the full path") {
    val _ = send("POST", "/carts/http-remove/items", Some(item("p1", "Widget", 1)))
    val _ = send("POST", "/carts/http-remove/items", Some(item("p2", "Gadget", 1)))
    assertEquals(send("DELETE", "/carts/http-remove/items/p1")._1, 204)

    val (_, body) = send("GET", "/carts/http-remove")
    assert(!body.contains("\"p1\""), body)
    assert(body.contains("\"p2\""), body)
  }

  test("an unknown path is a 404, a wrong verb is a 405") {
    assertEquals(send("GET", "/nope/whatever")._1, 404)
    assertEquals(send("GET", "/carts/x/items/y/z")._1, 404)
    // /carts/{cartId} exists for GET but not for PUT.
    assertEquals(send("PUT", "/carts/http-405")._1, 405)
  }

  test("a malformed JSON body is a 400, not a 500") {
    val (status, body) = send("POST", "/carts/http-junk/items", Some("{not json"))
    assertEquals(status, 400, body)
    assert(body.contains("malformed JSON"), body)
  }
