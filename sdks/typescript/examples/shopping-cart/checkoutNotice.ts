// A consumer that publishes onward to a topic. The sidecar needs a broker (ANKKA_KAFKA_BOOTSTRAP_SERVERS),
// so the example registers it only where there is one.
// docs:start publishing-consumer
import { Consumer, jsonCodec, s, type Infer } from "ankka"
import { ShoppingCartEvent } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"

/** What leaves the service when a cart is checked out. */
export const CheckoutNotice = s.record("CheckoutNotice", { cartId: s.string, at: s.long })
export type CheckoutNotice = Infer<typeof CheckoutNotice>

/**
 * Turns an internal event into a published one.
 *
 * The cart's own event type is an implementation detail; this consumer decides which events are
 * worth telling the outside world about and in what shape — so the domain stays free to change
 * without breaking downstream consumers.
 */
export class CheckoutNotices extends Consumer<ShoppingCartEvent, CheckoutNotice> {
  static readonly componentId = "checkout-notices"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly out = jsonCodec(CheckoutNotice, "checkout-notice")
  static readonly producesTo = "cart-checkouts"

  onMessage(event: ShoppingCartEvent) {
    switch (event.type) {
      case "CheckedOut":
        return this.effects.produce({ cartId: this.subject, at: BigInt(Date.now()) })
      case "ItemAdded":
      case "ItemRemoved":
      case "Discarded":
        return this.effects.ignore()
    }
  }
}
// docs:end publishing-consumer
