package shoppingcart

import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.LineItem

import scala.concurrent.duration.DurationInt

/**
 * The real thing: cluster sharding, Postgres, JSON in the journal, replay.
 *
 * Everything here goes through `ComponentClient`, which is the only way one component addresses
 * another — so this also exercises the routing an endpoint or workflow would use.
 */
class ShoppingCartIntegrationSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(ShoppingCartEntity.descriptor)

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()

  private def cart(id: String) =
    testKit.componentClient.forEventSourcedEntity(EntityId(id))

  test("a command is persisted and visible to a later read") {
    val id = "cart-persist"
    assertEquals(
      cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2)),
      Done
    )
    assertEquals(
      cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1)),
      Done
    )

    val state = cart(id).call(ShoppingCartEntity.getCart).invoke()
    assertEquals(state.cartId, id)
    assertEquals(state.totalQuantity, 3)
    assertEquals(state.items.map(_.productId), List("p1", "p2"))
  }

  test("state survives losing every entity from memory") {
    val id = "cart-replay"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 5))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _  = cart(id).call(ShoppingCartEntity.removeItem).invoke("p1")
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p3", "Doohickey", 4))

    val before = cart(id).call(ShoppingCartEntity.getCart).invoke()

    // Nothing is cached across this: a whole new actor system, and every entity must
    // rebuild itself from its journal.
    testKit.restartService()

    val after = cart(id).call(ShoppingCartEntity.getCart).invoke()
    assertEquals(after, before, "replayed state must equal the state before restart")
    assertEquals(after.totalQuantity, 4)
  }

  test("entities with different ids are fully isolated") {
    val _ = cart("cart-a").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _ = cart("cart-b").call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 9))

    assertEquals(cart("cart-a").call(ShoppingCartEntity.totalQuantity).invoke(), 1)
    assertEquals(cart("cart-b").call(ShoppingCartEntity.totalQuantity).invoke(), 9)
  }

  test("a rejection arrives as a typed CommandError, not a generic failure") {
    val failure = intercept[CommandError] {
      cart("cart-reject").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 0))
    }
    assertEquals(failure.code, ErrorCode.BadRequest)
    assert(failure.getMessage.contains("greater than zero"), failure.getMessage)

    // The rejection must not have been persisted.
    assert(cart("cart-reject").call(ShoppingCartEntity.getCart).invoke().isEmpty)
  }

  test("a NotFound rejection keeps its classification across the wire") {
    val failure = intercept[CommandError] {
      cart("cart-404").call(ShoppingCartEntity.removeItem).invoke("ghost")
    }
    assertEquals(failure.code, ErrorCode.NotFound)
  }

  test("checkout persists then deletes, and the id becomes reusable") {
    val id = "cart-checkout"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 3))

    val checkedOut = cart(id).call(ShoppingCartEntity.checkout).invoke()
    assert(checkedOut.checkedOut)
    assertEquals(checkedOut.totalQuantity, 3)

    // Deleted, so the cart is empty again rather than permanently bricked.
    assert(cart(id).call(ShoppingCartEntity.getCart).invoke().isEmpty)
    assertEquals(cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p9", "New", 1)), Done)
  }

  test("calls fan out concurrently with invokeAsync") {
    import scala.concurrent.Await
    import scala.concurrent.ExecutionContext.Implicits.global

    val ids = (1 to 20).map(n => s"cart-fan-$n")
    val issued = ids.map { id =>
      cart(id).call(ShoppingCartEntity.addItem).invokeAsync(LineItem("p1", "Widget", 1))
    }
    Await.result(scala.concurrent.Future.sequence(issued), 60.seconds): Unit

    val totals = ids.map(id => cart(id).call(ShoppingCartEntity.totalQuantity).invoke())
    assertEquals(totals.toSet, Set(1))
  }

  test("an unknown entity reads as empty rather than failing") {
    val state = cart("cart-never-touched").call(ShoppingCartEntity.getCart).invoke()
    assert(state.isEmpty)
    assertEquals(state.cartId, "cart-never-touched")
  }
