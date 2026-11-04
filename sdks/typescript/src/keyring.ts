// The process's keyring (feature 042): the subject keys its personal fields are sealed and opened
// under, fetched from the sidecar (`GetSubjectKey`, protocol 1.23) and cached for the life of the
// process, dropped when the sidecar says a subject was erased (`SubjectNotices`).
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

type Entry =
  | { readonly kind: "key"; readonly key: Uint8Array }
  // `forWrite`: asked with `create`, so the subject is destroyed. A read's answer may only mean that no
  // key was ever made, which a write must ask again about.
  | { readonly kind: "erased"; readonly forWrite: boolean }
  | { readonly kind: "failed"; readonly why: string }

export interface KeyringOptions {
  readonly fetch: KeyFetch
}

export class Keyring {
  #project: string | undefined
  readonly #fetch: KeyFetch
  readonly #cache = new Map<string, Entry>()
  readonly #inFlight = new Map<string, Promise<void>>()
  // Why an answer for the own project failed before any answer named the project.
  readonly #ownFailures = new Map<string, string>()

  constructor(options: KeyringOptions) {
    this.#fetch = options.fetch
  }

  /** The service's own project, as the first key answer for it named it; `undefined` until then. */
  get project(): string | undefined {
    return this.#project
  }

  /** Fetches, unless cached, the key of every subject the envelopes in `json` name. Never throws. */
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
   * Fetches, unless cached, the key of every present personal field in `value`, for a write. An erased
   * field needs no key, but its envelope names the own project: until an answer has named it, one is
   * asked for, without `create`. Never throws.
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

  /** The key a write seals `subject`'s field with. Throws `Erased` or `Unavailable`; never fetches. */
  keyForWrite(subject: string): { project: string; key: Uint8Array } {
    const project = this.#project
    if (project === undefined) {
      const why = this.#ownFailures.get(subject)
      throw PersonalError.unavailable(why ?? "this process does not know its project yet: no key answer from the sidecar has named it")
    }
    const entry = this.#cache.get(cacheKey(project, subject))
    if (entry?.kind === "key") return { project, key: entry.key }
    if (entry?.kind === "erased" && entry.forWrite) throw PersonalError.erased(subject)
    throw PersonalError.unavailable(entry?.kind === "failed" ? entry.why : `no key for '${subject}' was fetched before the write`)
  }

  /** The key a read opens `subject`'s field of `project` with, or `erased`. Throws `Unavailable`; never fetches. */
  keyForRead(project: string, subject: string): KeyAnswer {
    const entry = this.#cache.get(cacheKey(project, subject))
    if (entry?.kind === "key" || entry?.kind === "erased") return entry.kind === "key" ? entry : { kind: "erased" }
    throw PersonalError.unavailable(entry?.kind === "failed" ? entry.why : `no key for '${subject}' of '${project}' was fetched before the read`)
  }

  /**
   * Forgets every key: what the process does when its notice stream ends or opens again, since the
   * sidecar counts a closed stream as acked and hands a notice sent while none was held never again.
   */
  dropAll(): void {
    this.#cache.clear()
  }

  /** Forgets `subject`'s key: what a notice from the sidecar does. */
  drop(project: string, subject: string): void {
    this.#cache.delete(cacheKey(project, subject))
  }

  /** Whether a key, or an erased answer, is held for `subject`. */
  holds(project: string, subject: string): boolean {
    const e = this.#cache.get(cacheKey(project, subject))
    return e !== undefined && e.kind !== "failed"
  }

  /** `project` empty is the service's own, before its name is known. */
  async #ensure(project: string, subject: string, create: boolean): Promise<void> {
    const named = project === "" ? this.#project : project
    if (named !== undefined) {
      const held = this.#cache.get(cacheKey(named, subject))
      if (held?.kind === "key") return
      if (held?.kind === "erased" && (held.forWrite || !create)) return
    }
    const asked = named !== undefined && named === this.#project ? "" : project
    const flight = `${asked}\u0000${subject}\u0000${create}`
    const pending = this.#inFlight.get(flight)
    if (pending) return pending
    const asking = (async () => {
      let answered = named
      try {
        const reply = await this.#fetch(asked, subject, create)
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
          this.#cache.set(cacheKey(answered, subject), { kind: "key", key: answer.key })
        } else this.#cache.set(cacheKey(answered, subject), { kind: "erased", forWrite: create })
      } catch (e) {
        // Kept so the codec can say why; asked again by the next prefetch.
        const why = e instanceof Error ? e.message : String(e)
        if (answered !== undefined) this.#cache.set(cacheKey(answered, subject), { kind: "failed", why })
        else this.#ownFailures.set(subject, why)
      } finally {
        this.#inFlight.delete(flight)
      }
    })()
    this.#inFlight.set(flight, asking)
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
