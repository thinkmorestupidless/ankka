// The effects an event sourced entity's handlers return: persist these events and then reply from the
// state after them, or answer without persisting. Inert values. `ReadOnlyEffect` is a distinct type
// with `kind: "read-only"`, which is what lets `query` refuse a persisting handler at compile time.

import type { Duration } from "../time.ts"
import { done, type Done } from "../schema.ts"
import { ErrorCode, Outcome, Retention, type EffectLike, type Metadata, type ReadOnlyLike } from "./common.ts"

export interface PersistEffect<S, E, R> extends EffectLike<R> {
  readonly kind: "persist"
  readonly events: readonly E[]
  readonly retention: Retention | null
  readonly outcome: Outcome<S, R>
}

export interface ReadOnlyEffect<S, E, R> extends ReadOnlyLike<R> {
  readonly kind: "read-only"
  readonly retention: Retention | null
  readonly outcome: Outcome<S, R>
  /** Phantom: the entity's event type, so a read-only effect is tied to its entity. */
  readonly _events?: E
}

export type EventSourcedEffect<S, E, R> = PersistEffect<S, E, R> | ReadOnlyEffect<S, E, R>

/** `effects.persist(e)` returns one of these; finish it with `thenReply`, `thenReplyState` or `thenNoReply`. */
export class PersistBuilder<S, E> {
  readonly #events: readonly E[]
  readonly #retention: Retention | null

  constructor(events: readonly E[], retention: Retention | null = null) {
    this.#events = events
    this.#retention = retention
  }

  /** Delete the entity once these events are persisted. */
  deleteEntity(): PersistBuilder<S, E> {
    return new PersistBuilder(this.#events, Retention.deleteNow)
  }

  /** Expire the entity a while after these events are persisted. */
  expireAfter(after: Duration): PersistBuilder<S, E> {
    return new PersistBuilder(this.#events, Retention.expireAfter(after))
  }

  /** Reply with a value computed from the state *after* the events. */
  thenReply<R>(compute: (state: S) => R, metadata?: Metadata): PersistEffect<S, E, R> {
    return Object.freeze({ kind: "persist", events: this.#events, retention: this.#retention, outcome: Outcome.reply(compute, metadata) })
  }

  /** Reply with the state after the events. */
  thenReplyState(): PersistEffect<S, E, S> {
    return this.thenReply((state) => state)
  }

  /** Persist and answer nothing. */
  thenNoReply(): PersistEffect<S, E, never> {
    return Object.freeze({ kind: "persist", events: this.#events, retention: this.#retention, outcome: Outcome.noReply() })
  }
}

/** The factory a handler reaches as `this.effects`. */
export class EventSourcedEffects<S, E> {
  /** Persist one or more events. */
  persist(event: E, ...more: E[]): PersistBuilder<S, E> {
    return new PersistBuilder<S, E>(Object.freeze([event, ...more]))
  }

  /** Persist a list of events; an empty list is allowed and persists nothing. */
  persistAll(events: readonly E[]): PersistBuilder<S, E> {
    return new PersistBuilder<S, E>(Object.freeze([...events]))
  }

  /** Answer without persisting. */
  reply<R>(value: R, metadata?: Metadata): ReadOnlyEffect<S, E, R> {
    return Object.freeze({ kind: "read-only", retention: null, outcome: Outcome.reply(() => value, metadata) })
  }

  /** Refuse the command: nothing is persisted and the caller sees the code. */
  error(message: string, code: ErrorCode = ErrorCode.BadRequest): ReadOnlyEffect<S, E, never> {
    return Object.freeze({ kind: "read-only", retention: null, outcome: Outcome.fail(message, code) })
  }

  /** Answer nothing and persist nothing. */
  noReply(): ReadOnlyEffect<S, E, never> {
    return Object.freeze({ kind: "read-only", retention: null, outcome: Outcome.noReply() })
  }

  /** Delete the entity, persisting nothing more; finish with `thenReply(() => done)` or `thenNoReply()`. */
  deleteEntity(): PersistBuilder<S, E> {
    return new PersistBuilder<S, E>(Object.freeze([]), Retention.deleteNow)
  }

  /** Expire the entity after a while, persisting nothing more. */
  expireAfter(after: Duration): PersistBuilder<S, E> {
    return new PersistBuilder<S, E>(Object.freeze([]), Retention.expireAfter(after))
  }
}

export { done, type Done }
