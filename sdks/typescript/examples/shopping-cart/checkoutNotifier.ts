// Turns an internal event into an action elsewhere: the cart's own events are an implementation detail,
// and this consumer decides which are worth acting on. Here it records the checkout in the checkout log
// through the client — a consumer that publishes to a topic instead declares `producesTo` and an `out`
// shape, and the sidecar needs a broker (ANKKA_KAFKA_BOOTSTRAP_SERVERS).
import { Consumer } from "ankka"
import { ShoppingCartEvent } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"
import { CheckoutLog } from "./checkoutLog.ts"

// docs:start consumer
export class CheckoutNotifier extends Consumer<ShoppingCartEvent> {
  static readonly componentId = "checkout-notifier"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events

  async onMessage(event: ShoppingCartEvent) {
    if (event.type !== "CheckedOut") return this.effects.ignore()
    await this.client.of(CheckoutLog, this.subject).call(CheckoutLog.handlers.record).invoke(BigInt(Date.now()))
    return this.effects.done()
  }
}
// docs:end consumer
