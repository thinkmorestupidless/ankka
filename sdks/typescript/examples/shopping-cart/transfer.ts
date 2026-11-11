// A transfer between two balances as a durable process: withdraw, deposit, and put the money back if the
// deposit fails. The sidecar journals every transition before the next step runs.
import { Ankka, Done, done, Duration, ErrorCode, EventSourcedEntity, Workflow, command, jsonCodec, query, s, step, workflowSettings, type Infer } from "ankka"

const BalanceEvent = s.sumType("BalanceEvent", { Withdrawn: { amount: s.int }, Deposited: { amount: s.int } })
type BalanceEvent = Infer<typeof BalanceEvent>

/** A balance the transfer moves money between. */
export class Balances extends EventSourcedEntity<number, BalanceEvent> {
  static readonly componentId = "balance"
  static readonly state = jsonCodec(s.int, "balance")
  static readonly events = jsonCodec(BalanceEvent, "balance-event")

  static readonly handlers = {
    withdraw: command("withdraw", s.int, Done, (b: Balances, amount) =>
      amount > b.state ? b.effects.error(`insufficient funds: ${b.state} < ${amount}`) : b.effects.persist({ type: "Withdrawn", amount }).thenReply(() => done),
    ),
    deposit: command("deposit", s.int, Done, (b: Balances, amount) => b.effects.persist({ type: "Deposited", amount }).thenReply(() => done)),
    balance: query("balance", s.int, (b: Balances) => b.effects.reply(b.state)),
  }

  emptyState(): number {
    return 0
  }

  applyEvent(balance: number, event: BalanceEvent): number {
    return event.type === "Withdrawn" ? balance - event.amount : balance + event.amount
  }
}

export const Transfer = s.record("Transfer", { from: s.string, to: s.string, amount: s.int })
export type Transfer = Infer<typeof Transfer>
export const TransferState = s.record("TransferState", { transfer: Transfer, status: s.string })
export type TransferState = Infer<typeof TransferState>

// docs:start declarations
export class TransferWorkflow extends Workflow<TransferState> {
  static readonly componentId = "transfer"
  static readonly state = jsonCodec(TransferState, "transfer-state")
  // docs:start settings
  static readonly settings = workflowSettings({
    timeout: Duration.ofSeconds(60),
    defaultStepTimeout: Duration.ofSeconds(10),
    // One retry, then compensate. The deposit is the step that can fail for reasons outside this
    // workflow's control, so it is the one worth a second attempt.
    steps: { deposit: { recovery: { maxRetries: 1, failoverTo: "compensate" } } },
  })
  // docs:end settings

  // Step names and handler names are wire names: renaming a method is safe, renaming the string is a
  // protocol change for every instance already in flight.
  static readonly steps = {
    withdraw: step("withdraw", Transfer, (w: TransferWorkflow, transfer) => w.withdrawStep(transfer)),
    deposit: step("deposit", Transfer, (w: TransferWorkflow, transfer) => w.depositStep(transfer)),
    compensate: step("compensate", (w: TransferWorkflow) => w.compensateStep()),
  }

  static readonly handlers = {
    start: command("start", Transfer, Done, (w: TransferWorkflow, transfer) => w.start(transfer)),
    status: query("status", TransferState, (w: TransferWorkflow) => w.effects.reply(w.state)),
  }
  // docs:end declarations

  emptyState(): TransferState {
    return { transfer: { from: "", to: "", amount: 0 }, status: "not-started" }
  }

  // docs:start start
  start(transfer: Transfer) {
    if (transfer.amount <= 0) return this.effects.error("transfer amount must be greater than zero")
    if (this.state.status !== "not-started") return this.effects.error("transfer already started", ErrorCode.Conflict)
    return this.effects.updateState({ transfer, status: "started" }).thenTransitionTo("withdraw", transfer).thenReply(() => done)
  }
  // docs:end start

  // docs:start steps
  async withdrawStep(transfer: Transfer) {
    await this.balance(transfer.from).call(Balances.handlers.withdraw).invoke(transfer.amount)
    return this.stepEffects.updateState({ ...this.state, status: "withdrawn" }).thenTransitionTo("deposit", transfer)
  }

  async depositStep(transfer: Transfer) {
    await this.balance(transfer.to).call(Balances.handlers.deposit).invoke(transfer.amount)
    return this.stepEffects.updateState({ ...this.state, status: "completed" }).thenEnd()
  }

  /**
   * Puts the money back.
   *
   * Takes no input and reads the transfer from the state, which is why failover steps are input-free:
   * what needs compensating is whatever the workflow has recorded, not whatever was known when the
   * settings were written.
   */
  async compensateStep() {
    const transfer = this.state.transfer
    await this.balance(transfer.from).call(Balances.handlers.deposit).invoke(transfer.amount)
    return this.stepEffects.updateState({ ...this.state, status: "compensated" }).thenEnd()
  }
  // docs:end steps

  balance(id: string) {
    return this.client.of(Balances, id)
  }
}

// docs:start registration
export function transferService() {
  return Ankka.service().register(Balances).register(TransferWorkflow)
}
// docs:end registration
