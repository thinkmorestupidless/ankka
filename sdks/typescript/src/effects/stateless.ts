// Effects for the stateless kinds: a view says what an event does to a row, a consumer acknowledges
// or produces onward, a timed action is done or failed (and retried by the sweeper).

import { ErrorCode, type EffectLike, type ErrorDetail, type Metadata } from "./common.ts"

export type ViewEffect<Row> =
  | ({ readonly kind: "update-row"; readonly row: Row } & EffectLike<never>)
  | ({ readonly kind: "delete-row" } & EffectLike<never>)
  | ({ readonly kind: "ignore" } & EffectLike<never>)

export class ViewEffects<Row> {
  updateRow(row: Row): ViewEffect<Row> {
    return Object.freeze({ kind: "update-row", row })
  }
  deleteRow(): ViewEffect<Row> {
    return Object.freeze({ kind: "delete-row" })
  }
  ignore(): ViewEffect<Row> {
    return Object.freeze({ kind: "ignore" })
  }
}

/**
 * One message of several: what to publish, optionally the record key it is published under, and its
 * headers. A message with no key is keyed by its subject — the source instance's id unless the
 * metadata sets `ce-subject` — which keeps everything about one instance on one partition, in order.
 * A key is for a message about something else: a line item, an element of a graph. Naming one does
 * not change the subject.
 */
export interface OutgoingMessage<Out> {
  readonly payload: Out
  readonly key?: string
  readonly metadata?: Metadata
}

export type ConsumerEffect<Out> =
  | ({ readonly kind: "produce"; readonly payload: Out; readonly metadata: Metadata } & EffectLike<never>)
  | ({ readonly kind: "produceAll"; readonly messages: readonly OutgoingMessage<Out>[] } & EffectLike<never>)
  | ({ readonly kind: "done" } & EffectLike<never>)
  | ({ readonly kind: "ignore" } & EffectLike<never>)

export class ConsumerEffects<Out> {
  /** Produce onward to the topic the consumer declares in `producesTo`. */
  produce(payload: Out, metadata: Metadata = {}): ConsumerEffect<Out> {
    return Object.freeze({ kind: "produce", payload, metadata })
  }
  /**
   * Produce several messages for this change, in the order given. The change is handled when the
   * broker has accepted every one; if one is refused the change comes again and all are published
   * again. An empty list publishes nothing and is handled at once.
   */
  produceAll(messages: readonly OutgoingMessage<Out>[]): ConsumerEffect<Out> {
    if (!Array.isArray(messages)) throw new TypeError("produceAll takes a list of messages")
    const checked = messages.map((m, i) => {
      if (typeof m !== "object" || m === null || !("payload" in m)) throw new TypeError(`produceAll: message ${i} is not a message: { payload, key?, metadata? }`)
      if (m.key !== undefined && typeof m.key !== "string") throw new TypeError(`produceAll: message ${i} has a record key that is not a string`)
      if (m.key === "") throw new TypeError(`produceAll: message ${i} has an empty record key; leave the key out to key the message by its subject`)
      return Object.freeze({ ...m })
    })
    return Object.freeze({ kind: "produceAll", messages: Object.freeze(checked) })
  }
  done(): ConsumerEffect<Out> {
    return Object.freeze({ kind: "done" })
  }
  ignore(): ConsumerEffect<Out> {
    return Object.freeze({ kind: "ignore" })
  }
}

export type TimedActionEffect = ({ readonly kind: "done" } & EffectLike<never>) | ({ readonly kind: "fail"; readonly error: ErrorDetail } & EffectLike<never>)

export class TimedActionEffects {
  done(): TimedActionEffect {
    return Object.freeze({ kind: "done" })
  }
  /** Failed: the sweeper retries on its schedule with the attempt count incremented. */
  fail(message: string, code: ErrorCode = ErrorCode.Internal): TimedActionEffect {
    return Object.freeze({ kind: "fail", error: { message, code } })
  }
}
