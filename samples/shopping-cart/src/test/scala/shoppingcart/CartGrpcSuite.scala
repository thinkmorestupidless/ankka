package shoppingcart

import com.thinkmorestupidless.ankka.grpc.{GrpcChannels, GrpcServer}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.grpc.{ManagedChannel, Status, StatusRuntimeException}
import shoppingcart.api.{CartGrpcEndpoint, ShoppingCartEndpoint}
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.v1.cart.{AddItemRequest, CartServiceGrpc, GetCartRequest, LineItem}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt

/**
 * The cart's gRPC API over a whole service: a generated client -> grpc-java -> endpoint ->
 * ComponentClient -> sharded entity -> Postgres, with the HTTP endpoint served beside it.
 */
class CartGrpcSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  // docs:start grpc-test
  private val grpc = GrpcServer.at("127.0.0.1", 0)(clients => CartGrpcEndpoint(clients))
  private val http =
    HttpServer.at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))

  private var testKit: AnkkaTestKit   = null
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(grpc, http))
    channel = GrpcChannels.plaintext(grpc.boundPort.getOrElse(fail("the gRPC server did not bind")))

  override def afterAll(): Unit =
    if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    if testKit != null then testKit.stop()

  private def carts = CartServiceGrpc.blockingStub(channel)

  test("an item added is in the cart read back") {
    val _    = carts.addItem(AddItemRequest("grpc-1", Some(LineItem("p1", "Widget", 2))))
    val cart = carts.getCart(GetCartRequest("grpc-1"))
    assertEquals(cart.items.map(i => (i.productId, i.quantity)), Seq("p1" -> 2))
  }
  // docs:end grpc-test

  test("a cart nobody has written to is empty") {
    assertEquals(carts.getCart(GetCartRequest("grpc-empty")).items, Nil)
  }

  test("adding to a checked-out cart is refused as a failed precondition, with the cart's reason") {
    val _ = carts.addItem(AddItemRequest("grpc-2", Some(LineItem("p1", "Widget", 1))))
    val _ = testKit.componentClient
      .forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId("grpc-2"))
      .call(ShoppingCartEntity.checkout)
      .invoke()
    val failure = intercept[StatusRuntimeException](
      carts.addItem(AddItemRequest("grpc-2", Some(LineItem("p2", "Gadget", 1))))
    )
    assertEquals(failure.getStatus.getCode, Status.Code.FAILED_PRECONDITION)
    assertEquals(failure.getStatus.getDescription, "cart is already checked out")
  }

  test("the HTTP endpoint of the same service answers beside it") {
    val _ = carts.addItem(AddItemRequest("grpc-3", Some(LineItem("p1", "Widget", 1))))
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(
            URI.create(
              s"http://127.0.0.1:${http.boundPort.getOrElse(fail("no HTTP port"))}/carts/grpc-3"
            )
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(response.statusCode, 200)
    assert(response.body.contains("\"p1\""), response.body)
  }
