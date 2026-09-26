// Where the notifier records checkouts: a key value entity per cart holding when it happened.
import { Done, done, KeyValueEntity, command, jsonCodec, query, s, type Infer } from "ankka"

// docs:start key-value
export const CheckoutRecord = s.record("CheckoutRecord", { cartId: s.string, at: s.long, notified: s.boolean })
export type CheckoutRecord = Infer<typeof CheckoutRecord>

export class CheckoutLog extends KeyValueEntity<CheckoutRecord> {
  static readonly componentId = "checkout-log"
  static readonly state = jsonCodec(CheckoutRecord, "checkout-record")

  static readonly handlers = {
    record: command("record", s.long, Done, (log: CheckoutLog, at) => log.effects.updateState({ cartId: log.entityId, at, notified: true }).thenReply(() => done)),
    get: query("get", CheckoutRecord, (log: CheckoutLog) => log.effects.reply(log.state)),
  }

  emptyState(): CheckoutRecord {
    return { cartId: this.entityId, at: 0n, notified: false }
  }
}
// docs:end key-value
