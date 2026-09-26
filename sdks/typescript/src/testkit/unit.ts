// The unit testkits: one component, no sidecar, no database, no network. Effects come back as values.
// Every input, event, state and reply is encoded and decoded through the component's own codecs on the
// way in and out, so a shape the codec cannot express fails here rather than on first deployment. The
// reduction is the server's (`materialise.ts`), so a test cannot pass on something the sidecar would
// not do.

import { create } from "@bufbuild/protobuf"
import { HttpRequestSchema } from "../_proto/ankka/protocol/v1/endpoint_pb.ts"
import { ComponentClient, noClient } from "../client.ts"
import { binaryCodecs, codecFor, type Codec, type Shape } from "../codec.ts"
import { commandContext, type Principal } from "../context.ts"
import type { ErrorDetail, Metadata, Retention } from "../effects/common.ts"
import type { EventSourcedEffect } from "../effects/eventSourced.ts"
import type { Endpoint, EndpointClass } from "../endpoint.ts"
import { EventSourcedEntity, type EventSourcedEntityClass } from "../eventSourcedEntity.ts"
import type { HandlerRef } from "../handlers.ts"
import { materialiseEventSourced, type Materialised } from "../materialise.ts"
import { Ankka, type RegisteredEventSourced } from "../service.ts"
import { createHttpDispatcher, type HttpDispatcher } from "../server/http.ts"
import type { RouteRef } from "../routes.ts"

export type { Materialised }

function roundTrip<T>(codec: Codec<T>, value: T): T {
  return codec.decode(codec.encode(value))
}

function findHandler(handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>, handler: HandlerRef<any, any, any, any> | string, who: string): HandlerRef<any, any, any, any> {
  const name = typeof handler === "string" ? handler : handler.name
  const found = handlers.get(name)
  if (!found) throw new Error(`${who} has no handler ${JSON.stringify(name)}; declared: ${[...handlers.keys()].sort().join(", ")}`)
  return found
}

/** Drives one event sourced entity instance through its handlers, folding events as it goes. */
export class EventSourcedTestKit<S, E, C extends EventSourcedEntity<S, E>> {
  readonly #registered: RegisteredEventSourced
  readonly #entity: C
  readonly #client: ComponentClient
  readonly entityId: string
  /** The state now, after every call so far. */
  state: S
  /** The sequence number of the last event, `0n` before any. */
  sequence = 0n
  /** Every event persisted so far, in order. */
  readonly allEvents: E[] = []

  private constructor(cls: EventSourcedEntityClass<S, E, C>, entityId: string, client: ComponentClient) {
    const registry = Ankka.service({ client, log: () => {} })
      .register(cls)
      .validate()
    this.#registered = registry.component(cls.componentId) as RegisteredEventSourced
    this.#entity = new cls()
    this.#entity._bindInstance(entityId)
    this.#client = client
    this.entityId = entityId
    this.state = this.#entity.emptyState()
  }

  /** A kit for `cls` as instance `entityId`. Calls to other components reach `client`, which by default refuses them. */
  static of<S, E, C extends EventSourcedEntity<S, E>>(cls: EventSourcedEntityClass<S, E, C>, entityId = "test", client: ComponentClient = noClient()): EventSourcedTestKit<S, E, C> {
    return new EventSourcedTestKit(cls, entityId, client)
  }

  /** Sends a command or query and returns what its effect amounts to. */
  async call<I, R>(handler: HandlerRef<C, I, R, any> | string, input?: I, metadata: Metadata = {}): Promise<Materialised<S, E, R>> {
    const ref = findHandler(this.#registered.handlers, handler, this.#registered.cls.name)
    const inputCodec = ref.input ? codecFor(ref.input as Shape<I>) : undefined
    const wireInput = inputCodec ? roundTrip(inputCodec, input as I) : undefined
    const context = commandContext(this.#registered.id, this.entityId, this.sequence, metadata)
    this.#entity._bindCommand(this.state, context, this.#client.withMetadata(metadata))
    let effect: EventSourcedEffect<S, E, R>
    try {
      effect = (await ref.run(this.#entity, wireInput)) as EventSourcedEffect<S, E, R>
    } finally {
      this.#entity._unbindCommand()
    }
    if (ref.readOnly && effect.kind !== "read-only") throw new Error(`${this.#registered.id}/${ref.name} is a query and returned a persisting effect`)

    const m = materialiseEventSourced(effect, this.state, (s, e) => this.#entity.applyEvent(s, e))
    const eventCodec = this.#registered.eventCodec as Codec<E>
    const stateCodec = this.#registered.stateCodec as Codec<S>
    const events = m.events.map((e) => roundTrip(eventCodec, e))
    if (m.error) return Object.freeze({ events: [], newState: this.state, retention: m.retention, reply: undefined, error: m.error, noReply: false })
    const newState = roundTrip(stateCodec, m.newState)
    const replyCodec = (ref.reply ? codecFor(ref.reply as Shape<R>) : binaryCodecs.done) as Codec<R>
    const reply = m.noReply ? undefined : roundTrip(replyCodec, m.reply as R)
    this.state = newState
    this.sequence += BigInt(events.length)
    this.allEvents.push(...events)
    return Object.freeze({ events, newState, retention: m.retention, reply, error: undefined, noReply: m.noReply })
  }
}

/** A reply from the endpoint testkit. */
export class Response {
  readonly status: number
  readonly contentType: string
  readonly body: Uint8Array

  constructor(status: number, contentType: string, body: Uint8Array) {
    this.status = status
    this.contentType = contentType
    this.body = body
    Object.freeze(this)
  }

  text(): string {
    return new TextDecoder().decode(this.body)
  }

  json(): unknown {
    return JSON.parse(this.text())
  }
}

export interface RequestOptions {
  readonly query?: Readonly<Record<string, string | readonly string[]>>
  readonly headers?: Readonly<Record<string, string>>
  readonly principal?: Principal
  readonly metadata?: Metadata
}

const PLACEHOLDER = /\{[A-Za-z_][A-Za-z0-9_]*\}/g

/** Calls an endpoint's routes by path, matching them as the sidecar's router does: literal segments outrank parameters. */
export class EndpointTestKit<C extends Endpoint> {
  readonly #cls: EndpointClass<C>
  readonly #dispatcher: HttpDispatcher

  private constructor(cls: EndpointClass<C>, client: ComponentClient) {
    this.#cls = cls
    const registry = Ankka.service({ client, log: () => {} })
      .register(cls)
      .validate()
    this.#dispatcher = createHttpDispatcher({ registry, client, log: () => {} })
  }

  static of<C extends Endpoint>(cls: EndpointClass<C>, client: ComponentClient = noClient()): EndpointTestKit<C> {
    return new EndpointTestKit(cls, client)
  }

  #match(method: string, path: string): { id: string; route: RouteRef<any, any, any, any>; args: string[] } | undefined {
    const prefix = this.#cls.prefix
    const withoutQuery = path.split("?")[0]!
    const rest = withoutQuery.startsWith(prefix) ? withoutQuery.slice(prefix.length) || "/" : withoutQuery
    const candidates: { literals: number; id: string; route: RouteRef<any, any, any, any>; args: string[] }[] = []
    for (const [id, route] of Object.entries(this.#cls.routes)) {
      if (route.method !== method.toUpperCase()) continue
      const pattern = new RegExp("^" + route.template.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(PLACEHOLDER, "([^/]+)") + "$")
      const m = pattern.exec(rest)
      if (!m) continue
      const literals = route.template.split("/").filter((seg) => seg && !seg.startsWith("{")).length
      candidates.push({ literals, id, route, args: m.slice(1).map(decodeURIComponent) })
    }
    candidates.sort((a, b) => b.literals - a.literals)
    return candidates[0]
  }

  #request(method: string, path: string, body: unknown, options: RequestOptions) {
    const matched = this.#match(method, path)
    if (!matched) return undefined
    let bytes: Uint8Array = new Uint8Array()
    if (body !== undefined && body !== null) {
      if (body instanceof Uint8Array) bytes = new Uint8Array(body)
      else if (typeof body === "string") bytes = new TextEncoder().encode(body)
      else if (matched.route.body) bytes = (codecFor(matched.route.body) as Codec<unknown>).encode(body)
      else bytes = new TextEncoder().encode(JSON.stringify(body))
    }
    const query: { name: string; value: string }[] = []
    const fromPath = path.includes("?") ? new URLSearchParams(path.slice(path.indexOf("?") + 1)) : undefined
    for (const [name, value] of fromPath ?? []) query.push({ name, value })
    for (const [name, value] of Object.entries(options.query ?? {})) {
      for (const v of typeof value === "string" ? [value] : value) query.push({ name, value: v })
    }
    const headers = Object.entries(options.headers ?? {}).map(([name, value]) => ({ name, value }))
    const principal = options.principal
      ? { subject: options.principal.subject, name: options.principal.name ?? undefined, email: options.principal.email ?? undefined, emailVerified: options.principal.emailVerified, roles: [...options.principal.roles] }
      : undefined
    const req = create(HttpRequestSchema, {
      endpointId: this.#cls.name,
      routeId: matched.id,
      pathArgs: matched.args,
      query,
      headers,
      contentType: matched.route.body ? codecFor(matched.route.body).contentType : "",
      body: bytes,
      principal,
      metadata: { entries: Object.entries(options.metadata ?? {}).map(([key, value]) => ({ key, value })) },
    })
    return { req, route: matched.route }
  }

  async request(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<Response> {
    const prepared = this.#request(method, path, body, options)
    if (!prepared) return new Response(404, "text/plain", new TextEncoder().encode(`no route ${method.toUpperCase()} ${path}`))
    if (prepared.route.streaming) {
      const frames: string[] = []
      for await (const f of this.#dispatcher.handleStream(prepared.req)) {
        if (f.frame.case === "text") frames.push(f.frame.value)
        if (f.frame.case === "failed") return new Response(500, "text/plain", new TextEncoder().encode(f.frame.value.message))
      }
      return new Response(200, "text/event-stream", new TextEncoder().encode(frames.join("\n")))
    }
    const reply = await this.#dispatcher.handle(prepared.req)
    if (reply.message.case === "response") {
      const r = reply.message.value
      return new Response(r.status, r.contentType, r.body)
    }
    return new Response(500, "text/plain", new TextEncoder().encode(reply.message.case === "failure" ? reply.message.value.error?.message ?? "failure" : "no reply"))
  }

  /** The frames of an SSE route, each string one frame. */
  async sse(path: string, options: RequestOptions = {}): Promise<string[]> {
    const prepared = this.#request("GET", path, undefined, options)
    if (!prepared) throw new Error(`no route GET ${path}`)
    const frames: string[] = []
    for await (const f of this.#dispatcher.handleStream(prepared.req)) {
      if (f.frame.case === "text") frames.push(f.frame.value)
      if (f.frame.case === "failed") throw new Error(f.frame.value.message)
    }
    return frames
  }

  get(path: string, options?: RequestOptions): Promise<Response> {
    return this.request("GET", path, undefined, options)
  }
  post(path: string, body?: unknown, options?: RequestOptions): Promise<Response> {
    return this.request("POST", path, body, options)
  }
  put(path: string, body?: unknown, options?: RequestOptions): Promise<Response> {
    return this.request("PUT", path, body, options)
  }
  patch(path: string, body?: unknown, options?: RequestOptions): Promise<Response> {
    return this.request("PATCH", path, body, options)
  }
  delete(path: string, body?: unknown, options?: RequestOptions): Promise<Response> {
    return this.request("DELETE", path, body, options)
  }
}

export type { ErrorDetail, Retention }
