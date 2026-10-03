// What each cart holds, as a node of its own beside the cart's: built from the cart's state, read through
// the component client, rather than from the event. It publishes to the same topic as the cart graph,
// `cart-graph`, under element ids no other consumer writes.
import { GraphConsumer } from "ankka"
import { ShoppingCartEvent, totalQuantity } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"

// docs:start graph-from-state
/**
 * An event says what changed — one item added — and an element is its whole state: how many lines the
 * cart has now. So the element is not built from the event. The cart is read through the client and
 * published as it is found, at the version of the event being handled.
 *
 * The cart read may already be ahead of that event. The delta then carries a later state at an earlier
 * version, and the events still to come publish it again at their own: the graph is never behind for
 * longer than the consumer is, and ends where the cart is.
 */
export class CartContentsGraph extends GraphConsumer<ShoppingCartEvent> {
  static readonly componentId = "cart-contents-graph"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly producesTo = "cart-graph"

  async onMessage(event: ShoppingCartEvent) {
    if (event.type === "Discarded") return this.effects.ignore()
    const id = this.subject
    const cart = await this.client.of(ShoppingCartEntity, id).call(ShoppingCartEntity.handlers.getCart).invoke()
    return this.effects.publish([
      this.graph.node(`cart-contents:${id}`, {
        labels: ["CartContents"],
        properties: { cartId: id, lines: cart.items.length, quantity: totalQuantity(cart) },
      }),
    ])
  }

  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`cart-contents:${this.subject}`)])
  }
}
// docs:end graph-from-state
