// A service's client keys (protocol 1.20): credentials it issues for its MCP endpoint, each for one
// subject, with an expiry, an optional scope of tools and attributes a tool reads as the principal's
// claims. The runtime keeps them; this process sees a key once, in the answer to `issue`, and never
// again. Offered to endpoints, MCP endpoints, timed actions and agents, as the secret store is.
//
//   const issued = await this.clientKeys.issue({ subject: "acme", expiry: Expiry.after({ days: 365 }), scope: ["list_jobs"] })
//   issued.clientKey   // shown here, and nowhere again

import { Code, ConnectError } from "@connectrpc/connect"
import { channelOf, type ComponentClient } from "./client.ts"
import { metadataToProto } from "./context.ts"
import { CommandError, ErrorCode } from "./effects/common.ts"
import { errorCodeFromProto } from "./kinds.ts"
import { PROTOCOL_VERSION } from "./spec.ts"
import { Duration, Instant } from "./time.ts"
import type { ErrorCode as ProtoErrorCode } from "./_proto/ankka/protocol/v1/payload_pb.ts"

/** The first protocol whose runtime keeps client keys; an older one answers `UNIMPLEMENTED`. */
export const CLIENT_KEYS_SINCE = "1.20"

/** When a client key stops being admitted. There is no default: the issuer says, every time. */
export type Expiry =
  | { readonly kind: "after"; readonly millis: number }
  | { readonly kind: "at"; readonly epochMillis: number }
  | { readonly kind: "never" }

export const Expiry = Object.freeze({
  /** After a while from now: `Expiry.after({ days: 365 })`, or a `Duration`. */
  after(by: Duration | { readonly days?: number; readonly hours?: number; readonly minutes?: number; readonly seconds?: number; readonly millis?: number }): Expiry {
    const millis =
      by instanceof Duration
        ? by.toMillis()
        : (by.days ?? 0) * 86_400_000 + (by.hours ?? 0) * 3_600_000 + (by.minutes ?? 0) * 60_000 + (by.seconds ?? 0) * 1_000 + (by.millis ?? 0)
    if (!Number.isFinite(millis) || millis <= 0) throw new TypeError(`a client key's expiry is a positive while, not ${millis}ms`)
    return Object.freeze({ kind: "after", millis })
  },
  /** At an instant. */
  at(when: Instant | Date): Expiry {
    const epochMillis = when instanceof Instant ? when.toEpochMilli() : when.getTime()
    return Object.freeze({ kind: "at", epochMillis })
  },
  /** Never: admitted until it is revoked. */
  never: Object.freeze({ kind: "never" }) as Expiry,
})

/** What to issue: the subject a tool is told, how long, and what for. */
export interface IssueClientKey {
  readonly subject: string
  readonly expiry: Expiry
  /** The MCP endpoint's path; needed only when the service has several. */
  readonly endpoint?: string
  /** The tools the key may call; every tool when empty. */
  readonly scope?: Iterable<string>
  /** What a tool reads as the principal's claims: at most 16, each name and value at most 256 characters. */
  readonly attributes?: Readonly<Record<string, string>>
  readonly label?: string
}

/** An issued key: its id, which lists and revokes it, and the key, shown here once. */
export interface IssuedClientKey {
  readonly id: string
  readonly clientKey: string
}

/** One client key as a listing shows it: never the key, nor anything it could be derived from. */
export interface ClientKeySummary {
  readonly id: string
  readonly subject: string
  readonly attributes: Readonly<Record<string, string>>
  readonly label: string | null
  readonly endpoint: string
  readonly scope: readonly string[]
  readonly issuedAt: Instant
  readonly expiresAt: Instant | null
  readonly lastUsed: Instant | null
  readonly revokedAt: Instant | null
}

function refusalOf(error: { message: string; code: ProtoErrorCode; details?: Record<string, string> }): CommandError {
  return new CommandError({ message: error.message, code: errorCodeFromProto(error.code), details: { ...(error.details ?? {}) } })
}

function tooOld(failure: unknown): never {
  if (failure instanceof ConnectError && failure.code === Code.Unimplemented) {
    throw new CommandError({
      message: `the runtime beside this process is too old for client keys, which needs protocol ${CLIENT_KEYS_SINCE}; this process speaks ${PROTOCOL_VERSION}: ${failure.rawMessage}`,
      code: ErrorCode.Internal,
    })
  }
  throw failure
}

function instant(millis: bigint | undefined): Instant | null {
  return millis === undefined ? null : Instant.ofEpochMilli(Number(millis))
}

/**
 * The service's client keys, kept by the runtime. A refusal is a `CommandError` with the runtime's
 * code: `BAD_REQUEST` for a request that breaks a rule, `UNAVAILABLE` when the service's MCP
 * endpoints admit no client key (there is then nothing to keep them), `NOT_FOUND` for an unknown id.
 */
export class ClientKeys {
  readonly #client: ComponentClient | undefined

  /** The keys reached through `client`'s sidecar; with none, every call throws. */
  constructor(client?: ComponentClient) {
    this.#client = client
  }

  #channel() {
    const channel = this.#client ? channelOf(this.#client) : undefined
    if (!channel) throw new Error("these client keys are not connected to a runtime; a unit test passes an InMemoryClientKeys")
    return channel
  }

  /** Issues a key; the answer is the one place the key is ever shown. */
  async issue(request: IssueClientKey): Promise<IssuedClientKey> {
    const { stub, metadata } = this.#channel()
    const expiry = request.expiry
    const reply = await stub()
      .issueClientKey({
        subject: request.subject,
        expiry:
          expiry.kind === "after"
            ? { case: "afterMillis", value: BigInt(expiry.millis) }
            : expiry.kind === "at"
              ? { case: "atEpochMillis", value: BigInt(expiry.epochMillis) }
              : { case: "never", value: true },
        ...(request.endpoint !== undefined ? { endpoint: request.endpoint } : {}),
        scope: [...(request.scope ?? [])],
        attributes: { ...(request.attributes ?? {}) },
        ...(request.label !== undefined ? { label: request.label } : {}),
        metadata: metadataToProto(metadata),
      })
      .catch(tooOld)
    switch (reply.reply.case) {
      case "issued":
        return Object.freeze({ id: reply.reply.value.id, clientKey: reply.reply.value.clientKey })
      case "error":
        throw refusalOf(reply.reply.value)
      default:
        throw new CommandError({ message: "the runtime answered an issue with nothing", code: ErrorCode.Internal })
    }
  }

  /** Every client key of the service, revoked ones included; never a key itself. */
  async list(): Promise<ClientKeySummary[]> {
    const { stub, metadata } = this.#channel()
    const reply = await stub().listClientKeys({ metadata: metadataToProto(metadata) }).catch(tooOld)
    if (reply.error) throw refusalOf(reply.error)
    return reply.keys.map((k) =>
      Object.freeze({
        id: k.id,
        subject: k.subject,
        attributes: Object.freeze({ ...k.attributes }),
        label: k.label ?? null,
        endpoint: k.endpoint,
        scope: Object.freeze([...k.scope]),
        issuedAt: Instant.ofEpochMilli(Number(k.issuedAtMillis)),
        expiresAt: instant(k.expiresAtMillis),
        lastUsed: instant(k.lastUsedMillis),
        revokedAt: instant(k.revokedAtMillis),
      }),
    )
  }

  /** Revokes a key: refused on its next request on every instance. Revoking twice is not an error. */
  async revoke(id: string): Promise<void> {
    const { stub, metadata } = this.#channel()
    const reply = await stub().revokeClientKey({ id, metadata: metadataToProto(metadata) }).catch(tooOld)
    if (reply.error) throw refusalOf(reply.error)
  }
}

/** Client keys for unit tests: a map, issuing keys that look like the runtime's and applying its rules. */
export class InMemoryClientKeys extends ClientKeys {
  readonly #keys = new Map<string, { summary: ClientKeySummary; clientKey: string }>()
  #next = 0

  constructor() {
    super(undefined)
  }

  /** The key issued under `id`, which a test may present; the runtime shows it only once. */
  keyOf(id: string): string | undefined {
    return this.#keys.get(id)?.clientKey
  }

  override async issue(request: IssueClientKey): Promise<IssuedClientKey> {
    if (typeof request.subject !== "string" || request.subject.trim() === "") {
      throw new CommandError({ message: "a client key needs a subject", code: ErrorCode.BadRequest })
    }
    const attributes = request.attributes ?? {}
    if (Object.keys(attributes).length > 16) throw new CommandError({ message: "a client key carries at most 16 attributes", code: ErrorCode.BadRequest })
    for (const [name, value] of Object.entries(attributes)) {
      if (name.length > 256 || value.length > 256) throw new CommandError({ message: `attribute '${name.slice(0, 40)}' is longer than 256 characters`, code: ErrorCode.BadRequest })
    }
    this.#next += 1
    const id = `ck_${this.#next}`
    const clientKey = `ak_test_${id}_${Math.random().toString(36).slice(2)}`
    const now = Instant.now()
    const expiry = request.expiry
    const expiresAt =
      expiry.kind === "after" ? Instant.ofEpochMilli(now.toEpochMilli() + expiry.millis) : expiry.kind === "at" ? Instant.ofEpochMilli(expiry.epochMillis) : null
    const summary: ClientKeySummary = Object.freeze({
      id,
      subject: request.subject,
      attributes: Object.freeze({ ...attributes }),
      label: request.label ?? null,
      endpoint: request.endpoint ?? "",
      scope: Object.freeze([...(request.scope ?? [])]),
      issuedAt: now,
      expiresAt,
      lastUsed: null,
      revokedAt: null,
    })
    this.#keys.set(id, { summary, clientKey })
    return Object.freeze({ id, clientKey })
  }

  override async list(): Promise<ClientKeySummary[]> {
    return [...this.#keys.values()].map((k) => k.summary)
  }

  override async revoke(id: string): Promise<void> {
    const held = this.#keys.get(id)
    if (!held) throw new CommandError({ message: `no client key '${id}'`, code: ErrorCode.NotFound })
    if (held.summary.revokedAt === null) this.#keys.set(id, { ...held, summary: Object.freeze({ ...held.summary, revokedAt: Instant.now() }) })
  }
}

/**
 * @internal The client keys reached through `client`'s sidecar. Not a member of the client, which an
 * entity holds too: only the component kinds that may issue keys call this.
 */
export function clientKeysFor(client: ComponentClient): ClientKeys {
  return new ClientKeys(client)
}
