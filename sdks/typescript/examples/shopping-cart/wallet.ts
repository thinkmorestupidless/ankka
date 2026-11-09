// A player's wallet: balances by currency, each deposit applied once by its idempotency key.
import { EventSourcedEntity, command, jsonCodec, query, s, type Infer } from "ankka"

export const Wallet = s.record("Wallet", { balances: s.stringMap(s.long), applied: s.list(s.string) })
export type Wallet = Infer<typeof Wallet>
export const Deposited = s.record("Deposited", { key: s.string, currency: s.string, amount: s.long })
export type Deposited = Infer<typeof Deposited>
export const Deposit = s.record("Deposit", { key: s.string, currency: s.string, amount: s.long })
export type Deposit = Infer<typeof Deposit>
export const DepositReply = s.record("DepositReply", { currency: s.string, balance: s.long, applied: s.boolean })
export type DepositReply = Infer<typeof DepositReply>

export class WalletEntity extends EventSourcedEntity<Wallet, Deposited> {
  static readonly componentId = "wallet"
  static readonly state = jsonCodec(Wallet, "wallet")
  static readonly events = jsonCodec(Deposited, "deposited")

  static readonly handlers = {
    deposit: command("deposit", Deposit, DepositReply, (w: WalletEntity, request) => w.deposit(request)),
    get: query("get", Wallet, (w: WalletEntity) => w.effects.reply(w.state)),
  }

  emptyState(): Wallet {
    return { balances: {}, applied: [] }
  }

  applyEvent(wallet: Wallet, event: Deposited): Wallet {
    const balance = (wallet.balances[event.currency] ?? 0n) + event.amount
    return { balances: { ...wallet.balances, [event.currency]: balance }, applied: [...wallet.applied, event.key] }
  }

  deposit(request: Deposit) {
    if (request.key === "") return this.effects.error("a deposit needs an idempotency key")
    if (request.amount <= 0n) return this.effects.error(`amount must be positive, was ${request.amount}`)
    if (this.state.applied.includes(request.key)) {
      return this.effects.reply({ currency: request.currency, balance: this.state.balances[request.currency] ?? 0n, applied: false })
    }
    return this.effects
      .persist({ key: request.key, currency: request.currency, amount: request.amount })
      .thenReply((w) => ({ currency: request.currency, balance: w.balances[request.currency] ?? 0n, applied: true }))
  }
}
