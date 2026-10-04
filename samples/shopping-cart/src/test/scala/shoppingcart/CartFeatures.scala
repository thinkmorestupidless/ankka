package shoppingcart

import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}
import shoppingcart.api.{CheckoutsSeenEndpoint, ShoppingCartEndpoint}
import shoppingcart.application.{CheckoutNotifier, CheckoutsSeen, ShoppingCartEntity}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * The cart's living features (the `.feature` files under `features/cart`), run against the whole
 * service: each step is a request a customer's client would make over HTTP, so the routes, the
 * entity's rules, its refusals and the journal are all inside what a scenario checks.
 *
 * Each scenario works on a cart of its own, named by `scenarioId`, so no scenario sees another's. A
 * refusal is checked by its status and by the entity's own words, because a status alone passes for
 * any refusal of that kind, including the wrong one.
 */
class CartFeatures extends GherkinSuite("features") with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl               = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  /** The answer to the last request a `When` step made. */
  private var last: (Int, String) = (0, "")

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(
      clients => ShoppingCartEndpoint(clients.componentClient),
      clients => CheckoutsSeenEndpoint(clients.viewClient)
    )
    // The notices go to an in-memory broker, and the view reads them back from it, as the deployed
    // service does from the installation's broker.
    val broker = InMemoryBroker()
    testKit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CheckoutNotifier.descriptor, CheckoutsSeen.descriptor),
      Seq(server, ProjectionRuntime.withBroker(broker, broker))
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def cart                = s"/carts/$scenarioId"
  private def id(product: String) = product.toLowerCase.replaceAll("[^a-z0-9]+", "-")

  private def add(quantity: Int, product: String): (Int, String) =
    send(
      "POST",
      s"$cart/items",
      Some(s"""{"productId":"${id(product)}","name":"$product","quantity":$quantity}""")
    )

  private def setUp(result: (Int, String)): Unit =
    assertEquals(result._1, 204, s"setting up the cart failed: ${result._2}")

  /** The quantity of one product in a cart's JSON; 0 when the cart has no line for it. */
  private def quantityIn(body: String, product: String): Int =
    s""""productId":"${id(product)}","name":"[^"]*","quantity":(-?\\d+)""".r
      .findFirstMatchIn(body)
      .map(_.group(1).toInt)
      .getOrElse(0)

  /** The quantity of one product in the cart, as the cart's own read reports it. */
  private def quantityOf(product: String): Int =
    val (status, body) = send("GET", cart)
    assertEquals(status, 200, body)
    quantityIn(body, product)

  private def refused(status: Int, words: String): Unit =
    assertEquals(last._1, status, s"expected a refusal with $status: ${last._2}")
    assert(last._2.contains(words), s"refused, but not because $words: ${last._2}")

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("an empty cart")(() => ()) // every scenario's cart is new: nothing has been added to it

  // docs:start steps
  Given("a cart holding {int} of {string}") { (quantity: Int, product: String) =>
    setUp(add(quantity, product))
  }

  When("the customer adds {int} of {string}") { (quantity: Int, product: String) =>
    last = add(quantity, product)
  }

  Then("the cart holds {int} of {string}") { (quantity: Int, product: String) =>
    assertEquals(quantityOf(product), quantity)
  }
  // docs:end steps

  Given("a cart holding {int} of {string} and {int} of {string}") {
    (q1: Int, p1: String, q2: Int, p2: String) =>
      setUp(add(q1, p1))
      setUp(add(q2, p2))
  }

  Given("a checked-out cart holding {int} of {string}") { (quantity: Int, product: String) =>
    setUp(add(quantity, product))
    assertEquals(send("POST", s"$cart/checkout")._1, 200)
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("the customer removes {string}") { (product: String) =>
    last = send("DELETE", s"$cart/items/${id(product)}")
  }

  Then("the checkout notice of the cart is read from the topic") { () =>
    // Read through a topic and a projection, so it arrives a moment after the checkout answers.
    val deadline = 30.seconds.fromNow
    var seen     = send("GET", s"/checkouts-seen/$scenarioId")
    while seen._1 != 200 && deadline.hasTimeLeft() do
      Thread.sleep(200)
      seen = send("GET", s"/checkouts-seen/$scenarioId")
    assertEquals(seen._1, 200, s"no checkout notice of the cart was read: ${seen._2}")
    assert(seen._2.contains(s""""cartId":"$scenarioId""""), seen._2)
  }

  When("the customer checks out") { () =>
    last = send("POST", s"$cart/checkout")
  }

  When("the customer discards the cart") { () =>
    last = send("DELETE", cart)
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the cart holds {int} item(s) in total") { (total: Int) =>
    val (status, body) = send("GET", s"$cart/total")
    assertEquals((status, body.trim), (200, total.toString))
  }

  Then("the cart is checked out") { () =>
    val (status, body) = send("GET", cart)
    assertEquals(status, 200, body)
    assert(body.contains("\"checkedOut\":true"), body)
  }

  Then("the cart is empty") { () =>
    val (status, body) = send("GET", cart)
    assertEquals(status, 200, body)
    assert(body.contains("\"items\":[]"), body)
  }

  // The checkout's own answer: the cart as it was checked out, from the request a `When` made.
  Then("the checkout holds {int} of {string}") { (quantity: Int, product: String) =>
    assertEquals(last._1, 200, last._2)
    assert(last._2.contains("\"checkedOut\":true"), last._2)
    assertEquals(quantityIn(last._2, product), quantity)
  }

  Then("the addition is refused because the quantity is not positive") { () =>
    refused(400, "quantity must be greater than zero")
  }

  Then("the removal is refused because the cart does not hold the product") { () =>
    refused(404, "cart does not contain")
  }

  Then("the checkout is refused because the cart is empty") { () =>
    refused(400, "cannot check out an empty cart")
  }

  // One rule refuses every change to a checked-out cart; each Then names the change it refused.
  for change <- Seq("addition", "removal", "checkout", "discard") do
    Then(s"the $change is refused because the cart is checked out") { () =>
      refused(409, "cart is already checked out")
    }
