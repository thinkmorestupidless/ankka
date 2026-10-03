// Publishes the cart as a graph: a node for the cart, a node for its checkout, and the edge between
// them, each as a graph delta on the topic `cart-graph`. An ankka-flow pipeline whose only streamlet is
// the built-in merge sink keeps a graph database in step with that topic. The sidecar needs a broker
// (ANKKA_KAFKA_BOOTSTRAP_SERVERS), and the topic must be compacted: the pipeline that reads it creates it.
import { GraphConsumer } from "ankka"
import { ShoppingCartEvent } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"

// docs:start graph-consumer
export class CartGraph extends GraphConsumer<ShoppingCartEvent> {
  static readonly componentId = "cart-graph"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly producesTo = "cart-graph"

  /** Each element is the whole state of a node or an edge; its version is the event's sequence number. */
  onMessage(event: ShoppingCartEvent) {
    const id = this.subject
    switch (event.type) {
      case "ItemAdded":
      case "ItemRemoved":
        return this.effects.publish([this.cart(id, false)])
      case "CheckedOut":
        return this.effects.publish([
          this.cart(id, true),
          this.graph.node(`checkout:${id}`, { labels: ["Checkout"], properties: { cartId: id } }),
          this.graph.edge(`checked-out:${id}`, { type: "CHECKED_OUT", from: `cart:${id}`, to: `checkout:${id}` }),
        ])
      default:
        // Discarded: the deletion that follows is what marks the cart.
        return this.effects.ignore()
    }
  }

  /** The cart was deleted: a tombstone marks its node, above every version published before. */
  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`cart:${this.subject}`)])
  }

  private cart(id: string, checkedOut: boolean) {
    return this.graph.node(`cart:${id}`, { labels: ["Cart"], properties: { cartId: id, checkedOut } })
  }
}
// docs:end graph-consumer
