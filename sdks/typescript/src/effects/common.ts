// What every kind's effects share: the outcome of a request (a reply, no reply, or a refusal), the
// retention a handler may ask for, and the error codes. Inert values; nothing here performs I/O.

import type { Duration } from "../time.ts"

/** The refusal codes a handler may answer with, and the HTTP status each becomes at an endpoint. */
export const ErrorCode = Object.freeze({
  Internal: "INTERNAL",
  BadRequest: "BAD_REQUEST",
  Unauthorized: "UNAUTHORIZED",
  Forbidden: "FORBIDDEN",
  NotFound: "NOT_FOUND",
  Conflict: "CONFLICT",
  Timeout: "TIMEOUT",
  Unavailable: "UNAVAILABLE",
} as const)
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode]

const HTTP_STATUS: Readonly<Record<ErrorCode, number>> = Object.freeze({
  INTERNAL: 500,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  TIMEOUT: 504,
  UNAVAILABLE: 503,
})

export function httpStatusOf(code: ErrorCode): number {
  return HTTP_STATUS[code]
}

/** A refusal, as a value: what `effects.error(message, code)` carries and what a caller's `CommandError` holds. */
export interface ErrorDetail {
  readonly message: string
  readonly code: ErrorCode
}

/** Thrown by the component client when the callee refused. */
export class CommandError extends Error {
  readonly code: ErrorCode
  constructor(detail: ErrorDetail) {
    super(detail.message)
    this.name = "CommandError"
    this.code = detail.code
  }
  get detail(): ErrorDetail {
    return { message: this.message, code: this.code }
  }
}

/** Metadata on a request or a reply: trace and span ids, the source's id, a timer's name. */
export type Metadata = Readonly<Record<string, string>>

/**
 * The three cases of an effect's outcome. A `Reply` computes its value from the state *after* the
 * effect's events, which is why it is a function and not a value.
 */
export type Outcome<S, R> =
  | { readonly kind: "reply"; readonly compute: (state: S) => R; readonly metadata?: Metadata }
  | { readonly kind: "no-reply" }
  | { readonly kind: "fail"; readonly error: ErrorDetail }

export const Outcome = Object.freeze({
  reply<S, R>(compute: (state: S) => R, metadata?: Metadata): Outcome<S, R> {
    return Object.freeze({ kind: "reply", compute, ...(metadata ? { metadata } : {}) })
  },
  noReply<S>(): Outcome<S, never> {
    return Object.freeze({ kind: "no-reply" })
  },
  fail<S>(message: string, code: ErrorCode = ErrorCode.Internal): Outcome<S, never> {
    return Object.freeze({ kind: "fail", error: Object.freeze({ message, code }) })
  },
})

/** What an entity asks the runtime to do with it after this effect: delete now, or expire after a while. */
export type Retention =
  | { readonly kind: "delete-now" }
  | { readonly kind: "expire-after"; readonly after: Duration }

export const Retention = Object.freeze({
  deleteNow: Object.freeze({ kind: "delete-now" }) as Retention,
  expireAfter(after: Duration): Retention {
    return Object.freeze({ kind: "expire-after", after })
  },
})

/**
 * Every effect has a `kind`; read-only effects have `kind: "read-only"`, which is what lets `query`
 * refuse a persisting handler at compile time. `_reply` is a phantom carrying the reply type.
 */
export interface EffectLike<R = unknown> {
  readonly kind: string
  readonly _reply?: R
}
export interface ReadOnlyLike<R = unknown> extends EffectLike<R> {
  readonly kind: "read-only"
}
