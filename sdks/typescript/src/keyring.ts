// The process's keyring (feature 042): the subject keys its personal fields are sealed and opened
// under, fetched from the sidecar (`GetSubjectKey`, protocol 1.23) and cached under the runtime's
// rules (`KeyringSettings`), dropped when the sidecar says a subject was erased (`SubjectNotices`).
//
// A codec is synchronous and a fetch is not, so a key is fetched *before* the codec runs: the subjects
// named in a payload's JSON before it is decoded, the `Personal` values in a value before it is
// encoded. The codec then finds every key here; one it does not find is `Unavailable`.
//
// The process learns its own project from the sidecar, never from configuration it could write: a
// request for the own project leaves `project` empty, and the answer names the project it is for.
// The first such answer is the project every envelope the process seals names.

import { ENVELOPE_VERSION, Erased, PersonalError, Present, subjectProblem } from "./personal.ts"

/** What the keyring answered for one data subject. */
export type KeyAnswer = { readonly kind: "key"; readonly key: Uint8Array } | { readonly kind: "erased" }

/** A key answer and the project it is for: the service's own when the request left `project` empty. */
export interface KeyReply {
  readonly project: string
  readonly answer: KeyAnswer
}

/** How a key is fetched: `project` empty is the service's own, and `create` makes one for a write. */
export type KeyFetch = (project: string, subject: string, create: boolean) => Promise<KeyReply>

/**
 * How a process holds the keys it fetched: the runtime's `ankka.keyring` settings, by the same names
 * and defaults, in milliseconds. `keyringSettings` refuses one that breaks a rule, naming it.
 */
export interface KeyringSettings {
  /** `ankka.keyring.cache.max-keys`: how many keys are held at once; the least recently used goes first. */
  readonly maxKeys: number
  /** `ankka.keyring.cache.ttl`: how long a key of the own project is held before it is asked for again. */
  readonly ttlMillis: number
  /** `ankka.keyring.cache.foreign-ttl`: how long a key of another project, or a read's erased answer, is held. */
  readonly foreignTtlMillis: number
  /** `ankka.keyring.cache.outage-bound`: how long after its fetch a held own key serves while the keyring cannot be reached. */
  readonly outageBoundMillis: number
  /** `ankka.keyring.notice-bound`: the bound on an erasure reaching a read, which the outage bound may not exceed. */
  readonly noticeBoundMillis: number
}

export const DEFAULT_KEYRING_SETTINGS: KeyringSettings = Object.freeze({
  maxKeys: 10_000,
  ttlMillis: 5 * 60_000,
  foreignTtlMillis: 30_000,
  outageBoundMillis: 60_000,
  noticeBoundMillis: 60_000,
})

/** The defaults with `given` over them; throws a `RangeError` naming the setting a value breaks. */
export function keyringSettings(given: Partial<KeyringSettings> = {}): KeyringSettings {
  const s = { ...DEFAULT_KEYRING_SETTINGS, ...given }
  if (!Number.isInteger(s.maxKeys) || s.maxKeys < 1) throw new RangeError(`ankka.keyring.cache.max-keys must be at least 1, was ${s.maxKeys}`)
  const named: [keyof KeyringSettings, string][] = [
    ["ttlMillis", "cache.ttl"],
    ["foreignTtlMillis", "cache.foreign-ttl"],
    ["outageBoundMillis", "cache.outage-bound"],
    ["noticeBoundMillis", "notice-bound"],
  ]
  for (const [field, path] of named) {
    if (!(s[field] > 0)) throw new RangeError(`ankka.keyring.${path} must be positive, was ${s[field]}ms`)
  }
  if (s.outageBoundMillis > s.noticeBoundMillis) {
    throw new RangeError(
      `ankka.keyring.cache.outage-bound (${s.outageBoundMillis}ms) must not exceed ankka.keyring.notice-bound (${s.noticeBoundMillis}ms)`,
    )
  }
  return Object.freeze(s)
}

type Entry =
  | { readonly kind: "key"; readonly key: Uint8Array }
  // `forWrite`: asked with `create`, so the subject is destroyed. A read's answer may only mean that no
  // key was ever made, or that a grant was refused, which is asked again about.
  | { readonly kind: "erased"; readonly forWrite: boolean }

interface Held {
  readonly entry: Entry
  readonly at: number
  readonly foreign: boolean
}

/** One fetch in flight: `dropped` once a notice dropped its subject (or the whole cache) while it was out. */
interface Flight {
  readonly subject: string
  dropped: boolean
}

/** How many times a fetch overtaken by a notice is made again before it is unavailable. */
const ATTEMPTS = 3

export interface KeyringOptions {
  readonly fetch: KeyFetch
  readonly settings?: Partial<KeyringSettings>
  /** Milliseconds now; a test moves it. */
  readonly clock?: () => number
}

/**
 * The process's key cache, under the runtime's rules: at most `maxKeys`, the least recently used going
 * first; a key of the own project fresh for `ttl`, and still served while the keyring cannot be reached
 * until `outageBound` after it was fetched; a key of another project, which no notice of that project
 * reaches, and a read's erased answer, for `foreignTtl` and no longer. A write's erased answer is a
 * destroyed key, which never changes. An answer whose fetch was in flight when a notice dropped its
 * subject may be the destroyed key: it is never cached, and the key is asked for again.
 */
export class Keyring {
  #project: string | undefined
  readonly #fetch: KeyFetch
  readonly #settings: KeyringSettings
  readonly #clock: () => number
  // Insertion order is recency: an entry used is deleted and set again.
  readonly #cache = new Map<string, Held>()
  // Why the last fetch for a subject failed, so the codec can say; never a key.
  readonly #failures = new Map<string, string>()
  readonly #inFlight = new Map<string, Promise<void>>()
  readonly #flights = new Set<Flight>()
  // Why an answer for the own project failed before any answer named the project.
  readonly #ownFailures = new Map<string, string>()

  constructor(options: KeyringOptions) {
    this.#fetch = options.fetch
    this.#settings = keyringSettings(options.settings)
    this.#clock = options.clock ?? Date.now
  }

  /** The service's own project, as the first key answer for it named it; `undefined` until then. */
  get project(): string | undefined {
    return this.#project
  }

  get settings(): KeyringSettings {
    return this.#settings
  }

  /** How many answers are held. */
  get size(): number {
    return this.#cache.size
  }

  // ── the cache's rules ──────────────────────────────────────────────────────

  #age(h: Held): number {
    return this.#clock() - h.at
  }

  #fresh(h: Held): boolean {
    if (h.entry.kind === "erased") return (h.entry.forWrite && !h.foreign) || this.#age(h) < this.#settings.foreignTtlMillis
    return this.#age(h) < (h.foreign ? this.#settings.foreignTtlMillis : this.#settings.ttlMillis)
  }

  #servesInOutage(h: Held): boolean {
    return h.entry.kind === "key" && !h.foreign && this.#age(h) < this.#settings.outageBoundMillis
  }

  /** The entry for `key` while it may be used, now the most recently used; an expired one is forgotten. */
  #held(key: string): Held | undefined {
    const h = this.#cache.get(key)
    if (h === undefined) return undefined
    this.#cache.delete(key)
    if (!this.#fresh(h) && !this.#servesInOutage(h)) return undefined
    this.#cache.set(key, h)
    return h
  }

  #put(project: string, subject: string, entry: Entry): void {
    const key = cacheKey(project, subject)
    this.#cache.delete(key)
    this.#failures.delete(key)
    this.#cache.set(key, { entry, at: this.#clock(), foreign: this.#project === undefined || project !== this.#project })
    while (this.#cache.size > this.#settings.maxKeys) {
      const eldest = this.#cache.keys().next().value
      if (eldest === undefined) break
      this.#cache.delete(eldest)
    }
  }

  // ── what the server awaits before the codec runs ───────────────────────────

  /** Fetches, unless held fresh, the key of every subject the envelopes in `json` name. Never throws. */
  async prefetchJson(json: Uint8Array | string): Promise<void> {
    const text = typeof json === "string" ? json : Buffer.from(json.buffer, json.byteOffset, json.byteLength).toString("utf8")
    if (!text.includes('"$personal"')) return
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch {
      return
    }
    const wanted = new Map<string, [string, string]>()
    walk(parsed, (node) => {
      if (!isObject(node) || !("$personal" in node)) return false
      const { project, subject, c } = node as Record<string, unknown>
      if (node["$personal"] === ENVELOPE_VERSION && typeof c === "string" && typeof project === "string" && subjectProblem(subject) === undefined) {
        wanted.set(cacheKey(project, subject as string), [project, subject as string])
      }
      return true
    })
    await Promise.all([...wanted.values()].map(([project, subject]) => this.#ensure(project, subject, false)))
  }

  /**
   * Fetches, unless held fresh, the key of every present personal field in `value`, for a write. An
   * erased field needs no key, but its envelope names the own project: until an answer has named it, one
   * is asked for, without `create`. Never throws.
   */
  async prefetchValue(value: unknown): Promise<void> {
    const subjects = new Set<string>()
    let erased: string | undefined
    walk(value, (node) => {
      if (node instanceof Present) {
        subjects.add(node.subject)
        return true
      }
      if (node instanceof Erased) {
        erased ??= node.subject
        return true
      }
      return false
    })
    const own = this.#project ?? ""
    if (subjects.size === 0 && erased !== undefined && this.#project === undefined) return this.#ensure("", erased, false)
    await Promise.all([...subjects].map((s) => this.#ensure(own, s, true)))
  }

  // ── what the codec reads, synchronously ────────────────────────────────────

  /** The key a write seals `subject`'s field with. Throws `Erased` or `Unavailable`; never fetches. */
  keyForWrite(subject: string): { project: string; key: Uint8Array } {
    const project = this.#project
    if (project === undefined) {
      const why = this.#ownFailures.get(subject)
      throw PersonalError.unavailable(why ?? "this process does not know its project yet: no key answer from the sidecar has named it")
    }
    const key = cacheKey(project, subject)
    const entry = this.#held(key)?.entry
    if (entry?.kind === "key") return { project, key: entry.key }
    if (entry?.kind === "erased" && entry.forWrite) throw PersonalError.erased(subject)
    throw PersonalError.unavailable(this.#failures.get(key) ?? `no key for '${subject}' was fetched before the write`)
  }

  /** The key a read opens `subject`'s field of `project` with, or `erased`. Throws `Unavailable`; never fetches. */
  keyForRead(project: string, subject: string): KeyAnswer {
    const key = cacheKey(project, subject)
    const entry = this.#held(key)?.entry
    if (entry !== undefined) return entry.kind === "key" ? entry : { kind: "erased" }
    throw PersonalError.unavailable(this.#failures.get(key) ?? `no key for '${subject}' of '${project}' was fetched before the read`)
  }

  // ── notices ────────────────────────────────────────────────────────────────

  /**
   * Forgets every key: what the process does when its notice stream ends or opens again, since the
   * sidecar counts a closed stream as acked and hands a notice sent while none was held never again.
   */
  dropAll(): void {
    this.#cache.clear()
    for (const f of this.#flights) f.dropped = true
  }

  /** Forgets `subject`'s key: what a notice from the sidecar does. */
  drop(project: string, subject: string): void {
    this.#cache.delete(cacheKey(project, subject))
    for (const f of this.#flights) if (f.subject === subject) f.dropped = true
  }

  /** Whether a key, or an erased answer, is held for `subject` and may be used. */
  holds(project: string, subject: string): boolean {
    const h = this.#cache.get(cacheKey(project, subject))
    return h !== undefined && (this.#fresh(h) || this.#servesInOutage(h))
  }

  /** `project` empty is the service's own, before its name is known. */
  async #ensure(project: string, subject: string, create: boolean): Promise<void> {
    const named = project === "" ? this.#project : project
    if (named !== undefined) {
      const held = this.#cache.get(cacheKey(named, subject))
      if (held !== undefined && this.#fresh(held)) {
        if (held.entry.kind === "key") return
        if (held.entry.forWrite || !create) return
      }
    }
    const asked = named !== undefined && named === this.#project ? "" : project
    const flightKey = `${asked}\u0000${subject}\u0000${create}`
    const pending = this.#inFlight.get(flightKey)
    if (pending) return pending
    const asking = (async () => {
      let answered = named
      try {
        let reply: KeyReply | undefined
        for (let attempt = 0; attempt < ATTEMPTS && reply === undefined; attempt++) {
          const flight: Flight = { subject, dropped: false }
          this.#flights.add(flight)
          try {
            const got = await this.#fetch(asked, subject, create)
            if (!flight.dropped) reply = got
          } finally {
            this.#flights.delete(flight)
          }
        }
        if (reply === undefined) throw new Error(`the key of '${subject}' was dropped by a notice each time it was fetched`)
        // Only an answer to a request for the own project says which project is the own one.
        if (asked === "") {
          if (reply.project === "") throw new Error("the sidecar answered a subject key without naming the project it is for")
          if (this.#project === undefined) this.#project = reply.project
          else if (reply.project !== this.#project) {
            throw new Error(`the sidecar answered a key for project '${reply.project}', not this service's '${this.#project}'`)
          }
          this.#ownFailures.delete(subject)
        }
        answered = reply.project !== "" ? reply.project : named
        if (answered === undefined) throw new Error("the sidecar answered a subject key without naming the project it is for")
        const { answer } = reply
        if (answer.kind === "key") {
          if (answer.key.length !== 32) throw new Error(`the keyring answered a key of ${answer.key.length} bytes, not 32`)
          this.#put(answered, subject, { kind: "key", key: answer.key })
        } else this.#put(answered, subject, { kind: "erased", forWrite: create })
      } catch (e) {
        // Kept so the codec can say why; asked again by the next prefetch. A held own key the outage
        // bound still allows keeps serving; anything else held is forgotten.
        const why = e instanceof Error ? e.message : String(e)
        if (answered !== undefined) {
          const key = cacheKey(answered, subject)
          const held = this.#cache.get(key)
          if (held === undefined || !this.#servesInOutage(held)) {
            this.#cache.delete(key)
            this.#failures.set(key, why)
          }
        } else this.#ownFailures.set(subject, why)
      } finally {
        this.#inFlight.delete(flightKey)
      }
    })()
    this.#inFlight.set(flightKey, asking)
    return asking
  }
}

function cacheKey(project: string, subject: string): string {
  return `${project}\u0000${subject}`
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v)
}

/** Visits every node of `value`; `visit` answering true stops the descent below that node. */
function walk(value: unknown, visit: (node: unknown) => boolean): void {
  const seen = new Set<object>()
  const go = (node: unknown): void => {
    if (typeof node !== "object" || node === null) return
    if (seen.has(node)) return
    seen.add(node)
    if (visit(node)) return
    if (node instanceof Uint8Array) return
    if (Array.isArray(node)) node.forEach(go)
    else if (node instanceof Map) node.forEach((v) => go(v))
    else if (node instanceof Set) node.forEach((v) => go(v))
    else for (const v of Object.values(node)) go(v)
  }
  go(value)
}

// ── the process's keyring ────────────────────────────────────────────────────

let installed: Keyring | undefined

/** @internal Installs the keyring every codec in this process reads; `undefined` removes it. */
export function installKeyring(keyring: Keyring | undefined): void {
  installed = keyring
}

/** The keyring installed in this process, if any. */
export function currentKeyring(): Keyring | undefined {
  return installed
}

/** The installed keyring; `Unavailable` when there is none. */
export function requireKeyring(): Keyring {
  if (!installed) throw PersonalError.unavailable("no keyring is installed in this process (no sidecar speaking protocol 1.23 has discovered it)")
  return installed
}

/** Prefetches for decoding `json`, when a keyring is installed. Never throws. */
export async function prefetchForDecode(json: Uint8Array | string | undefined): Promise<void> {
  if (installed && json !== undefined && json.length > 0) await installed.prefetchJson(json)
}

/** Prefetches for encoding `value`, when a keyring is installed. Never throws. */
export async function prefetchForEncode(value: unknown): Promise<void> {
  if (installed) await installed.prefetchValue(value)
}

// ── notices ──────────────────────────────────────────────────────────────────

/** A notice as the sidecar relays it. */
export interface SubjectNotice {
  readonly seq: bigint
  readonly project: string
  readonly subject: string
  readonly rerun: boolean
}

/**
 * Holds `open()`'s notice stream for as long as `signal` is not aborted: each notice drops the subject's
 * key and is then acked. A stream that ends or fails is opened again after `retryMillis`.
 */
export async function followNotices(
  keyring: Keyring,
  open: (signal: AbortSignal) => AsyncIterable<SubjectNotice>,
  ack: (seq: bigint) => Promise<void>,
  signal: AbortSignal,
  log: (message: string) => void = () => {},
  retryMillis = 1000,
): Promise<void> {
  while (!signal.aborted) {
    try {
      const stream = open(signal)
      keyring.dropAll()
      for await (const notice of stream) {
        keyring.drop(notice.project, notice.subject)
        await ack(notice.seq)
      }
    } catch (e) {
      if (signal.aborted) return
      log(`ankka: the subject notice stream failed: ${e instanceof Error ? e.message : String(e)}; reopening`)
    } finally {
      keyring.dropAll()
    }
    if (signal.aborted) return
    await new Promise<void>((resolve) => {
      const t = setTimeout(resolve, retryMillis)
      signal.addEventListener("abort", () => (clearTimeout(t), resolve()), { once: true })
    })
  }
}
