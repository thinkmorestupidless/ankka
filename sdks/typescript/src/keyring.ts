// The keys of a process behind the sidecar (protocol 1.15): fetched from it on a miss with one
// blocking call, held until the expiry it states, and dropped when it says the subject is erased.

import { create, fromBinary, toBinary } from "@bufbuild/protobuf"
import { createClient } from "@connectrpc/connect"
import { createGrpcTransport } from "@connectrpc/connect-node"
import { Client, KeyAnswerSchema, KeyFetchSchema, LookupTokenReplySchema, LookupTokenRequestSchema } from "./_proto/ankka/protocol/v1/client_pb.ts"
import { EmptySchema } from "./_proto/ankka/protocol/v1/payload_pb.ts"
import { BlockingCalls, PersonalFieldError, type KeyAnswer, type KeySource } from "./personal.ts"

const FETCH = "/ankka.protocol.v1.Client/FetchSubjectKey"
const LOOKUP = "/ankka.protocol.v1.Client/LookupToken"

export class SidecarKeys implements KeySource {
  readonly #address: () => string
  readonly #calls: BlockingCalls
  readonly #keys = new Map<string, { answer: KeyAnswer; until: number }>()
  readonly #tokens = new Map<string, string>()
  readonly #destroyed = new Set<string>()
  readonly #max: number
  #own: string | undefined
  #listening = false
  #closed = false
  readonly #stop = new AbortController()

  constructor(address: () => string, max = 10_000) {
    this.#address = address
    this.#calls = new BlockingCalls(address)
    this.#max = max
  }

  get ownProject(): string | undefined {
    return this.#own
  }

  isDestroyed(project: string | undefined, subject: string): boolean {
    return this.#destroyed.has(`${project ?? this.#own ?? ""}\u0000${subject}`)
  }

  destroyed(project: string, subject: string): void {
    const id = `${project}\u0000${subject}`
    this.#destroyed.add(id)
    this.#keys.delete(id)
  }

  key(project: string | undefined, subject: string, create_: boolean): KeyAnswer {
    this.#listen()
    const wanted = project ?? this.#own
    if (wanted !== undefined) {
      const id = `${wanted}\u0000${subject}`
      if (this.#destroyed.has(id)) return { kind: "destroyed", project: wanted }
      const held = this.#keys.get(id)
      if (held && held.until > Date.now()) return held.answer
    }
    let bytes: Uint8Array
    try {
      bytes = this.#calls.call(FETCH, toBinary(KeyFetchSchema, create(KeyFetchSchema, { subject, project: project ?? "", create: create_ })))
    } catch (e) {
      const message = (e as Error).message
      const reason = message.includes("grpc-status 12") ? "the runtime is older than protocol 1.15 and holds no keys" : `the runtime could not be reached: ${message}`
      return { kind: "unavailable", project: wanted ?? "", reason }
    }
    const answer = fromBinary(KeyAnswerSchema, bytes)
    switch (answer.out.case) {
      case "key": {
        const got: KeyAnswer = { kind: "key", project: answer.out.value.project, key: answer.out.value.key }
        if (project === undefined && this.#own === undefined) this.#own = got.project
        this.#keys.set(`${got.project}\u0000${subject}`, { answer: got, until: Date.now() + Number(answer.out.value.expiresMillis) })
        while (this.#keys.size > this.#max) this.#keys.delete(this.#keys.keys().next().value as string)
        return got
      }
      case "destroyed":
        if (project === undefined && this.#own === undefined) this.#own = answer.out.value.project
        this.destroyed(answer.out.value.project, subject)
        return { kind: "destroyed", project: answer.out.value.project }
      case "refused": {
        const r = answer.out.value
        if (project === undefined && this.#own === undefined && r.project) this.#own = r.project
        return r.unavailable ? { kind: "unavailable", project: r.project, reason: r.reason } : { kind: "refused", project: r.project, reason: r.reason }
      }
      default:
        return { kind: "unavailable", project: wanted ?? "", reason: "the runtime answered nothing" }
    }
  }

  lookupToken(plaintext: Uint8Array): string {
    const id = Buffer.from(plaintext).toString("base64")
    const held = this.#tokens.get(id)
    if (held !== undefined) return held
    let reply
    try {
      reply = fromBinary(LookupTokenReplySchema, this.#calls.call(LOOKUP, toBinary(LookupTokenRequestSchema, create(LookupTokenRequestSchema, { plaintext }))))
    } catch (e) {
      throw new PersonalFieldError(`no lookup token: ${(e as Error).message}`, "UNAVAILABLE")
    }
    if (reply.result.case !== "token") throw new PersonalFieldError(reply.result.value?.message ?? "no lookup token", "UNAVAILABLE")
    this.#tokens.set(id, reply.result.value)
    while (this.#tokens.size > this.#max) this.#tokens.delete(this.#tokens.keys().next().value as string)
    return reply.result.value
  }

  /** Hears every subject the runtime is told is erased, from the first fetch on, reconnecting when the stream ends. */
  #listen(): void {
    if (this.#listening) return
    this.#listening = true
    const run = async (): Promise<void> => {
      while (!this.#closed) {
        try {
          const stub = createClient(Client, createGrpcTransport({ baseUrl: `http://${this.#address()}` }))
          for await (const d of stub.subjectKeyEvents(create(EmptySchema, {}), { signal: this.#stop.signal })) this.destroyed(d.project, d.subject)
        } catch {
          // A closed stream is reopened.
        }
        // Whatever was cached while nobody listened may have been erased since.
        this.#keys.clear()
        await new Promise((resolve) => setTimeout(resolve, 1000).unref())
      }
    }
    void run()
  }

  async close(): Promise<void> {
    this.#closed = true
    this.#stop.abort()
    await this.#calls.close()
  }
}
