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

export type ConsumerEffect<Out> =
  | ({ readonly kind: "produce"; readonly payload: Out; readonly metadata: Metadata } & EffectLike<never>)
  | ({ readonly kind: "done" } & EffectLike<never>)
  | ({ readonly kind: "ignore" } & EffectLike<never>)

export class ConsumerEffects<Out> {
  /** Produce onward to the topic the consumer declares in `producesTo`. */
  produce(payload: Out, metadata: Metadata = {}): ConsumerEffect<Out> {
    return Object.freeze({ kind: "produce", payload, metadata })
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
