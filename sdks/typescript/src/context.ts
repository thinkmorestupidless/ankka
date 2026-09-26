// What a handler can see about the request it is running for. A `CommandContext` is bound on the
// component instance for one call; an HTTP `RequestContext` lives in an `AsyncLocalStorage`, the Node
// equivalent of the thread-local the Scala SDK uses and the `ContextVar` the Python SDK uses: one
// request per asynchronous context, and work handed to another context cannot see it.

import { AsyncLocalStorage } from "node:async_hooks"
import { Instant } from "./time.ts"
import type { Metadata } from "./effects/common.ts"
import type { Metadata as ProtoMetadata } from "./_proto/ankka/protocol/v1/payload_pb.ts"

/** The context of one command on a stateful component. */
export interface CommandContext {
  readonly componentId: string
  readonly entityId: string
  /** The sequence number of the last event applied before this command; `0n` for a fresh entity. */
  readonly sequenceNumber: bigint
  readonly metadata: Metadata
  /** The current instant; a seam a test can replace. */
  now(): Instant
}

export function commandContext(componentId: string, entityId: string, sequenceNumber: bigint, metadata: Metadata, now: () => Instant = Instant.now): CommandContext {
  return Object.freeze({ componentId, entityId, sequenceNumber, metadata, now })
}

export function metadataFromProto(proto: ProtoMetadata | undefined): Metadata {
  const out: Record<string, string> = {}
  for (const e of proto?.entries ?? []) out[e.key] = e.value
  return Object.freeze(out)
}

export function metadataToProto(metadata: Metadata | undefined): { entries: { key: string; value: string }[] } {
  return { entries: Object.entries(metadata ?? {}).map(([key, value]) => ({ key, value })) }
}

/** The caller an authenticated route was invoked by. */
export interface Principal {
  readonly subject: string
  readonly name: string | null
  readonly email: string | null
  readonly emailVerified: boolean
  readonly roles: readonly string[]
}

/** Query parameters in request order; a name may repeat. */
export class Query {
  private readonly pairs: readonly (readonly [string, string])[]

  constructor(pairs: readonly (readonly [string, string])[]) {
    this.pairs = pairs
    Object.freeze(this)
  }
  get(name: string): string | null {
    const hit = this.pairs.find(([n]) => n === name)
    return hit ? hit[1] : null
  }
  getAll(name: string): string[] {
    return this.pairs.filter(([n]) => n === name).map(([, v]) => v)
  }
  has(name: string): boolean {
    return this.pairs.some(([n]) => n === name)
  }
  entries(): readonly (readonly [string, string])[] {
    return this.pairs
  }
}

/** Request headers; names are matched case-insensitively. */
export class Headers {
  private readonly pairs: readonly (readonly [string, string])[]

  constructor(pairs: readonly (readonly [string, string])[]) {
    this.pairs = pairs
    Object.freeze(this)
  }
  get(name: string): string | null {
    const lower = name.toLowerCase()
    const hit = this.pairs.find(([n]) => n.toLowerCase() === lower)
    return hit ? hit[1] : null
  }
  getAll(name: string): string[] {
    const lower = name.toLowerCase()
    return this.pairs.filter(([n]) => n.toLowerCase() === lower).map(([, v]) => v)
  }
  entries(): readonly (readonly [string, string])[] {
    return this.pairs
  }
}

/** What an endpoint handler sees of its request: `this.request` on the endpoint. `P` is the typed path parameters. */
export interface RequestContext<P = Readonly<Record<string, unknown>>> {
  readonly params: P
  readonly query: Query
  readonly headers: Headers
  /** The caller, when the route's access rule is `authenticated`; `null` otherwise. */
  readonly principal: Principal | null
  readonly metadata: Metadata
}

const requestStorage = new AsyncLocalStorage<RequestContext>()

/** Runs `fn` with `request` as the current request, for the asynchronous work it starts. */
export function withRequest<T>(request: RequestContext, fn: () => T): T {
  return requestStorage.run(request, fn)
}

/** The current request, or `undefined` outside one. */
export function requestIfAny(): RequestContext | undefined {
  return requestStorage.getStore()
}

/** The current request, or a clear error when called outside one. */
export function currentRequest(): RequestContext {
  const r = requestStorage.getStore()
  if (!r) throw new Error("request is only available inside an endpoint handler")
  return r
}
