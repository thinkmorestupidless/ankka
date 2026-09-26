// Effects for key value entities: replace the state, then decide what to reply.

import type { Duration } from "../time.ts"
import { done, type Done } from "../schema.ts"
import { ErrorCode, Outcome, Retention, type EffectLike, type Metadata, type ReadOnlyLike } from "./common.ts"

export interface UpdateEffect<S, R> extends EffectLike<R> {
  readonly kind: "update"
  /** The state to store; `null` when the effect only deletes. */
  readonly newState: S | null
  readonly retention: Retention | null
  readonly outcome: Outcome<S, R>
}

export interface KeyValueReadOnlyEffect<S, R> extends ReadOnlyLike<R> {
  readonly kind: "read-only"
  readonly retention: null
  readonly outcome: Outcome<S, R>
}

export type KeyValueEffect<S, R> = UpdateEffect<S, R> | KeyValueReadOnlyEffect<S, R>

export class UpdateBuilder<S> {
  readonly #newState: S | null
  readonly #retention: Retention | null

  constructor(newState: S | null, retention: Retention | null = null) {
    this.#newState = newState
    this.#retention = retention
  }

  deleteEntity(): UpdateBuilder<S> {
    return new UpdateBuilder(this.#newState, Retention.deleteNow)
  }

  expireAfter(after: Duration): UpdateBuilder<S> {
    return new UpdateBuilder(this.#newState, Retention.expireAfter(after))
  }

  /** Reply with a value computed from the new state. */
  thenReply<R>(compute: (state: S) => R, metadata?: Metadata): UpdateEffect<S, R> {
    return Object.freeze({ kind: "update", newState: this.#newState, retention: this.#retention, outcome: Outcome.reply(compute, metadata) })
  }

  thenReplyState(): UpdateEffect<S, S> {
    return this.thenReply((state) => state)
  }

  thenNoReply(): UpdateEffect<S, never> {
    return Object.freeze({ kind: "update", newState: this.#newState, retention: this.#retention, outcome: Outcome.noReply() })
  }
}

/** The factory a key value entity's handler reaches as `this.effects`. */
export class KeyValueEffects<S> {
  updateState(state: S): UpdateBuilder<S> {
    return new UpdateBuilder<S>(state)
  }

  /** Delete the entity, keeping the state as it is until then. */
  deleteEntity(): UpdateBuilder<S> {
    return new UpdateBuilder<S>(null, Retention.deleteNow)
  }

  reply<R>(value: R, metadata?: Metadata): KeyValueReadOnlyEffect<S, R> {
    return Object.freeze({ kind: "read-only", retention: null, outcome: Outcome.reply(() => value, metadata) })
  }

  error(message: string, code: ErrorCode = ErrorCode.BadRequest): KeyValueReadOnlyEffect<S, never> {
    return Object.freeze({ kind: "read-only", retention: null, outcome: Outcome.fail(message, code) })
  }

  noReply(): KeyValueReadOnlyEffect<S, never> {
    return Object.freeze({ kind: "read-only", retention: null, outcome: Outcome.noReply() })
  }
}

export { done, type Done }
