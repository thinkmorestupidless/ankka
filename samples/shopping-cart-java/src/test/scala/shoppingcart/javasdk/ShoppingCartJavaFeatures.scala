package shoppingcart.javasdk

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import java.net.http.{HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * The Scala cart's own living features, run against the cart written in Java: each step is the
 * request a customer's client makes over HTTP, as `CartFeatures` sends it to the Scala cart.
 *
 * The cart's tools and its checkout notice need an MCP endpoint, a consumer and a view in Java,
 * which arrive with every component kind (user story 3); until then their steps are skipped by
 * name, never passed.
 */
class ShoppingCartJavaFeatures extends GherkinSuite("../shopping-cart/features") with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null

  /** The answer to the last request a `When` step made. */
  private var last: (Int, String) = (0, "")

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Main.service())

  override def afterAll(): Unit = if kit != null then kit.close()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest.newBuilder(kit.http.resolve(path)).timeout(Duration.ofSeconds(30))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = kit.httpClient.send(builder.build(), JdkResponse.BodyHandlers.ofString())
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

  private def quantityIn(body: String, product: String): Int =
    s""""productId":"${id(product)}","name":"[^"]*","quantity":(-?\\d+)""".r
      .findFirstMatchIn(body)
      .map(_.group(1).toInt)
      .getOrElse(0)

  private def quantityOf(product: String): Int =
    val (status, body) = send("GET", cart)
    assertEquals(status, 200, body)
    quantityIn(body, product)

  private def refused(status: Int, words: String): Unit =
    assertEquals(last._1, status, s"expected a refusal with $status: ${last._2}")
    assert(last._2.contains(words), s"refused, but not because $words: ${last._2}")

  private def notYet(): Unit =
    assume(false, "needs a Java MCP endpoint, consumer and view: user story 3")

  Given("an empty cart")(() => ())

  Given("a cart holding {int} of {string}") { (quantity: Int, product: String) =>
    setUp(add(quantity, product))
  }

  When("the customer adds {int} of {string}") { (quantity: Int, product: String) =>
    last = add(quantity, product)
  }

  Then("the cart holds {int} of {string}") { (quantity: Int, product: String) =>
    assertEquals(quantityOf(product), quantity)
  }

  Given("a cart holding {int} of {string} and {int} of {string}") {
    (q1: Int, p1: String, q2: Int, p2: String) =>
      setUp(add(q1, p1))
      setUp(add(q2, p2))
  }

  Given("a checked-out cart holding {int} of {string}") { (quantity: Int, product: String) =>
    setUp(add(quantity, product))
    assertEquals(send("POST", s"$cart/checkout")._1, 200)
  }

  When("the customer removes {string}") { (product: String) =>
    last = send("DELETE", s"$cart/items/${id(product)}")
  }

  When("the customer checks out") { () =>
    last = send("POST", s"$cart/checkout")
  }

  When("the customer discards the cart") { () =>
    last = send("DELETE", cart)
  }

  Then("the checkout notice of the cart is read from the topic")(() => notYet())

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

  When("the customer's assistant asks for the cart's tools")(() => notYet())
  Then("the assistant is told the tools {string}, {string} and {string}") {
    (_: String, _: String, _: String) => notYet()
  }
  When("the customer's assistant adds {int} of {string} with the tool {string}") {
    (_: Int, _: String, _: String) => notYet()
  }
  When("the customer's assistant reads the cart with the tool {string}")((_: String) => notYet())
  When("the customer's assistant checks the cart out with the tool {string}") { (_: String) =>
    notYet()
  }
  Then("the assistant is told the cart holds {int} of {string}") { (_: Int, _: String) =>
    notYet()
  }
  Then("the assistant is told {string}")((_: String) => notYet())

  for change <- Seq("addition", "removal", "checkout", "discard") do
    Then(s"the $change is refused because the cart is checked out") { () =>
      refused(409, "cart is already checked out")
    }
