package shoppingcart

import com.thinkmorestupidless.ankka.core.graph.GraphDelta
import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import shoppingcart.application.CartGraph
import shoppingcart.domain.LineItem
import shoppingcart.domain.ShoppingCartEvent.*

/**
 * The graph the carts are published as, one change at a time. Nothing is started: no runtime, no
 * database, no broker.
 */
class CartGraphUnitSuite extends munit.FunSuite:

  // docs:start graph-test
  private val kit = ConsumerTestKit.graph(CartGraph)

  test("an item added publishes the cart's node, at the event's sequence number") {
    val deltas =
      kit.onMessage(ItemAdded(LineItem("p1", "Pen", 1)), subject = "c1", sequenceNumber = 1)

    assertEquals(deltas.map(_.key), Vector("node:cart:c1"))
    val cart = deltas.head
    assertEquals(cart.version, 1L)
    assertEquals(cart.labels, Vector("Cart"))
    assertEquals(cart.properties, Map("cartId" -> "c1", "checkedOut" -> false))
  }

  test("a checkout publishes the cart as checked out, the checkout, and the edge between them") {
    val deltas = kit.onMessage(CheckedOut, subject = "c1", sequenceNumber = 4)

    assertEquals(
      deltas.map(_.key),
      Vector("node:cart:c1", "node:checkout:c1", "edge:checked-out:c1")
    )
    assertEquals(deltas.map(_.version).distinct, Vector(4L))
    assertEquals(deltas(0).properties("checkedOut"), true)
    val edge = deltas(2)
    assertEquals(
      (edge.edgeType, edge.from, edge.to),
      (Some("CHECKED_OUT"), Some("cart:c1"), Some("checkout:c1"))
    )
  }

  test("a discarded cart's deletion publishes its tombstone") {
    assertEquals(kit.onMessage(Discarded, subject = "c1", sequenceNumber = 2), Vector.empty)

    val deltas = kit.onDelete(subject = "c1", sequenceNumber = 3)
    assertEquals(
      deltas.map(d => (d.key, d.isTombstone, d.version)),
      Vector(("node:cart:c1", true, 3L))
    )
  }
  // docs:end graph-test

  test("an item removed publishes the cart's node again, whole") {
    val deltas = kit.onMessage(ItemRemoved("p1"), subject = "c1", sequenceNumber = 3)
    assertEquals(deltas.map(d => d.key -> d.version), Vector("node:cart:c1" -> 3L))
    assertEquals(deltas.head.properties, Map("cartId" -> "c1", "checkedOut" -> false))
  }

  test("the same change handled twice publishes equal deltas") {
    val first  = kit.onMessage(CheckedOut, subject = "c9", sequenceNumber = 2)
    val second = kit.onMessage(CheckedOut, subject = "c9", sequenceNumber = 2)
    assertEquals(first, second)
  }

  test("every delta says what it is to anything that reads the topic") {
    val result = kit.records.onMessage(CheckedOut, "c1", 4)
    assertEquals(
      result.messages.map(_.metadata.eventType).distinct,
      Vector(Some(GraphDelta.SchemaName))
    )
    assertEquals(result.messages.map(_.metadata.subject).distinct, Vector(Some("c1")))
    assertEquals(
      result.messages.head.text,
      """{"kind":"node","id":"cart:c1","version":4,"labels":["Cart"],"properties":{"cartId":"c1","checkedOut":true}}"""
    )
  }
