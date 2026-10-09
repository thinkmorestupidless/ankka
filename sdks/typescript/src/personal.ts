// Personal fields (protocol 1.15): a value of one data subject, kept encrypted under that subject's key.
//
// A field declared `s.personal(inner)` is written as the personal envelope every SDK writes,
// `{"subject", "project", "data"[, "lookup"]}`, where `data` is AES-256-GCM over the value's JSON with
// the subject and project as associated data. When the data subject is erased its key is destroyed,
// and every copy of the field reads as erased from then on.
//
// The keys come from the runtime beside the process, which holds the keyring's channel. The codecs
// are synchronous, so a key not yet held is fetched with one blocking call: a worker thread makes the
// gRPC call while this thread waits on `Atomics.wait`.

import { createHmac } from "node:crypto"
import { Worker } from "node:worker_threads"
import type { ErrorCode } from "./effects/common.ts"

export type Personal<T> = Present<T> | Erased

export interface Present<T> {
  readonly kind: "present"
  readonly subject: string
  readonly value: T
  /** Whether a view's declared query may match the field by equality. */
  readonly lookup: boolean
  /** The project the value belongs to, when it was read from another project's envelope. */
  readonly project?: string
  /** Read from a store, or written once: re-written for an erased subject, it is written erased. */
  stored?: boolean
}

export interface Erased {
  readonly kind: "erased"
  readonly subject: string
  readonly project?: string
}

export class DataSubjectError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "DataSubjectError"
  }
}

/** A personal field that cannot be written or read; `code` is the platform's error code for it. */
export class PersonalFieldError extends Error {
  readonly code: "BAD_REQUEST" | "FORBIDDEN" | "UNAVAILABLE"
  constructor(message: string, code: "BAD_REQUEST" | "FORBIDDEN" | "UNAVAILABLE") {
    super(message)
    this.name = "PersonalFieldError"
    this.code = code
  }
}

/** The platform's error code for a personal field that could not be written. */
export function personalErrorCode(e: PersonalFieldError): ErrorCode {
  return e.code
}

const SUBJECT = /^[A-Za-z0-9._\-/:]{1,253}$/

export function checkSubject(subject: string): string {
  if (typeof subject !== "string" || subject === "") throw new DataSubjectError("a data subject is required")
  if (subject.length > 253) throw new DataSubjectError(`a data subject is at most 253 characters, not ${subject.length}`)
  if (!SUBJECT.test(subject)) throw new DataSubjectError(`a data subject is letters, digits, '.', '_', '-', '/' and ':' only: '${subject}'`)
  return subject
}

/** A personal value of `subject`. Refused at once for a subject this process was told is erased. */
export function present<T>(subject: string, value: T, options: { lookup?: boolean } = {}): Present<T> {
  checkSubject(subject)
  const keys = current
  if (keys && keys.isDestroyed(keys.ownProject, subject)) {
    throw new PersonalFieldError(`data subject ${subject} is erased: no personal field can be written for it`, "BAD_REQUEST")
  }
  return { kind: "present", subject, value, lookup: options.lookup ?? false }
}

export function erased(subject: string): Erased {
  checkSubject(subject)
  return { kind: "erased", subject }
}

/** The value, or `undefined` when the subject is erased — including a value held since its erasure. */
export function valueOf<T>(p: Personal<T>): T | undefined {
  if (p.kind === "erased") return undefined
  const keys = current
  if (keys && keys.isDestroyed(p.project ?? keys.ownProject, p.subject)) return undefined
  return p.value
}

export function isErased(p: Personal<unknown>): boolean {
  return valueOf(p) === undefined
}

// ── Keys ─────────────────────────────────────────────────────────────────────

export type KeyAnswer =
  | { readonly kind: "key"; readonly project: string; readonly key: Uint8Array }
  | { readonly kind: "destroyed"; readonly project: string }
  | { readonly kind: "refused"; readonly project: string; readonly reason: string }
  | { readonly kind: "unavailable"; readonly project: string; readonly reason: string }

export interface KeySource {
  readonly ownProject: string | undefined
  key(project: string | undefined, subject: string, create: boolean): KeyAnswer
  lookupToken(plaintext: Uint8Array): string
  isDestroyed(project: string | undefined, subject: string): boolean
}

let current: KeySource | undefined
let lookupAllowed = false

/** The process's keys: set by the server when it starts, or by a test. */
export function installKeys(source: KeySource | undefined): void {
  current = source
}

/** Installs `source()` unless keys are already installed; answers what it installed, if anything. */
export function installKeysIfAbsent<K extends KeySource>(source: () => K): K | undefined {
  if (current !== undefined) return undefined
  const made = source()
  current = made
  return made
}

export function keySource(): KeySource {
  if (current === undefined) {
    throw new PersonalFieldError(
      "no keyring is available here: a personal field is written and read only inside a service the runtime hosts, or a test that installs a key source",
      "UNAVAILABLE",
    )
  }
  return current
}

/** Around a view's row write: the one place a lookup token is written. */
export function allowingLookup<T>(body: () => T): T {
  const before = lookupAllowed
  lookupAllowed = true
  try {
    return body()
  } finally {
    lookupAllowed = before
  }
}

export function isLookupAllowed(): boolean {
  return lookupAllowed
}

/** Keys a test hands over: one key for every subject of `project`. Never for a running service. */
export class FixedKeys implements KeySource {
  readonly ownProject: string
  readonly otherProjects = new Map<string, Uint8Array>()
  readonly #key: Uint8Array
  readonly #lookupKey: Uint8Array
  readonly #gone: Set<string>

  constructor(project: string, key: Uint8Array, lookupKey: Uint8Array = new Uint8Array(32), destroyed: Iterable<string> = []) {
    this.ownProject = project
    this.#key = key
    this.#lookupKey = lookupKey
    this.#gone = new Set(destroyed)
  }

  key(project: string | undefined, subject: string): KeyAnswer {
    const p = project ?? this.ownProject
    if (p === this.ownProject && this.#gone.has(subject)) return { kind: "destroyed", project: p }
    if (p === this.ownProject) return { kind: "key", project: p, key: this.#key }
    const other = this.otherProjects.get(p)
    return other ? { kind: "key", project: p, key: other } : { kind: "refused", project: p, reason: "no grant" }
  }

  lookupToken(plaintext: Uint8Array): string {
    return hmacHex(this.#lookupKey, plaintext)
  }

  isDestroyed(project: string | undefined, subject: string): boolean {
    return (project ?? this.ownProject) === this.ownProject && this.#gone.has(subject)
  }

  erase(subject: string): void {
    this.#gone.add(subject)
  }
}

function hmacHex(key: Uint8Array, data: Uint8Array): string {
  return createHmac("sha256", key).update(data).digest("hex")
}

// ── The sidecar's keys, fetched with a blocking call ───────────────────────────

// A gRPC unary call over HTTP/2, made by a worker while the codec's thread waits. Plain JavaScript
// evaluated as a worker, so it needs no build step and imports nothing of the SDK's: the caller
// hands it the request's bytes and reads back the reply's.
const WORKER_SOURCE = `
const { parentPort } = require("node:worker_threads")
const http2 = require("node:http2")
let session, sessionAddress
function connect(address) {
  if (!session || session.closed || session.destroyed || sessionAddress !== address) {
    if (session) session.close()
    session = http2.connect("http://" + address)
    session.on("error", () => {})
    session.unref()
    sessionAddress = address
  }
  return session
}
parentPort.on("message", ({ address, path, body, signal, data }) => {
  const flags = new Int32Array(signal)
  const out = new Uint8Array(data)
  const finish = (state, bytes) => {
    const n = Math.min(bytes.length, out.length)
    out.set(bytes.subarray(0, n))
    Atomics.store(flags, 1, n)
    Atomics.store(flags, 0, state)
    Atomics.notify(flags, 0)
  }
  try {
    const req = connect(address).request({ ":method": "POST", ":path": path, "content-type": "application/grpc", te: "trailers" })
    const chunks = []
    let status = null, message = ""
    req.on("response", (h) => { if (h["grpc-status"] !== undefined) { status = h["grpc-status"]; message = h["grpc-message"] || "" } })
    req.on("trailers", (t) => { status = t["grpc-status"]; message = t["grpc-message"] || "" })
    req.on("data", (c) => chunks.push(c))
    req.on("error", (e) => finish(2, Buffer.from(String(e && e.message || e))))
    req.on("end", () => {
      if (status !== null && status !== "0") return finish(2, Buffer.from("grpc-status " + status + " " + decodeURIComponent(message)))
      const all = Buffer.concat(chunks)
      finish(1, all.length >= 5 ? all.subarray(5, 5 + all.readUInt32BE(1)) : Buffer.alloc(0))
    })
    const frame = Buffer.alloc(5 + body.length)
    frame.writeUInt32BE(body.length, 1)
    Buffer.from(body).copy(frame, 5)
    req.end(frame)
  } catch (e) {
    finish(2, Buffer.from(String(e && e.message || e)))
  }
})
`

/** One blocking gRPC unary call at a time, made by a worker thread. */
export class BlockingCalls {
  readonly #worker: Worker
  readonly #signal = new SharedArrayBuffer(8)
  readonly #data = new SharedArrayBuffer(1 << 20)
  readonly #address: () => string

  constructor(address: () => string) {
    this.#address = address
    this.#worker = new Worker(WORKER_SOURCE, { eval: true })
    this.#worker.unref()
  }

  /** The reply's bytes, or throws with the reason the call failed. */
  call(path: string, body: Uint8Array, timeoutMillis = 30_000): Uint8Array {
    const flags = new Int32Array(this.#signal)
    Atomics.store(flags, 0, 0)
    this.#worker.postMessage({ address: this.#address(), path, body, signal: this.#signal, data: this.#data })
    if (Atomics.wait(flags, 0, 0, timeoutMillis) === "timed-out") throw new Error(`no answer from the runtime within ${timeoutMillis}ms`)
    const bytes = new Uint8Array(this.#data, 0, Atomics.load(flags, 1)).slice()
    if (Atomics.load(flags, 0) !== 1) throw new Error(new TextDecoder().decode(bytes))
    return bytes
  }

  close(): Promise<number> {
    return this.#worker.terminate()
  }
}
