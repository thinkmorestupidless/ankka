package shoppingcart

import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import shoppingcart.application.{CartRows, CheckoutWorkflow, ShoppingCartEntity}
import shoppingcart.domain.{LineItem, ShoppingCart}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Runs `GuideSamples`, and the component client's samples, against the cart. */
class GuideSamplesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(clients => CartRowsEndpoint(clients))
    testKit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CartRows.descriptor, CheckoutWorkflow.descriptor),
      Seq(ProjectionRuntime(), server)
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def send(method: String, path: String, body: String = ""): (Int, String) =
    val request = JdkRequest
      .newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "text/plain")
      .method(method, JdkRequest.BodyPublishers.ofString(body))
      .build()
    val response = http.send(request, JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("the cart and its endpoint are a valid service") {
    assert(GuideSamples.service.validate.isRight)
  }

  test("a component is addressed by kind and instance, then by handler") {
    val componentClient = testKit.componentClient
    // docs:start addressing
    val cart = componentClient.forEventSourcedEntity(EntityId("cart-1"))

    cart.call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2)) // Done
    cart.call(ShoppingCartEntity.getCart).invoke()                            // ShoppingCart
    // docs:end addressing
    val state: ShoppingCart = cart.call(ShoppingCartEntity.getCart).invoke()
    assertEquals(state.items, List(LineItem("p1", "Widget", 2)))
  }

  test("several calls at once, each collected on the virtual thread") {
    val componentClient = testKit.componentClient
    val cartIds         = Vector("fan-1", "fan-2")
    cartIds.zipWithIndex.foreach((id, n) =>
      componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(ShoppingCartEntity.addItem)
        .invoke(LineItem("p1", "Widget", n + 1)): Unit
    )
    // docs:start fan-out
    val pending = cartIds.map(id =>
      componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(ShoppingCartEntity.totalQuantity)
        .invokeAsync()
    )
    val totals = pending.map(ComponentClient.await(_, 10.seconds))
    // docs:end fan-out
    assertEquals(totals, Vector(1, 2))
  }

  test("a refusal reaches the caller as a CommandError carrying its code") {
    val cart  = testKit.componentClient.forEventSourcedEntity(EntityId("refused-1"))
    val asked =
      // docs:start refusal
      try cart.call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 0))
      catch case e: CommandError if e.code == ErrorCode.BadRequest => Done
        // docs:end refusal
    assertEquals(asked, Done)
    assertEquals(cart.call(ShoppingCartEntity.totalQuantity).invoke(), 0)
  }

  test("a view's row is answered, and a cart with none is a 404 naming it") {
    val _ = testKit.componentClient
      .forEventSourcedEntity(EntityId("rows-1"))
      .call(ShoppingCartEntity.addItem)
      .invoke(LineItem("p1", "Widget", 2))
    val body = eventually("the row appears") {
      Some(send("GET", "/carts/rows-1/rows")).collect { case (200, body) => body }
    }
    assert(body.contains("\"cartId\":\"rows-1\""), body)
    val (status, missing) = send("GET", "/carts/nobody/rows")
    assertEquals(status, 404)
    assert(missing.contains("no row for cart 'nobody'"), missing)
    assert(send("GET", "/carts/rows/count")._1 == 200)
  }

  test("a route starts a workflow with the request's body") {
    val _ = testKit.componentClient
      .forEventSourcedEntity(EntityId("checkout-1"))
      .call(ShoppingCartEntity.addItem)
      .invoke(LineItem("p1", "Widget", 1))
    val (status, body) = send("POST", "/carts/checkout-1/checkouts", "ok")
    assertEquals(status, 204, body)
    val ended = eventually("the checkout ends") {
      val state = testKit.componentClient
        .forWorkflow(EntityId("checkout-1"))
        .call(CheckoutWorkflow.status)
        .invoke()
      Option.when(state.status == "charged")(state)
    }
    assertEquals(ended.reserved, 1)
  }
