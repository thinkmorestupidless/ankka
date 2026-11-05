// Personal fields (feature 042): a value about one data subject, never held in the clear.
//
// Marked in a shape with `s.personal(inner)` and built with `personal(subject("player/8c1f"), value)`.
// Written by a serializer (a payload sent to the sidecar) it is the personal envelope of
// protocol/ENCODING.md, sealed with AES-256-GCM under the subject's key; read back it is present, or
// erased once the key is destroyed. Where no serializer is writing (an endpoint's reply or a caller's
// body) it is the value with its subject beside it. Its textual form names the subject, never the value.

import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto"
import { CommandError, ErrorCode } from "./effects/common.ts"

// ── the data subject ─────────────────────────────────────────────────────────

declare const subjectBrand: unique symbol

/** Who a personal field is about: 1 to 253 characters from letters, digits, `.`, `_`, `-`, `/` and `:`. */
export type DataSubject = string & { readonly [subjectBrand]: true }

/** The rule, in the words every SDK refuses with. */
export const DATA_SUBJECT_RULE = "a data subject is 1 to 253 characters from letters, digits, '.', '_', '-', '/' and ':'"

const SUBJECT = /^[A-Za-z0-9._/:-]{1,253}$/

/**
 * Why `value` is not a data subject, or `undefined` when it is one. The reason names the rule and never
 * the value, which may be the personal data a caller meant to protect (FR-029).
 */
export function subjectProblem(value: unknown): string | undefined {
  if (typeof value === "string" && SUBJECT.test(value)) return undefined
  return `the value given is not a data subject: ${DATA_SUBJECT_RULE}`
}

/** `value` as a data subject; throws a `TypeError` naming the rule when it is not one. */
export function subject(value: string): DataSubject {
  const problem = subjectProblem(value)
  if (problem !== undefined) throw new TypeError(problem)
  return value as DataSubject
}

// ── the field ────────────────────────────────────────────────────────────────

const inspect = Symbol.for("nodejs.util.inspect.custom")

/** A personal field holding its value. */
export class Present<T> {
  readonly kind = "present" as const
  readonly subject: DataSubject
  readonly value: T
  constructor(subject: DataSubject, value: T) {
    this.subject = subject
    this.value = value
    Object.freeze(this)
  }
  /** The value; a present field ignores `fallback`. */
  orElse<U>(_fallback: U): T | U {
    return this.value
  }
  get isErased(): false {
    return false
  }
  toString(): string {
    return `Personal(${this.subject})`
  }
  [inspect](): string {
    return this.toString()
  }
  /**
   * The subject alone, never the value: `JSON.stringify` is how an object is logged, and a personal
   * field's textual form holds no value (FR-029). A payload's and an endpoint's forms come from the
   * field's schema, which never calls this.
   */
  toJSON(): { subject: string } {
    return { subject: this.subject }
  }
}

/** A personal field whose data subject was erased: only decoding makes one. */
export class Erased {
  readonly kind = "erased" as const
  readonly subject: DataSubject
  constructor(subject: DataSubject) {
    this.subject = subject
    Object.freeze(this)
  }
  orElse<U>(fallback: U): U {
    return fallback
  }
  get isErased(): true {
    return true
  }
  toString(): string {
    return `Personal(${this.subject})`
  }
  [inspect](): string {
    return this.toString()
  }
  toJSON(): { subject: string } {
    return { subject: this.subject }
  }
}

/** A personal field: read it by `p.kind`, `"present"` or `"erased"`. */
export type Personal<T> = Present<T> | Erased

/** A personal field about `about` holding `value`. */
export function personal<T>(about: DataSubject, value: T): Personal<T> {
  const problem = subjectProblem(about)
  if (problem !== undefined) throw new TypeError(problem)
  return new Present(about, value)
}

/** @internal An erased field, as decoding makes one. */
export function erased(about: DataSubject): Erased {
  return new Erased(about)
}

export function isPersonal(value: unknown): value is Personal<unknown> {
  return value instanceof Present || value instanceof Erased
}

// ── the errors ───────────────────────────────────────────────────────────────

export type PersonalReason = "unavailable" | "erased" | "corrupt"

/**
 * Why a personal field could not be written or read. A `CommandError` whose `details.personal` is the
 * reason: `unavailable` (no keyring, or it did not answer), `erased` (a write for an erased data
 * subject), `corrupt` (an envelope that fails authentication). Reading an erased subject is never one.
 */
export class PersonalError extends CommandError {
  readonly reason: PersonalReason
  constructor(reason: PersonalReason, message: string) {
    const code = reason === "unavailable" ? ErrorCode.Unavailable : reason === "erased" ? ErrorCode.Conflict : ErrorCode.Internal
    super({ message, code, details: { personal: reason } })
    this.name = "PersonalError"
    this.reason = reason
  }

  static unavailable(why: string): PersonalError {
    return new PersonalError("unavailable", `the keyring is unavailable: ${why}`)
  }

  static erased(about: string): PersonalError {
    return new PersonalError("erased", `the data subject '${about}' is erased; nothing was written`)
  }

  static corrupt(about: string, why: string): PersonalError {
    return new PersonalError("corrupt", `the personal envelope of '${about}' is corrupt: ${why}`)
  }
}

// ── the envelope's cipher ────────────────────────────────────────────────────

/** The envelope's version; a reader refuses one it does not know. */
export const ENVELOPE_VERSION = 1
const NONCE_BYTES = 12
const TAG_BYTES = 16
const utf8 = new TextEncoder()

/** Subject, project and manifest, each as a 4-byte big-endian length and its UTF-8 bytes. */
export function associatedData(about: string, project: string, manifest: string): Uint8Array {
  const parts = [about, project, manifest].map((p) => utf8.encode(p))
  const out = new Uint8Array(parts.reduce((n, p) => n + 4 + p.length, 0))
  const view = new DataView(out.buffer)
  let at = 0
  for (const p of parts) {
    view.setUint32(at, p.length, false)
    out.set(p, at + 4)
    at += 4 + p.length
  }
  return out
}

/** `nonce ‖ ciphertext ‖ tag`. A nonce is random unless given, which only the fixtures' tests do. */
export function seal(key: Uint8Array, plaintext: Uint8Array, ad: Uint8Array, nonce?: Uint8Array): Uint8Array {
  const iv = nonce ?? randomBytes(NONCE_BYTES)
  if (iv.length !== NONCE_BYTES) throw new RangeError(`a nonce is ${NONCE_BYTES} bytes, was ${iv.length}`)
  const cipher = createCipheriv("aes-256-gcm", key, iv)
  cipher.setAAD(ad)
  const body = Buffer.concat([cipher.update(plaintext), cipher.final()])
  return new Uint8Array(Buffer.concat([iv, body, cipher.getAuthTag()]))
}

/** The plaintext, or `undefined` when the tag does not verify under this key and associated data. */
export function open(key: Uint8Array, boxed: Uint8Array, ad: Uint8Array): Uint8Array | undefined {
  if (boxed.length < NONCE_BYTES + TAG_BYTES) return undefined
  try {
    const decipher = createDecipheriv("aes-256-gcm", key, boxed.subarray(0, NONCE_BYTES))
    decipher.setAAD(ad)
    decipher.setAuthTag(boxed.subarray(boxed.length - TAG_BYTES))
    return new Uint8Array(Buffer.concat([decipher.update(boxed.subarray(NONCE_BYTES, boxed.length - TAG_BYTES)), decipher.final()]))
  } catch {
    return undefined
  }
}

// ── the scope a serializer binds ─────────────────────────────────────────────

/** @internal What a payload is being encoded or decoded under; no scope is the edge. */
export interface Scope {
  readonly manifest: string
  readonly nonce?: Uint8Array
}

let bound: Scope | undefined

/** @internal The scope bound now, if any. */
export function currentScope(): Scope | undefined {
  return bound
}

/**
 * @internal Runs `body` (synchronous: a codec's encode or decode) as a serializer writing under
 * `manifest`. `nonce` fixes the nonce every envelope in `body` is sealed with, which only the fixtures'
 * tests may do: a nonce used twice under one key breaks GCM.
 */
export function withinScope<T>(manifest: string, body: () => T, nonce?: Uint8Array): T {
  const before = bound
  bound = nonce === undefined ? { manifest } : { manifest, nonce }
  try {
    return body()
  } finally {
    bound = before
  }
}

/** @internal Runs `body` at the edge, with no scope bound. */
export function outsideScope<T>(body: () => T): T {
  const before = bound
  bound = undefined
  try {
    return body()
  } finally {
    bound = before
  }
}
