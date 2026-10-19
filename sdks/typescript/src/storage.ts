// A service's buckets, reached by name (feature 055): `this.storage.default` is the bucket
// `provisionObjectStorage` asks for, and `this.storage.bucket("photos")` one the descriptor's `buckets`
// list names. Offered where a wait belongs, as the secret store is — endpoints, workflow steps,
// consumers, graph consumers, timed actions, agents and autonomous agents — and never an entity or a
// view. An entity keeps an object's *name*; the bucket keeps the bytes.
//
// A process speaks S3 itself (research R14): nothing of an object crosses the sidecar. The stores are
// the runtime's, said again in TypeScript with the same rules and the same words:
//
//   - `S3Buckets`, from the `ANKKA_S3_*` variables the platform renders or a service supplies, over
//     `node:http`/`node:https` with `Content-Length` set and the body piped (never `fetch`, which sends
//     a stream chunked, and S3 refuses a chunked put);
//   - `DirectoryBuckets`, a directory on a developer's machine, in the runtime's layout, whose signed
//     URLs the service serves itself at `/_ankka/storage` through its sidecar;
//   - `InMemoryBuckets`, the test kits' store.
//
// Every call refuses with `CommandError`: `BAD_REQUEST` for a name, a prefix, a length or a span that
// breaks a rule, and for a signed URL or an address the bucket's exposure does not allow; `UNAVAILABLE`
// when the store cannot be reached, when the service has no such bucket, or when it has no storage
// credential; `INTERNAL` for an answer the client cannot read.

import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto"
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs"
import { mkdir, open, readdir, readFile, rename, rm, writeFile } from "node:fs/promises"
import { Agent as HttpAgent, request as httpRequest, type IncomingMessage, type OutgoingHttpHeaders } from "node:http"
import { Agent as HttpsAgent, request as httpsRequest } from "node:https"
import { isAbsolute, join, resolve } from "node:path"
import { Readable } from "node:stream"
import { CommandError, ErrorCode } from "./effects/common.ts"
import { Credentials, EMPTY_PAYLOAD, UNSIGNED_PAYLOAD, encode, hmac, presign, sha256Hex, sign, SignedRequest } from "./sigv4.ts"
import { Instant } from "./time.ts"

// ── The API ─────────────────────────────────────────────────────────────────

/** How a bucket is reached from outside the installation. */
export type BucketExposure = "private" | "signed" | "public" | "supplied"

/** What a signed URL lets its holder do with one object. */
export type SignedAccess = "read" | "keep"

/** An object read from a bucket. Read its `body` once, or call `bytes()`. */
export interface StoredObject {
  readonly contentType: string
  readonly length: number
  readonly lastKept: Instant
  readonly body: ReadableStream<Uint8Array>
  /** The whole body, read from `body`. */
  bytes(): Promise<Uint8Array>
}

/** What a bucket keeps under a name, without the bytes. */
export interface ObjectInfo {
  readonly name: string
  readonly contentType: string
  readonly length: number
  readonly lastKept: Instant
}

/** One object in a listing. */
export interface ObjectEntry {
  readonly name: string
  readonly length: number
  readonly lastKept: Instant
}

/** How a put keeps its body: its content type, and its length, which a stream must declare. */
export interface PutOptions {
  readonly contentType: string
  readonly length?: number
}

/**
 * One bucket. Its objects are named with `/` between segments, so a listing by prefix reads like a
 * directory; it is not one. A put replaces the whole object or leaves the old one.
 */
export interface Bucket {
  /** The descriptor's name for it; `undefined` for the unnamed bucket. */
  readonly name: string | undefined
  readonly exposure: BucketExposure
  /**
   * Keeps `body` under `name`, replacing what was there. A stream declares its `length`; one that ends
   * before it fails and keeps nothing, and the old object, if any, stays.
   */
  put(name: string, body: ReadableStream<Uint8Array> | Uint8Array, options: PutOptions): Promise<void>
  /** The object kept under `name`, or `undefined` when there is none. */
  get(name: string): Promise<StoredObject | undefined>
  /** Removes what is kept under `name`. Removing nothing is not an error. */
  delete(name: string): Promise<void>
  /** Every object whose name begins with `prefix`, in name order; pages are read as it is walked. */
  list(prefix: string): AsyncIterable<ObjectEntry>
  /** What is kept under `name`, without its bytes, or `undefined`. */
  stat(name: string): Promise<ObjectInfo | undefined>
  /**
   * A URL a browser may use, until `spanSeconds` (15 minutes by default, at most seven days) have
   * passed, to read or keep the object under `name`. Computed: no request reaches the store. A signed
   * bucket's, or the unnamed bucket's when the service sets `exposeObjectStorage`.
   */
  signedUrl(name: string, access: SignedAccess, spanSeconds?: number): string
  /** The address a browser reads the object under `name` at, with no signature: a public bucket's only. */
  url(name: string): string
}

/** A service's buckets: `default`, the unnamed one, and the named ones by name. */
export interface Buckets {
  /** The unnamed bucket. Refuses, naming `provisionObjectStorage`, when the service has none. */
  readonly default: Bucket
  /** A bucket the descriptor's `buckets` list names; refused, naming the ones it does name, otherwise. */
  bucket(name: string): Bucket
  /** Every named bucket, in no particular order. */
  readonly buckets: Bucket[]
}

// ── The rules and the words ────────────────────────────────────────────────────

const utf8 = new TextEncoder()

function shown(text: string): string {
  let visible = ""
  for (const c of text) visible += c < " " || c === "\u007f" ? "?" : c
  // As the runtime counts: UTF-16 units.
  return visible.length > 60 ? visible.slice(0, 60) + "…" : visible
}

function problem(subject: string, text: string, trailingSlash: boolean): string | undefined {
  const bytes = utf8.encode(text).length
  const body = trailingSlash && text.endsWith("/") ? text.slice(0, -1) : text
  const segments = body.split("/")
  let found: string | undefined
  if (text === "") found = `${subject} must not be empty`
  else if (bytes > ObjectRules.MAX_NAME_BYTES) found = `${subject} is at most ${ObjectRules.MAX_NAME_BYTES} bytes as UTF-8; this one is ${bytes}`
  else if (text.startsWith("/")) found = `${subject} must not begin with '/'`
  else if ([...text].some((c) => c < " " || c === "\u007f")) found = `${subject} must not hold a control character`
  else if (segments.some((s) => s === "")) found = `${subject} must not have an empty segment`
  else if (segments.some((s) => s === "." || s === "..")) found = `${subject} must not have a segment '.' or '..'`
  return found === undefined ? undefined : `${found} ("${shown(text)}")`
}

function badRequest(message: string): CommandError {
  return new CommandError({ message, code: ErrorCode.BadRequest })
}

/**
 * What an object's name, a prefix, a bucket's name and a signed URL's span may be. Every store and every
 * test double applies these, and `protocol/fixtures/storage/rules.json` holds them to the other SDKs.
 */
export const ObjectRules = Object.freeze({
  MAX_NAME_BYTES: 1024,
  MAX_BUCKET_NAME: 32,
  /** Seven days. */
  MAX_SIGNED_URL_SECONDS: 604_800,
  /** Fifteen minutes. */
  DEFAULT_SIGNED_URL_SECONDS: 900,
  BUCKET_NAME_RULE: "lower-case letters, digits and '-', 1 to 32 characters",

  /** What is wrong with an object's `name`, if anything. */
  nameProblem(name: string): string | undefined {
    return problem("an object's name", name, false)
  },
  /** What is wrong with a listing's `prefix`, if anything. Empty is everything. */
  prefixProblem(prefix: string): string | undefined {
    return prefix === "" ? undefined : problem("a prefix", prefix, true)
  },
  /** What is wrong with a bucket's name, if anything, in the descriptor's words. */
  bucketNameProblem(name: string): string | undefined {
    return /^[a-z0-9-]{1,32}$/.test(name) ? undefined : `'${shown(name)}' is not a bucket name: ${ObjectRules.BUCKET_NAME_RULE}`
  },
  /** What is wrong with a signed URL's span, in seconds, if anything. */
  spanProblem(spanSeconds: number): string | undefined {
    if (!Number.isFinite(spanSeconds) || spanSeconds <= 0) return `a signed URL's span is positive; this one is ${Math.trunc(spanSeconds)} seconds`
    if (spanSeconds > ObjectRules.MAX_SIGNED_URL_SECONDS) {
      return `a signed URL is made for at most 7 days (${ObjectRules.MAX_SIGNED_URL_SECONDS} seconds); this one asks for ${Math.trunc(spanSeconds)}`
    }
    return undefined
  },
  checkName(name: string): void {
    const p = ObjectRules.nameProblem(name)
    if (p !== undefined) throw badRequest(p)
  },
  checkPrefix(prefix: string): void {
    const p = ObjectRules.prefixProblem(prefix)
    if (p !== undefined) throw badRequest(p)
  },
  checkSpan(spanSeconds: number): void {
    const p = ObjectRules.spanProblem(spanSeconds)
    if (p !== undefined) throw badRequest(p)
  },
})

/** What the storage client says when it refuses: the runtime's words (`StorageRefusals` in the Scala SDK). */
export const StorageRefusals = Object.freeze({
  noBucket(): CommandError {
    return new CommandError({
      message: "this service has no bucket: it asks for one with provisionObjectStorage, or names one in buckets",
      code: ErrorCode.Unavailable,
    })
  },
  unknown(name: string, known: Iterable<string>): CommandError {
    const names = [...known].sort()
    const named = names.length === 0 ? "names none" : "names " + names.map((n) => `'${n}'`).join(", ")
    return new CommandError({ message: `this service has no bucket named '${name}'; its descriptor ${named}`, code: ErrorCode.Unavailable })
  },
  noCredential(): CommandError {
    return new CommandError({
      message:
        "this service has a bucket and no storage credential to sign with: it sets objectStorageCredential to false, " +
        "so it reaches its bucket as its workload identity, with its store's own client",
      code: ErrorCode.Unavailable,
    })
  },
  notExposed(): CommandError {
    return badRequest(
      "the service's bucket is not reachable from the internet, so it signs no URL: the descriptor sets exposeObjectStorage, or names a signed bucket",
    )
  },
  notSigned(bucket: string, exposure: BucketExposure): CommandError {
    return badRequest(`bucket '${bucket}' is ${exposure}; a signed URL is made for a signed bucket`)
  },
  notPublic(bucket: string | undefined, exposure: BucketExposure): CommandError {
    const label = bucket === undefined ? "the service's unnamed bucket" : `bucket '${bucket}'`
    return badRequest(`${label} is ${exposure}; an address with no signature is a public bucket's`)
  },
  unreachable(endpoint: string, cause: string): CommandError {
    return new CommandError({ message: `the object store at ${endpoint} cannot be reached: ${cause}`, code: ErrorCode.Unavailable })
  },
  refusedByStore(endpoint: string, status: number, detail: string): CommandError {
    return new CommandError({
      message: `the object store at ${endpoint} refused the request (${status})${detail === "" ? "" : ": " + detail}`,
      code: ErrorCode.Unavailable,
    })
  },
  unreadable(endpoint: string, cause: string): CommandError {
    return new CommandError({ message: `the object store at ${endpoint} answered with something unreadable: ${cause}`, code: ErrorCode.Internal })
  },
  shortBody(declared: number, read: number): CommandError {
    return badRequest(`the stream ended after ${read} of its declared ${declared} bytes; nothing was kept`)
  },
  negativeLength(length: number): CommandError {
    return badRequest(`an object's length is not negative; this one is ${length}`)
  },
  /** TypeScript's own: a stream's length cannot be known before it is read, and S3 is told it first. */
  undeclaredLength(): CommandError {
    return badRequest("a put of a stream declares its length: put(name, stream, { contentType, length })")
  },
  stepsOnly(): CommandError {
    return badRequest("a workflow keeps and reads an object in a step, not in a command handler")
  },
})

// ── Bodies ─────────────────────────────────────────────────────────────────────

/** The length a put keeps: a stream's declared one, or the bytes' own. */
function declaredLength(body: ReadableStream<Uint8Array> | Uint8Array, options: PutOptions): number {
  const length = options.length ?? (body instanceof Uint8Array ? body.length : undefined)
  if (length === undefined) throw StorageRefusals.undeclaredLength()
  if (!Number.isInteger(length) || length < 0) throw StorageRefusals.negativeLength(length)
  return length
}

/**
 * At most `length` bytes of `body`, chunk by chunk; the caller learns how many it was given, and a body
 * that ends early is short. Never reads past `length`.
 */
async function* bounded(body: ReadableStream<Uint8Array> | Uint8Array, length: number): AsyncGenerator<Uint8Array> {
  if (body instanceof Uint8Array) {
    if (length > 0) yield body.subarray(0, Math.min(length, body.length))
    return
  }
  const reader = body.getReader()
  let read = 0
  try {
    while (read < length) {
      const next = await reader.read()
      if (next.done) return
      const chunk = next.value.length > length - read ? next.value.subarray(0, length - read) : next.value
      read += chunk.length
      if (chunk.length > 0) yield chunk
    }
  } finally {
    // The caller's stream is theirs: released, not cancelled, once its declared length is read.
    reader.releaseLock()
  }
}

async function collect(body: ReadableStream<Uint8Array> | Uint8Array, length: number): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  let read = 0
  for await (const chunk of bounded(body, length)) {
    parts.push(chunk)
    read += chunk.length
  }
  if (read < length) throw StorageRefusals.shortBody(length, read)
  const out = new Uint8Array(read)
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

function streamOf(bytes: Uint8Array): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      if (bytes.length > 0) controller.enqueue(new Uint8Array(bytes))
      controller.close()
    },
  })
}

async function readAll(body: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  let total = 0
  for await (const chunk of body as unknown as AsyncIterable<Uint8Array>) {
    parts.push(chunk)
    total += chunk.length
  }
  const out = new Uint8Array(total)
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

function storedObject(contentType: string, length: number, lastKept: Instant, body: ReadableStream<Uint8Array>): StoredObject {
  return Object.freeze({ contentType, length, lastKept, body, bytes: () => readAll(body) })
}

function nowSeconds(): number {
  return Math.floor(Date.now() / 1000)
}

function checkSigned(bucket: string | undefined, exposure: BucketExposure): void {
  if (exposure === "signed" || exposure === "supplied") return
  throw bucket === undefined ? StorageRefusals.notExposed() : StorageRefusals.notSigned(bucket, exposure)
}

// ── No store at all ────────────────────────────────────────────────────────────

/** Buckets that refuse every call, naming `provisionObjectStorage`: a deployed service with no bucket. */
export const noStorage: Buckets = Object.freeze({
  get default(): Bucket {
    throw StorageRefusals.noBucket()
  },
  bucket(name: string): Bucket {
    throw StorageRefusals.unknown(name, [])
  },
  get buckets(): Bucket[] {
    return []
  },
})

// ── In memory ──────────────────────────────────────────────────────────────────

interface Kept {
  readonly contentType: string
  readonly lastKept: Instant
  readonly bytes: Uint8Array
}

export interface InMemoryBucketsOptions {
  /** The named buckets and their exposure; more may be declared later with `declare`. */
  readonly buckets?: Readonly<Record<string, BucketExposure>>
  /** Whether the service has the unnamed bucket; `true` by default. */
  readonly unnamed?: boolean
  /** The unnamed bucket's exposure: `signed` by default, so a test may sign a URL for it. */
  readonly unnamedExposure?: BucketExposure
  /** Where a public bucket's objects are read: `http://memory/public/<bucket>` by default, reaching nothing. */
  readonly publicBase?: (bucket: string) => string
}

/**
 * A service's buckets in memory: the test kits' store, so a test of a handler that keeps or reads an
 * object needs no object store and no Docker. It applies every rule the other stores apply, in the
 * same words. A signed URL is computed (`http://memory/...`) and reaches nothing.
 */
export class InMemoryBuckets implements Buckets {
  readonly #exposures = new Map<string, BucketExposure>()
  readonly #objects = new Map<string, Map<string, Kept>>()
  readonly #unnamed: boolean
  readonly #unnamedExposure: BucketExposure
  readonly #publicBase: (bucket: string) => string

  constructor(options: InMemoryBucketsOptions = {}) {
    this.#unnamed = options.unnamed ?? true
    this.#unnamedExposure = options.unnamedExposure ?? "signed"
    this.#publicBase = options.publicBase ?? ((bucket) => `http://memory/public/${bucket}`)
    for (const [name, exposure] of Object.entries(options.buckets ?? {})) this.declare(name, exposure)
  }

  get default(): Bucket {
    if (!this.#unnamed) throw StorageRefusals.noBucket()
    return this.#bucket(undefined, this.#unnamedExposure)
  }

  bucket(name: string): Bucket {
    const exposure = this.#exposures.get(name)
    if (exposure === undefined) throw StorageRefusals.unknown(name, this.#exposures.keys())
    return this.#bucket(name, exposure)
  }

  get buckets(): Bucket[] {
    return [...this.#exposures.entries()].map(([n, e]) => this.#bucket(n, e))
  }

  /** Declares a named bucket, before or after the service starts. */
  declare(name: string, exposure: BucketExposure): void {
    const p = ObjectRules.bucketNameProblem(name)
    if (p !== undefined) throw new TypeError(p)
    this.#exposures.set(name, exposure)
  }

  /** Forgets every object; the declared buckets stay. */
  clear(): void {
    this.#objects.clear()
  }

  #bucket(name: string | undefined, exposure: BucketExposure): Bucket {
    const key = name ?? ""
    const objects = (): Map<string, Kept> => {
      let m = this.#objects.get(key)
      if (!m) {
        m = new Map()
        this.#objects.set(key, m)
      }
      return m
    }
    const publicBase = this.#publicBase
    return Object.freeze({
      name,
      exposure,
      async put(objectName: string, body: ReadableStream<Uint8Array> | Uint8Array, options: PutOptions): Promise<void> {
        ObjectRules.checkName(objectName)
        const length = declaredLength(body, options)
        const bytes = await collect(body, length)
        objects().set(objectName, { contentType: options.contentType, lastKept: Instant.now(), bytes: new Uint8Array(bytes) })
      },
      async get(objectName: string): Promise<StoredObject | undefined> {
        ObjectRules.checkName(objectName)
        const s = objects().get(objectName)
        return s && storedObject(s.contentType, s.bytes.length, s.lastKept, streamOf(s.bytes))
      },
      async delete(objectName: string): Promise<void> {
        ObjectRules.checkName(objectName)
        objects().delete(objectName)
      },
      list(prefix: string): AsyncIterable<ObjectEntry> {
        ObjectRules.checkPrefix(prefix)
        const entries = [...objects().entries()]
          .filter(([n]) => n.startsWith(prefix))
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([n, s]) => Object.freeze({ name: n, length: s.bytes.length, lastKept: s.lastKept }))
        return (async function* () {
          yield* entries
        })()
      },
      async stat(objectName: string): Promise<ObjectInfo | undefined> {
        ObjectRules.checkName(objectName)
        const s = objects().get(objectName)
        return s && Object.freeze({ name: objectName, contentType: s.contentType, length: s.bytes.length, lastKept: s.lastKept })
      },
      signedUrl(objectName: string, access: SignedAccess, spanSeconds: number = ObjectRules.DEFAULT_SIGNED_URL_SECONDS): string {
        ObjectRules.checkName(objectName)
        checkSigned(name, exposure)
        ObjectRules.checkSpan(spanSeconds)
        const expires = nowSeconds() + Math.trunc(spanSeconds)
        return `http://memory/${name ?? "default"}/${encode(objectName, false)}?access=${access}&expires=${expires}`
      },
      url(objectName: string): string {
        ObjectRules.checkName(objectName)
        if (name === undefined || exposure !== "public") throw StorageRefusals.notPublic(name, exposure)
        return publicBase(name).replace(/\/$/, "") + "/" + encode(objectName, false)
      },
    })
  }
}

// ── What service.json declares ───────────────────────────────────────────────────

/** What a descriptor declares: whether the unnamed bucket is asked for, and each named one. */
export interface DeclaredBuckets {
  readonly unnamed: boolean
  readonly buckets: Readonly<Record<string, BucketExposure>>
}

/**
 * The buckets `service.json` in `directory` declares, read where a service runs on a developer's machine
 * and in a test, so the directory and memory stores refuse the names a deployed service would; `undefined`
 * when there is no such file. Only the two fields that say which buckets there are.
 */
export function declaredBuckets(directory: string = process.cwd()): DeclaredBuckets | undefined {
  const file = join(directory, "service.json")
  if (!existsSync(file)) return undefined
  let parsed: { service?: { provisionObjectStorage?: unknown; buckets?: unknown } }
  try {
    parsed = JSON.parse(readFileSync(file, "utf8")) as typeof parsed
  } catch (e) {
    throw new Error(`${file} cannot be read: ${e instanceof Error ? e.message : String(e)}`)
  }
  const service = parsed?.service ?? {}
  const buckets: Record<string, BucketExposure> = {}
  for (const b of Array.isArray(service.buckets) ? (service.buckets as { name?: unknown; exposure?: unknown; supplied?: unknown }[]) : []) {
    const name = String(b?.name ?? "")
    if (b?.supplied !== undefined && b.supplied !== null) {
      buckets[name] = "supplied"
      continue
    }
    const word = typeof b?.exposure === "string" ? b.exposure : "private"
    if (word !== "private" && word !== "signed" && word !== "public") {
      throw new Error(`buckets: '${name}' has exposure '${word}'; it is one of private, signed or public`)
    }
    buckets[name] = word
  }
  return Object.freeze({ unnamed: service.provisionObjectStorage === true, buckets: Object.freeze(buckets) })
}

// ── A directory ────────────────────────────────────────────────────────────────

/** Where a service serves its directory store's objects. */
export const STORAGE_PATH = "/_ankka/storage"
/** The unnamed bucket's directory and URL segment: no bucket name begins with `_`. */
export const DEFAULT_SEGMENT = "_default"

interface Meta {
  readonly name: string
  readonly contentType: string
  readonly length: number
  readonly lastKept: string
}

export interface DirectoryBucketsOptions {
  /** The buckets `service.json` declares; with none, the unnamed bucket and any name asked for, as private. */
  readonly declared?: DeclaredBuckets
  /** The service's own address, where its URLs point: `http://<ANKKA_HTTP_INTERFACE or 127.0.0.1>:<ANKKA_HTTP_PORT or 9000>`. */
  readonly addressBase?: () => string
}

/** The address a local service's HTTP port answers at, as the sidecar beside it binds it. */
export function localAddress(env: Readonly<Record<string, string | undefined>> = process.env): string {
  const host = env.ANKKA_HTTP_INTERFACE && env.ANKKA_HTTP_INTERFACE !== "0.0.0.0" ? env.ANKKA_HTTP_INTERFACE : "127.0.0.1"
  return `http://${host}:${env.ANKKA_HTTP_PORT || "9000"}`
}

/**
 * A service's buckets in a directory on a developer's machine: what a service keeps when it runs with no
 * variable of an object store. The runtime's layout exactly, so a Scala service and a TypeScript one read
 * each other's: one directory per bucket under `root` (`_default` for the unnamed one), an object's name
 * encoded into one file name under `objects/` (its hash when too long), and beside it under `meta/` its
 * name, content type, length and when it was kept. A put writes a temporary file and moves it into place,
 * so a stream that ends short leaves the old object.
 *
 * A signed URL and a public address point at the service's own HTTP port, under `/_ankka/storage`, which
 * the SDK serves through the sidecar while this is the service's store.
 */
export class DirectoryBuckets implements Buckets {
  readonly root: string
  readonly #declared: DeclaredBuckets | undefined
  readonly #addressBase: () => string
  #key: Buffer | undefined

  constructor(root: string, options: DirectoryBucketsOptions = {}) {
    this.root = resolve(root)
    this.#declared = options.declared
    this.#addressBase = options.addressBase ?? (() => localAddress())
  }

  get default(): Bucket {
    if (this.#declared && !this.#declared.unnamed) throw StorageRefusals.noBucket()
    return this.#bucket(undefined, "signed")
  }

  bucket(name: string): Bucket {
    if (this.#declared) {
      const exposure = this.#declared.buckets[name]
      if (exposure === undefined) throw StorageRefusals.unknown(name, Object.keys(this.#declared.buckets))
      return this.#bucket(name, exposure)
    }
    const p = ObjectRules.bucketNameProblem(name)
    if (p !== undefined) throw badRequest(p)
    return this.#bucket(name, "private")
  }

  get buckets(): Bucket[] {
    return Object.entries(this.#declared?.buckets ?? {}).map(([n, e]) => this.#bucket(n, e))
  }

  /** The bucket a URL's segment names, `_default` or a bucket's name, as this store would answer it. */
  bySegment(segment: string): { readonly bucket: Bucket; readonly segment: string } | undefined {
    try {
      return { bucket: segment === DEFAULT_SEGMENT ? this.default : this.bucket(segment), segment }
    } catch {
      return undefined
    }
  }

  /** `HMAC-SHA256(key, "<segment>/<name>|<access>|<expires>")` in hex, over the key kept in the root. */
  token(segment: string, objectName: string, access: string, expires: number): string {
    return hmac(this.#signingKey(), `${segment}/${objectName}|${access}|${expires}`).toString("hex")
  }

  /** Whether `token` signs this access until `expires`, and `expires` is still to come. */
  admits(segment: string, objectName: string, access: string, expires: number, token: string): boolean {
    if (!Number.isInteger(expires) || expires < nowSeconds()) return false
    const want = Buffer.from(this.token(segment, objectName, access, expires), "utf8")
    const got = Buffer.from(token, "utf8")
    return want.length === got.length && timingSafeEqual(want, got)
  }

  /** The key the store's URLs are signed with, made once and kept in the root so a restart honours them. */
  #signingKey(): Buffer {
    if (this.#key) return this.#key
    const file = join(this.root, ".key")
    try {
      if (!existsSync(file)) {
        mkdirSync(this.root, { recursive: true })
        const part = join(this.root, `.key-${randomUUID()}`)
        writeFileSync(part, randomBytes(32), { mode: 0o600 })
        try {
          chmodSync(part, 0o600)
        } catch {
          // a file system without modes
        }
        if (existsSync(file)) rmSync(part, { force: true })
        else renameSync(part, file)
      }
      this.#key = readFileSync(file)
      return this.#key
    } catch (e) {
      throw this.#failed(e)
    }
  }

  #failed(e: unknown): CommandError {
    return new CommandError({
      message: `the directory store at ${this.root} cannot be used: ${e instanceof Error ? e.message : String(e)}`,
      code: ErrorCode.Unavailable,
    })
  }

  #bucket(name: string | undefined, exposure: BucketExposure): Bucket {
    const store = this
    const segment = name ?? DEFAULT_SEGMENT
    const objects = join(this.root, segment, "objects")
    const metas = join(this.root, segment, "meta")
    const file = (objectName: string): string => {
      const encoded = encode(objectName, true)
      return encoded.length <= 200 ? encoded : "h-" + sha256Hex(objectName)
    }
    const io = async <A>(body: () => Promise<A>): Promise<A> => {
      try {
        return await body()
      } catch (e) {
        if (e instanceof CommandError) throw e
        throw store.#failed(e)
      }
    }
    const meta = async (objectName: string): Promise<Meta | undefined> => {
      try {
        return JSON.parse(await readFile(join(metas, file(objectName) + ".json"), "utf8")) as Meta
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code === "ENOENT") return undefined
        throw store.#failed(e)
      }
    }
    const address = (objectName: string): string => `${store.#addressBase().replace(/\/$/, "")}${STORAGE_PATH}/${segment}/${addressOf(objectName)}`
    return Object.freeze({
      name,
      exposure,
      async put(objectName: string, body: ReadableStream<Uint8Array> | Uint8Array, options: PutOptions): Promise<void> {
        ObjectRules.checkName(objectName)
        const length = declaredLength(body, options)
        const scratch = join(store.root, ".tmp")
        await io(async () => {
          await mkdir(objects, { recursive: true })
          await mkdir(metas, { recursive: true })
          await mkdir(scratch, { recursive: true })
        })
        const part = join(scratch, `${randomUUID()}.part`)
        try {
          const written = await io(async () => {
            const handle = await open(part, "w")
            let n = 0
            try {
              for await (const chunk of bounded(body, length)) {
                await handle.write(chunk)
                n += chunk.length
              }
            } finally {
              await handle.close()
            }
            return n
          })
          if (written < length) throw StorageRefusals.shortBody(length, written)
          const kept: Meta = { name: objectName, contentType: options.contentType, length, lastKept: Instant.now().toString() }
          const metaPart = join(scratch, `${randomUUID()}.meta`)
          await io(async () => {
            await writeFile(metaPart, JSON.stringify(kept))
            await rename(part, join(objects, file(objectName)))
            await rename(metaPart, join(metas, file(objectName) + ".json"))
          })
        } finally {
          await rm(part, { force: true })
        }
      },
      async get(objectName: string): Promise<StoredObject | undefined> {
        ObjectRules.checkName(objectName)
        const m = await meta(objectName)
        if (!m) return undefined
        let handle
        try {
          handle = await open(join(objects, file(objectName)), "r")
        } catch (e) {
          if ((e as NodeJS.ErrnoException).code === "ENOENT") return undefined
          throw store.#failed(e)
        }
        const body = Readable.toWeb(handle.createReadStream()) as unknown as ReadableStream<Uint8Array>
        return storedObject(m.contentType, m.length, Instant.parse(m.lastKept), body)
      },
      async delete(objectName: string): Promise<void> {
        ObjectRules.checkName(objectName)
        await io(async () => {
          await rm(join(metas, file(objectName) + ".json"), { force: true })
          await rm(join(objects, file(objectName)), { force: true })
        })
      },
      list(prefix: string): AsyncIterable<ObjectEntry> {
        ObjectRules.checkPrefix(prefix)
        return (async function* () {
          let found: string[]
          try {
            found = await readdir(metas)
          } catch (e) {
            if ((e as NodeJS.ErrnoException).code === "ENOENT") return
            throw store.#failed(e)
          }
          const entries: ObjectEntry[] = []
          for (const f of found) {
            try {
              const m = JSON.parse(await readFile(join(metas, f), "utf8")) as Meta
              if (typeof m.name === "string" && m.name.startsWith(prefix)) entries.push(Object.freeze({ name: m.name, length: m.length, lastKept: Instant.parse(m.lastKept) }))
            } catch {
              // a meta file being written, or not one: skipped, as the runtime skips it
            }
          }
          entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
          yield* entries
        })()
      },
      async stat(objectName: string): Promise<ObjectInfo | undefined> {
        ObjectRules.checkName(objectName)
        const m = await meta(objectName)
        return m && Object.freeze({ name: m.name, contentType: m.contentType, length: m.length, lastKept: Instant.parse(m.lastKept) })
      },
      signedUrl(objectName: string, access: SignedAccess, spanSeconds: number = ObjectRules.DEFAULT_SIGNED_URL_SECONDS): string {
        ObjectRules.checkName(objectName)
        checkSigned(name, exposure)
        ObjectRules.checkSpan(spanSeconds)
        const expires = nowSeconds() + Math.trunc(spanSeconds)
        const token = store.token(segment, objectName, access, expires)
        return `${address(objectName)}?access=${access}&expires=${expires}&token=${token}`
      },
      url(objectName: string): string {
        ObjectRules.checkName(objectName)
        if (exposure !== "public" || name === undefined) throw StorageRefusals.notPublic(name, exposure)
        return address(objectName)
      },
    })
  }
}

/**
 * The deepest object name whose address keeps its slashes. The sidecar matches a process's route by
 * whole segments, so the storage endpoint declares one route per depth up to this; a deeper name's
 * slashes are encoded and it travels as one segment.
 */
export const MAX_ADDRESS_SEGMENTS = 16

/** An object's name in a directory store's address: as the runtime writes it, while the sidecar can route it. */
export function addressOf(objectName: string): string {
  return encode(objectName, objectName.split("/").length > MAX_ADDRESS_SEGMENTS)
}

// ── S3 ───────────────────────────────────────────────────────────────────────────

/** One S3 bucket and how to reach it. */
export interface S3Target {
  readonly endpoint: URL
  readonly region: string
  readonly bucket: string
  readonly credentials: Credentials | undefined
  readonly publicEndpoint: URL | undefined
}

/** A bucket the descriptor names: its exposure, where it is, and for a public one the base it is read at. */
export interface NamedTarget {
  readonly name: string
  readonly exposure: BucketExposure
  readonly target: S3Target
  readonly publicUrl: string | undefined
}

/** The S3 store's settings, read from the `ANKKA_S3_*` variables as the runtime's `StorageSettings` reads them. */
export interface S3Settings {
  readonly unnamed: S3Target | undefined
  readonly named: readonly NamedTarget[]
  /** How long one request waits for an answer: 30 seconds. */
  readonly timeoutMs: number
}

const PREFIX = "ANKKA_S3_"
type Env = Readonly<Record<string, string | undefined>>

function nonEmpty(env: Env, name: string): string | undefined {
  const v = env[name]
  return v === undefined || v === "" ? undefined : v
}

function required(env: Env, name: string): string {
  const v = nonEmpty(env, name)
  if (v === undefined) throw new Error(`${name} is not set`)
  return v
}

function parseUrl(name: string, value: string): URL {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${name} is not a URL: '${value}'`)
  }
  if (url.protocol === "" || url.hostname === "") throw new Error(`${name} is not a URL with a scheme and a host: '${value}'`)
  return url
}

/** The bucket a variable's `<NAME>` names: lower case, `_` as `-`. */
export function bucketNameOf(suffix: string): string {
  return suffix.toLowerCase().replaceAll("_", "-")
}

/** The `<NAME>` of a bucket's variables: upper case, `-` as `_`. */
export function bucketVariableSuffix(bucket: string): string {
  return bucket.toUpperCase().replaceAll("-", "_")
}

/**
 * The S3 settings in `env`: the unnamed family `ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY,PUBLIC_ENDPOINT}`;
 * a named bucket the platform made, `ANKKA_S3_BUCKET_<NAME>`, `ANKKA_S3_EXPOSURE_<NAME>` and, for a public
 * one, `ANKKA_S3_PUBLIC_URL_<NAME>`, sharing the unnamed family's endpoint, region and key; a supplied
 * bucket, the five `ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY}_<NAME>` of its own. Throws,
 * naming the variable, on a family that cannot be read.
 */
export function s3Settings(env: Env = process.env, timeoutMs = 30_000): S3Settings {
  let unnamed: S3Target | undefined
  const endpoint = nonEmpty(env, PREFIX + "ENDPOINT")
  if (endpoint !== undefined) {
    const access = nonEmpty(env, PREFIX + "ACCESS_KEY")
    const secret = nonEmpty(env, PREFIX + "SECRET_KEY")
    const publicEndpoint = nonEmpty(env, PREFIX + "PUBLIC_ENDPOINT")
    unnamed = Object.freeze({
      endpoint: parseUrl(PREFIX + "ENDPOINT", endpoint),
      region: required(env, PREFIX + "REGION"),
      bucket: env[PREFIX + "BUCKET"] ?? "",
      credentials: access !== undefined && secret !== undefined ? new Credentials(access, secret) : undefined,
      publicEndpoint: publicEndpoint === undefined ? undefined : parseUrl(PREFIX + "PUBLIC_ENDPOINT", publicEndpoint),
    })
  }
  const suffixes = new Set<string>()
  for (const key of Object.keys(env)) {
    for (const p of [PREFIX + "BUCKET_", PREFIX + "ENDPOINT_"]) {
      if (key.startsWith(p) && key.length > p.length) suffixes.add(key.slice(p.length))
    }
  }
  const named: NamedTarget[] = []
  for (const suffix of [...suffixes].sort()) {
    const name = bucketNameOf(suffix)
    const own = nonEmpty(env, `${PREFIX}ENDPOINT_${suffix}`)
    if (own !== undefined) {
      named.push(
        Object.freeze({
          name,
          exposure: "supplied" as const,
          target: Object.freeze({
            endpoint: parseUrl(`${PREFIX}ENDPOINT_${suffix}`, own),
            region: required(env, `${PREFIX}REGION_${suffix}`),
            bucket: required(env, `${PREFIX}BUCKET_${suffix}`),
            credentials: new Credentials(required(env, `${PREFIX}ACCESS_KEY_${suffix}`), required(env, `${PREFIX}SECRET_KEY_${suffix}`)),
            publicEndpoint: undefined,
          }),
          publicUrl: undefined,
        }),
      )
      continue
    }
    const bucket = required(env, `${PREFIX}BUCKET_${suffix}`)
    if (!unnamed) throw new Error(`bucket '${name}' (${PREFIX}BUCKET_${suffix}) is made by the platform and ${PREFIX}ENDPOINT is not set`)
    const word = nonEmpty(env, `${PREFIX}EXPOSURE_${suffix}`) ?? "private"
    if (word !== "private" && word !== "signed" && word !== "public") {
      throw new Error(`${PREFIX}EXPOSURE_${suffix} is '${word}'; it is one of private, signed or public`)
    }
    const publicUrl = nonEmpty(env, `${PREFIX}PUBLIC_URL_${suffix}`)?.replace(/\/$/, "")
    if (word === "public" && publicUrl === undefined) throw new Error(`bucket '${name}' is public and ${PREFIX}PUBLIC_URL_${suffix} is not set`)
    named.push(Object.freeze({ name, exposure: word, target: Object.freeze({ ...unnamed, bucket }), publicUrl }))
  }
  return Object.freeze({ unnamed: unnamed && unnamed.bucket !== "" ? unnamed : undefined, named: Object.freeze(named), timeoutMs })
}

const httpAgent = new HttpAgent({ keepAlive: true })
const httpsAgent = new HttpsAgent({ keepAlive: true })

class Timeout extends Error {}

interface Sent {
  readonly status: number
  readonly response: IncomingMessage
}

/**
 * One request over `node:http` or `node:https`, with `Content-Length` set when there is a body, which is
 * piped chunk by chunk. A body that ends before its length aborts the request, so the store keeps nothing.
 */
function send(url: string, method: string, headers: OutgoingHttpHeaders, timeoutMs: number, body?: { readonly chunks: AsyncIterable<Uint8Array>; readonly length: number }): Promise<Sent> {
  const target = new URL(url)
  const https = target.protocol === "https:"
  return new Promise<Sent>((resolvePromise, reject) => {
    let settled = false
    const fail = (e: unknown) => {
      if (settled) return
      settled = true
      reject(e)
    }
    const req = (https ? httpsRequest : httpRequest)(
      url,
      { method, headers: body ? { ...headers, "Content-Length": String(body.length) } : headers, agent: https ? httpsAgent : httpAgent },
      (response) => {
        req.setTimeout(0)
        if (settled) {
          response.resume()
          return
        }
        settled = true
        resolvePromise({ status: response.statusCode ?? 0, response })
      },
    )
    req.setTimeout(timeoutMs, () => req.destroy(new Timeout()))
    req.on("error", fail)
    if (!body) {
      req.end()
      return
    }
    void (async () => {
      let written = 0
      try {
        for await (const chunk of body.chunks) {
          written += chunk.length
          if (!req.write(chunk)) await new Promise<void>((r) => req.once("drain", () => r()))
          if (req.destroyed) return
        }
      } catch (e) {
        req.destroy()
        fail(e)
        return
      }
      if (written < body.length) {
        req.destroy()
        fail(StorageRefusals.shortBody(body.length, written))
        return
      }
      req.end()
    })()
  })
}

async function textOf(response: IncomingMessage, limit = 1 << 20): Promise<string> {
  const parts: Buffer[] = []
  let total = 0
  for await (const chunk of response as AsyncIterable<Buffer>) {
    if (total < limit) parts.push(chunk)
    total += chunk.length
  }
  return Buffer.concat(parts).toString("utf8")
}

const ENTITIES: Readonly<Record<string, string>> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" }

function unescapeXml(text: string): string {
  return text.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-z]+);/g, (whole, body: string) => {
    if (body.startsWith("#x")) return String.fromCodePoint(Number.parseInt(body.slice(2), 16))
    if (body.startsWith("#")) return String.fromCodePoint(Number.parseInt(body.slice(1), 10))
    return ENTITIES[body] ?? whole
  })
}

function element(xml: string, tag: string): string | undefined {
  const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml)
  return m ? unescapeXml(m[1]!) : undefined
}

/** A `ListObjectsV2` answer: its entries, and the token for the next page while it is truncated. */
export function parseListing(xml: string): { readonly entries: ObjectEntry[]; readonly next: string | undefined } {
  const entries: ObjectEntry[] = []
  for (const m of xml.matchAll(/<Contents>([\s\S]*?)<\/Contents>/g)) {
    const block = m[1]!
    const key = element(block, "Key")
    const size = element(block, "Size")
    const modified = element(block, "LastModified")
    if (key === undefined) throw new Error("a listing's entry has no Key")
    const length = size === undefined ? 0 : Number(size.trim())
    if (!Number.isFinite(length)) throw new Error(`a listing's Size is not a number: ${size}`)
    entries.push(Object.freeze({ name: key, length, lastKept: modified ? Instant.fromDate(new Date(modified.trim())) : Instant.ofEpochSecond(0) }))
  }
  const truncated = element(xml, "IsTruncated")?.trim() === "true"
  return { entries, next: truncated ? element(xml, "NextContinuationToken") : undefined }
}

function lastModifiedOf(response: IncomingMessage): Instant {
  const v = response.headers["last-modified"]
  const d = v ? new Date(v) : undefined
  return d && !Number.isNaN(d.getTime()) ? Instant.fromDate(d) : Instant.ofEpochSecond(0)
}

/** One bucket in an S3 store: path-style requests, a put streamed once with an unsigned payload, a listing paged as it is walked. */
export class S3Bucket implements Bucket {
  readonly name: string | undefined
  readonly exposure: BucketExposure
  readonly #target: S3Target
  readonly #publicUrl: string | undefined
  readonly #timeoutMs: number
  readonly #endpoint: string

  constructor(target: S3Target, name: string | undefined, exposure: BucketExposure, publicUrl: string | undefined, timeoutMs = 30_000) {
    this.#target = target
    this.name = name
    this.exposure = exposure
    this.#publicUrl = publicUrl
    this.#timeoutMs = timeoutMs
    this.#endpoint = target.endpoint.toString()
  }

  async put(objectName: string, body: ReadableStream<Uint8Array> | Uint8Array, options: PutOptions): Promise<void> {
    ObjectRules.checkName(objectName)
    const length = declaredLength(body, options)
    const headers = { "Content-Type": options.contentType, "x-amz-content-sha256": UNSIGNED_PAYLOAD }
    const { status, response } = await this.#send("PUT", this.#objectPath(objectName), [], headers, UNSIGNED_PAYLOAD, { chunks: bounded(body, length), length })
    const text = await textOf(response)
    if (status !== 200) throw this.#refused(status, text)
  }

  async get(objectName: string): Promise<StoredObject | undefined> {
    ObjectRules.checkName(objectName)
    const { status, response } = await this.#send("GET", this.#objectPath(objectName))
    if (status === 200) {
      const length = Number(response.headers["content-length"] ?? -1)
      const contentType = response.headers["content-type"] ?? "application/octet-stream"
      return storedObject(contentType, Number.isFinite(length) ? length : -1, lastModifiedOf(response), Readable.toWeb(response) as unknown as ReadableStream<Uint8Array>)
    }
    const text = await textOf(response, 4096)
    if (status === 404) return undefined
    throw this.#refused(status, text)
  }

  async stat(objectName: string): Promise<ObjectInfo | undefined> {
    ObjectRules.checkName(objectName)
    const { status, response } = await this.#send("HEAD", this.#objectPath(objectName))
    response.resume()
    if (status === 200) {
      const length = Number(response.headers["content-length"] ?? 0)
      return Object.freeze({
        name: objectName,
        contentType: response.headers["content-type"] ?? "application/octet-stream",
        length: Number.isFinite(length) ? length : 0,
        lastKept: lastModifiedOf(response),
      })
    }
    if (status === 404) return undefined
    throw this.#refused(status, "")
  }

  async delete(objectName: string): Promise<void> {
    ObjectRules.checkName(objectName)
    const { status, response } = await this.#send("DELETE", this.#objectPath(objectName))
    const text = await textOf(response)
    if (status !== 200 && status !== 204 && status !== 404) throw this.#refused(status, text)
  }

  list(prefix: string): AsyncIterable<ObjectEntry> {
    ObjectRules.checkPrefix(prefix)
    const bucket = this
    return (async function* () {
      let token: string | undefined
      do {
        const query: [string, string][] = [
          ["list-type", "2"],
          ["prefix", prefix],
        ]
        if (token !== undefined) query.push(["continuation-token", token])
        const { status, response } = await bucket.#send("GET", `/${bucket.#target.bucket}`, query)
        const text = await textOf(response, 64 << 20)
        if (status !== 200) throw bucket.#refused(status, text)
        let page
        try {
          page = parseListing(text)
        } catch (e) {
          throw StorageRefusals.unreadable(bucket.#endpoint, e instanceof Error ? e.message : String(e))
        }
        yield* page.entries
        token = page.next
      } while (token !== undefined)
    })()
  }

  signedUrl(objectName: string, access: SignedAccess, spanSeconds: number = ObjectRules.DEFAULT_SIGNED_URL_SECONDS): string {
    ObjectRules.checkName(objectName)
    let base: URL
    if (this.exposure === "supplied") base = this.#target.endpoint
    else if (this.name === undefined || this.exposure === "signed") {
      if (!this.#target.publicEndpoint) throw StorageRefusals.notExposed()
      base = this.#target.publicEndpoint
    } else throw StorageRefusals.notSigned(this.name, this.exposure)
    ObjectRules.checkSpan(spanSeconds)
    const method = access === "keep" ? "PUT" : "GET"
    return presign(new SignedRequest(method, base, this.#objectPath(objectName)), this.#credentials(), this.#target.region, "s3", Math.trunc(spanSeconds), new Date())
  }

  url(objectName: string): string {
    ObjectRules.checkName(objectName)
    if (this.exposure !== "public" || this.#publicUrl === undefined) throw StorageRefusals.notPublic(this.name, this.exposure)
    return this.#publicUrl.replace(/\/$/, "") + "/" + encode(objectName, false)
  }

  #credentials(): Credentials {
    if (!this.#target.credentials) throw StorageRefusals.noCredential()
    return this.#target.credentials
  }

  #objectPath(objectName: string): string {
    return `/${this.#target.bucket}/${objectName}`
  }

  async #send(
    method: string,
    path: string,
    query: readonly (readonly [string, string])[] = [],
    headers: Readonly<Record<string, string>> = { "x-amz-content-sha256": EMPTY_PAYLOAD },
    payload: string = EMPTY_PAYLOAD,
    body?: { readonly chunks: AsyncIterable<Uint8Array>; readonly length: number },
  ): Promise<Sent> {
    const request = new SignedRequest(method, this.#target.endpoint, path, query)
    const auth = sign(request, headers, payload, this.#credentials(), this.#target.region, "s3", new Date())
    try {
      return await send(request.url, method, { ...headers, ...auth }, this.#timeoutMs, body)
    } catch (e) {
      if (e instanceof CommandError) throw e
      const cause = e instanceof Timeout ? `no answer within ${Math.round(this.#timeoutMs / 1000)} seconds` : e instanceof Error ? e.message || e.name : String(e)
      throw StorageRefusals.unreachable(this.#endpoint, cause)
    }
  }

  #refused(status: number, body: string): CommandError {
    return StorageRefusals.refusedByStore(this.#endpoint, status, /<Code>([^<]*)<\/Code>/.exec(body)?.[1] ?? "")
  }
}

/** The S3 store's buckets, by name, from `s3Settings`. */
export class S3Buckets implements Buckets {
  readonly #unnamed: Bucket | undefined
  readonly #named: ReadonlyMap<string, Bucket>

  constructor(settings: S3Settings = s3Settings()) {
    const unnamedExposure: BucketExposure = settings.unnamed?.publicEndpoint ? "signed" : "private"
    this.#unnamed = settings.unnamed && new S3Bucket(settings.unnamed, undefined, unnamedExposure, undefined, settings.timeoutMs)
    this.#named = new Map(settings.named.map((n) => [n.name, new S3Bucket(n.target, n.name, n.exposure, n.publicUrl, settings.timeoutMs)] as const))
  }

  get default(): Bucket {
    if (!this.#unnamed) throw StorageRefusals.noBucket()
    return this.#unnamed
  }

  bucket(name: string): Bucket {
    const b = this.#named.get(name)
    if (!b) throw StorageRefusals.unknown(name, this.#named.keys())
    return b
  }

  get buckets(): Bucket[] {
    return [...this.#named.values()]
  }
}

// ── The store a process is given ─────────────────────────────────────────────────

/** What stands behind every bucket of a service. */
export type StorageBackend = "s3" | "directory" | "memory" | "unavailable"

export interface ChosenStorage {
  readonly backend: StorageBackend
  readonly buckets: Buckets
}

export interface StorageChoiceOptions {
  /** Where `service.json` is read from, for the buckets it declares: the working directory by default. */
  readonly serviceDirectory?: string
  /** The service's own address, for the directory store's URLs. */
  readonly addressBase?: () => string
}

/**
 * The one place a process's store is chosen, as the runtime chooses its own: `ANKKA_STORAGE_BACKEND` when
 * set (`s3`, `directory` or `memory`); otherwise S3 when any `ANKKA_S3_*` bucket is given, the directory
 * store (`ANKKA_STORAGE_DIR`, default `.ankka/storage`) on a developer's machine — anywhere Kubernetes did
 * not start the process — and no store at all on the platform. Throws, naming the variable, when the
 * settings cannot be read: a service that cannot reach its buckets does not start.
 */
export function chooseStorage(env: Env = process.env, options: StorageChoiceOptions = {}): ChosenStorage {
  const word = (env.ANKKA_STORAGE_BACKEND ?? "").trim()
  const settings = s3Settings(env)
  const anyS3 = settings.unnamed !== undefined || settings.named.length > 0
  const runningLocally = env.KUBERNETES_SERVICE_HOST === undefined
  const local = () => declaredBuckets(options.serviceDirectory ?? process.cwd())
  let backend: StorageBackend
  switch (word) {
    case "":
      backend = anyS3 ? "s3" : runningLocally ? "directory" : "unavailable"
      break
    case "s3":
      if (!anyS3) throw new Error(`ANKKA_STORAGE_BACKEND is s3 and ${PREFIX}ENDPOINT is not set: the s3 store needs an endpoint`)
      backend = "s3"
      break
    case "directory":
    case "memory":
      backend = word
      break
    default:
      throw new Error(`ANKKA_STORAGE_BACKEND is '${word}'; it is one of s3, directory or memory`)
  }
  switch (backend) {
    case "s3":
      return { backend, buckets: new S3Buckets(settings) }
    case "directory": {
      const dir = env.ANKKA_STORAGE_DIR || ".ankka/storage"
      const root = isAbsolute(dir) ? dir : resolve(process.cwd(), dir)
      const declared = local()
      return { backend, buckets: new DirectoryBuckets(root, { ...(declared ? { declared } : {}), addressBase: options.addressBase ?? (() => localAddress(env)) }) }
    }
    case "memory": {
      const declared = local()
      return { backend, buckets: new InMemoryBuckets(declared ? { unnamed: declared.unnamed, buckets: declared.buckets } : {}) }
    }
    default:
      return { backend, buckets: noStorage }
  }
}

let processBuckets: Buckets = noStorage

/** The store the server chose for this process, which every component's `storage` answers unless a test gave it one. */
export function processStorage(): Buckets {
  return processBuckets
}

/** @internal The server's: the store this process was given. */
export function _setProcessStorage(buckets: Buckets): void {
  processBuckets = buckets
}
