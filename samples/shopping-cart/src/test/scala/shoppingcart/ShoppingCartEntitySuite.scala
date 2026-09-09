package shoppingcart

import nakka.core.{Done, ErrorCode}
import nakka.testkit.EventSourcedTestKit
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.*
import shoppingcart.domain.ShoppingCartEvent.*

/**
 * Entity behaviour with no actor system, cluster or database — but with the real serializers, so a
 * missing codec fails here.
 */
class ShoppingCartEntitySuite extends munit.FunSuite:

  private def newKit = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")

  test("adding an item persists exactly one event and acknowledges") {
    val kit    = newKit
    val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))

    assertEquals(result.replyValue, Done)
    assertEquals(result.events, Vector(ItemAdded(LineItem("p1", "Widget", 2))))
    assertEquals(result.eventOfType[ItemAdded].item.quantity, 2)
    assertEquals(kit.currentState.totalQuantity, 2)
  }

  test("state is rebuilt purely by folding events") {
    val kit = newKit
    val _   = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))
    val _   = kit.call(ShoppingCartEntity.addItem)(LineItem("p2", "Gadget", 1))
    val _   = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 1))

    assertEquals(kit.allEvents.size, 3)
    assertEquals(
      kit.currentState.items,
      List(LineItem("p1", "Widget", 3), LineItem("p2", "Gadget", 1))
    )

    val folded = kit.allEvents.foldLeft(ShoppingCart.empty("cart-1")) {
      case (cart, ItemAdded(item))        => cart.addItem(item)
      case (cart, ItemRemoved(productId)) => cart.removeItem(productId)
      case (cart, CheckedOut)             => cart.onCheckedOut
    }
    assertEquals(folded, kit.currentState, "replaying the journal must reproduce the state")
  }

  test("a rejected command persists nothing") {
    val kit    = newKit
    val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 0))

    assert(result.isError)
    assertEquals(result.error.code, ErrorCode.BadRequest)
    assert(result.errorMessage.contains("greater than zero"), result.errorMessage)
    assertEquals(result.events, Vector.empty)
    assertEquals(kit.allEvents, Vector.empty)
    assert(kit.currentState.isEmpty)
  }

  test("removing an absent product is a NotFound rejection") {
    val result = newKit.call(ShoppingCartEntity.removeItem)("ghost")
    assertEquals(result.error.code, ErrorCode.NotFound)
  }

  test("reads do not persist") {
    val kit    = newKit
    val _      = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 4))
    val before = kit.allEvents

    assertEquals(kit.call(ShoppingCartEntity.totalQuantity).replyValue, 4)
    assertEquals(kit.call(ShoppingCartEntity.getCart).replyValue.cartId, "cart-1")
    assertEquals(kit.allEvents, before)
  }

  test("checkout persists the event and then deletes the cart") {
    val kit    = newKit
    val _      = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 1))
    val result = kit.call(ShoppingCartEntity.checkout)

    // thenReplyState observes the post-event state, so checkedOut is already true.
    assert(result.replyValue.checkedOut, "reply should see the state after the event applied")
    assertEquals(result.events, Vector(CheckedOut))
    assertEquals(result.retention, Some(nakka.core.effect.Retention.DeleteNow))
    assert(kit.isDeleted)
  }

  test("an empty cart cannot be checked out") {
    val result = newKit.call(ShoppingCartEntity.checkout)
    assert(result.isError)
    assert(result.errorMessage.contains("empty"), result.errorMessage)
  }

  test("checking out twice conflicts") {
    val kit = newKit
    val _   = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 1))
    val _   = kit.call(ShoppingCartEntity.checkout)

    // Deletion leaves the id reusable with fresh state, so this is now an empty cart.
    val second = kit.call(ShoppingCartEntity.checkout)
    assert(second.isError)
  }

  test("the entity knows its own id from the context") {
    assertEquals(
      EventSourcedTestKit.of(ShoppingCartEntity, "cart-xyz").currentState.cartId,
      "cart-xyz"
    )
  }
