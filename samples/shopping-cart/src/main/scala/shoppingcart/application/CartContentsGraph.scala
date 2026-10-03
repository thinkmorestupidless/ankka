package shoppingcart.application

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.graph.GraphConsumer
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

// docs:start graph-from-state
/**
 * Publishes what each cart holds, as a node of its own beside the cart's.
 *
 * An event says what changed — one item added — and an element is its whole state: how many lines
 * the cart has now. So this consumer does not build the element from the event. It reads the cart
 * through the component client and publishes what it finds, at the version of the event it is
 * handling.
 *
 * The cart it reads may already be ahead of that event. The delta then carries a later state at an
 * earlier version, and the events still to come publish it again at their own: the graph is never
 * behind for longer than the consumer is, and ends where the cart is.
 */
final class CartContentsGraph(client: ComponentClient) extends GraphConsumer[ShoppingCartEvent]:

  def onMessage(event: ShoppingCartEvent): Effect = event match
    case Discarded => effects.ignore()
    case ItemAdded(_) | ItemRemoved(_) | CheckedOut =>
      val cartId = messageContext.subject
      val cart = client
        .forEventSourcedEntity(EntityId(cartId))
        .call(ShoppingCartEntity.getCart)
        .invoke()
      effects.publish(
        graph.node(
          s"cart-contents:$cartId",
          Seq("CartContents"),
          Map("cartId" -> cartId, "lines" -> cart.items.size, "quantity" -> cart.totalQuantity)
        )
      )

  override def onDelete: Effect =
    effects.publish(graph.tombstoneNode(s"cart-contents:${messageContext.subject}"))

object CartContentsGraph
    extends GraphConsumer.Companion[CartContentsGraph, ShoppingCartEvent](
      componentId = ComponentId("cart-contents-graph"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      topic = "cart-graph"
    ):
  def create(ctx: ConsumerContext) = new CartContentsGraph(ctx.componentClient)
// docs:end graph-from-state
