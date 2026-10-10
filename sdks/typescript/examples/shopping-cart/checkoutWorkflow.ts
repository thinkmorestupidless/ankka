// A checkout as a durable multi-step process: reserve the stock, charge the customer, and check the cart
// out — or compensate. The sidecar journals every transition and runs the steps; a step that throws is
// retried and failed over as declared in `settings`.
import { Done, done, Duration, ErrorCode, Workflow, command, jsonCodec, query, s, step, workflowSettings, type Infer } from "ankka"
import { ShoppingCartEntity } from "./entity.ts"

export const Checkout = s.record("Checkout", { cartId: s.string, status: s.string, reserved: s.int, mode: s.string })
export type Checkout = Infer<typeof Checkout>

export class PaymentDeclined extends Error {}

// docs:start workflow
export class CheckoutWorkflow extends Workflow<Checkout> {
  static readonly componentId = "checkout"
  static readonly state = jsonCodec(Checkout, "checkout")
  static readonly settings = workflowSettings({
    defaultStepTimeout: Duration.ofSeconds(10),
    steps: { charge: { recovery: { maxRetries: 1, failoverTo: "compensate" } } },
  })

  static readonly handlers = {
    /**
     * `mode`: `ok`, `fail` (the charge is declined and compensated), `pause` (a pause before it), `abort`
     * (declined; the compensation records it and fails the workflow) or `drop` (declined; the compensation
     * fails the workflow and records nothing).
     */
    start: command("start", s.string, Done, (w: CheckoutWorkflow, mode) => w.start(mode)),
    status: query("status", Checkout, (w: CheckoutWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    reserve: step("reserve", (w: CheckoutWorkflow) => w.reserve()),
    wait: step("wait", (w: CheckoutWorkflow) => w.wait()),
    charge: step("charge", (w: CheckoutWorkflow) => w.charge()),
    compensate: step("compensate", (w: CheckoutWorkflow) => w.compensate()),
  }

  emptyState(): Checkout {
    return { cartId: this.entityId, status: "new", reserved: 0, mode: "ok" }
  }

  start(mode: string) {
    if (this.state.status !== "new") return this.effects.error(`checkout is already ${this.state.status}`, ErrorCode.Conflict)
    return this.effects.updateState({ ...this.state, status: "reserving", mode }).thenTransitionTo("reserve").thenReply(() => done)
  }

  async reserve() {
    // A client call from a step: what the cart holds.
    const total = await this.cart().call(ShoppingCartEntity.handlers.totalQuantity).invoke()
    const next = this.state.mode === "pause" ? "wait" : "charge"
    return this.stepEffects.updateState({ ...this.state, status: "reserved", reserved: total }).thenTransitionTo(next)
  }

  wait() {
    return this.stepEffects.updateState({ ...this.state, status: "waiting" }).thenPause({ after: Duration.ofMillis(1500), onTimeout: "charge" })
  }

  async charge() {
    if (["fail", "abort", "drop"].includes(this.state.mode)) throw new PaymentDeclined("payment declined")
    // Not idempotent — a retry after the cart was checked out is refused — which is why `charge` is
    // allowed one retry and then fails over, and why compensation exists.
    if (this.state.reserved > 0) await this.cart().call(ShoppingCartEntity.handlers.checkout).invoke()
    return this.stepEffects.updateState({ ...this.state, status: "charged" }).thenEnd()
  }

  compensate() {
    if (this.state.mode === "abort") return this.stepEffects.updateState({ ...this.state, status: "aborted", reserved: 0 }).thenFail("payment declined")
    if (this.state.mode === "drop") return this.stepEffects.fail("payment declined")
    return this.stepEffects.updateState({ ...this.state, status: "compensated", reserved: 0 }).thenEnd()
  }

  cart() {
    return this.client.of(ShoppingCartEntity, this.state.cartId)
  }
}
// docs:end workflow
