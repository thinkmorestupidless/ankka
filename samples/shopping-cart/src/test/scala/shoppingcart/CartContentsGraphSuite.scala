package shoppingcart

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, TestTransport}
import shoppingcart.application.{CartContentsGraph, ShoppingCartEntity}
import shoppingcart.domain.ShoppingCartEvent.*
import shoppingcart.domain.{LineItem, ShoppingCart}

/**
 * A graph element built from the entity's state, read through the component client: nothing
 * started, the cart stubbed.
 */
class CartContentsGraphSuite extends munit.FunSuite:

  private def kitFor(cart: ShoppingCart) =
    ConsumerTestKit.graph(
      CartContentsGraph,
      TestTransport().stub(ShoppingCartEntity.getCart)(() => cart).client
    )

  private val pen = LineItem("p1", "Pen", 2)
  private val ink = LineItem("p2", "Ink", 3)

  test("the element is the cart's state as read, at the version of the event being handled") {
    val cart   = ShoppingCart("c1", List(pen, ink), checkedOut = false)
    val deltas = kitFor(cart).onMessage(ItemAdded(ink), subject = "c1", sequenceNumber = 2)

    assertEquals(deltas.map(d => d.key -> d.version), Vector("node:cart-contents:c1" -> 2L))
    assertEquals(deltas.head.labels, Vector("CartContents"))
    assertEquals(deltas.head.properties, Map("cartId" -> "c1", "lines" -> 2L, "quantity" -> 5L))
  }

  test(
    "a cart read ahead of the event is published at the event's version, and later events catch up"
  ) {
    // The consumer is handling the first event, and the cart has already had its second.
    val ahead = kitFor(ShoppingCart("c1", List(pen, ink), checkedOut = false))
    val early = ahead.onMessage(ItemAdded(pen), subject = "c1", sequenceNumber = 1).head
    assertEquals((early.version, early.properties("lines")), (1L, 2L))
    // The second event publishes the same state at its own version, which is what a graph keeps.
    val late = ahead.onMessage(ItemAdded(ink), subject = "c1", sequenceNumber = 2).head
    assertEquals((late.version, late.properties), (2L, early.properties))
  }

  test("a discard publishes nothing, and the deletion publishes the element's tombstone") {
    val kit = kitFor(ShoppingCart.empty("c1"))
    assertEquals(kit.onMessage(Discarded, subject = "c1", sequenceNumber = 3), Vector.empty)
    val deltas = kit.onDelete(subject = "c1", sequenceNumber = 4)
    assertEquals(
      deltas.map(d => (d.key, d.isTombstone, d.version)),
      Vector(("node:cart-contents:c1", true, 4L))
    )
  }

  test("a cart that cannot be read fails the change, so it is delivered again") {
    val unstubbed = ConsumerTestKit.graph(CartContentsGraph)
    intercept[Exception](unstubbed.onMessage(ItemAdded(pen), subject = "c1", sequenceNumber = 1))
  }
