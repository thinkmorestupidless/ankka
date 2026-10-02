package shoppingcart.application

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.graph.{GraphConsumer, GraphElement}
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

// docs:start graph-consumer
/**
 * Publishes the carts as a graph: a node for each cart, a node for each checkout, and an edge from
 * one to the other.
 *
 * Each handler says which elements the change leaves in which state. Every element is its whole
 * state — the cart node carries all of its properties each time — and takes the event's sequence
 * number as its version, so the newest state wins wherever the graph is kept and a change handled
 * twice changes nothing.
 */
final class CartGraph extends GraphConsumer[ShoppingCartEvent]:

  def onMessage(event: ShoppingCartEvent): Effect =
    val cartId = messageContext.subject
    event match
      case ItemAdded(_) | ItemRemoved(_) =>
        effects.publish(cart(cartId, checkedOut = false))
      case CheckedOut =>
        effects.publish(
          cart(cartId, checkedOut = true),
          graph.node(s"checkout:$cartId", Seq("Checkout"), Map("cartId" -> cartId)),
          graph.edge(
            s"checked-out:$cartId",
            "CHECKED_OUT",
            from = s"cart:$cartId",
            to = s"checkout:$cartId"
          )
        )
      // The deletion that follows is what removes the cart from the graph.
      case Discarded => effects.ignore()

  /** A discarded cart is deleted: mark its node, so an older delta cannot bring it back. */
  override def onDelete: Effect =
    effects.publish(graph.tombstoneNode(s"cart:${messageContext.subject}"))

  private def cart(cartId: String, checkedOut: Boolean): GraphElement =
    graph.node(s"cart:$cartId", Seq("Cart"), Map("cartId" -> cartId, "checkedOut" -> checkedOut))

object CartGraph
    extends GraphConsumer.Companion[CartGraph, ShoppingCartEvent](
      componentId = ComponentId("cart-graph"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      topic = "cart-graph"
    ):
  def create(ctx: ConsumerContext) = new CartGraph
// docs:end graph-consumer
