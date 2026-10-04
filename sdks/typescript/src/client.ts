// The component client: how a handler calls other components, queries views and schedules timers. Every
// call is a request on the sidecar's `Client` service at `ANKKA_SIDECAR_ADDRESS`; the sidecar routes it.
// One channel per process, opened on first use so the integration testkit can learn the sidecar's
// mapped port after construction. A client handed to a handler is scoped to that request's metadata,
// so the sidecar records the call as a child span.

import { Code, ConnectError, createClient, type Client as ConnectClient, type Transport } from "@connectrpc/connect"
import { createGrpcTransport } from "@connectrpc/connect-node"
import { Client } from "./_proto/ankka/protocol/v1/client_pb.ts"
import type { Payload, ErrorCode as ProtoErrorCode } from "./_proto/ankka/protocol/v1/payload_pb.ts"
import { codecFor, jsonCodec, isCodec, textCodecs, binaryCodecs, codecForManifest, type Codec, type Shape } from "./codec.ts"
import { CommandError, type ErrorDetail, type Metadata } from "./effects/common.ts"
import type { HandlerRef } from "./handlers.ts"
import { decodeJsonValue, reviver } from "./json.ts"
import { errorCodeFromProto, kindToProto, type ComponentKind } from "./kinds.ts"
import { metadataToProto } from "./context.ts"
import { encodePayload, decodePayload, EMPTY_PAYLOAD } from "./server/payloads.ts"
import type { Schema } from "./schema.ts"
import type { Duration } from "./time.ts"
import { AutonomousAgentCalls, TaskCalls, Tasks } from "./autonomous.ts"

/** A component class as the typed client sees it: an id and, on its prototype, its kind. */
export interface ComponentRef {
  readonly componentId: string
  readonly prototype: { readonly _kind: ComponentKind }
}

interface Connection {
  address: string
  transport: Transport | undefined
  stub: ConnectClient<typeof Client> | undefined
}

function stubOf(connection: Connection): ConnectClient<typeof Client> {
  if (!connection.stub) {
    connection.transport = createGrpcTransport({ baseUrl: `http://${connection.address}` })
    connection.stub = createClient(Client, connection.transport)
  }
  return connection.stub
}

function errorOf(error: { message: string; code: ProtoErrorCode }): ErrorDetail {
  return { message: error.message, code: errorCodeFromProto(error.code) }
}

function inputPayload<I>(shape: Shape<I> | undefined, input: I | undefined): Payload {
  if (shape === undefined) {
    if (input !== undefined) throw new TypeError("this handler takes no input")
    return EMPTY_PAYLOAD
  }
  return encodePayload(codecFor(shape), input as I)
}

function decodeReply<R>(shape: Shape<R> | undefined, payload: Payload | undefined): R {
  if (shape !== undefined) return decodePayload(codecFor(shape), payload)
  // No reply shape named: a primitive by its manifest, `done` otherwise.
  const codec = payload ? codecForManifest(payload.manifest) : undefined
  return (codec ?? binaryCodecs.done).decode(payload?.data ?? new Uint8Array()) as R
}

/** One call, ready to invoke or stream. */
export class Invocation<I, R> {
  readonly #connection: Connection
  readonly #metadata: Metadata
  readonly #kind: ComponentKind
  readonly #componentId: string
  readonly #entityId: string
  readonly #name: string
  readonly #input: Shape<I> | undefined
  readonly #reply: Shape<R> | undefined

  constructor(connection: Connection, metadata: Metadata, kind: ComponentKind, componentId: string, entityId: string, name: string, input: Shape<I> | undefined, reply: Shape<R> | undefined) {
    this.#connection = connection
    this.#metadata = metadata
    this.#kind = kind
    this.#componentId = componentId
    this.#entityId = entityId
    this.#name = name
    this.#input = input
    this.#reply = reply
  }

  #request(input: I | undefined) {
    return {
      kind: kindToProto(this.#kind),
      componentId: this.#componentId,
      entityId: this.#entityId,
      name: this.#name,
      payload: inputPayload(this.#input, input),
      metadata: metadataToProto(this.#metadata),
    }
  }

  /** Invokes and decodes the reply; a refusal rejects with `CommandError`. */
  async invoke(input?: I): Promise<R> {
    const answer = await stubOf(this.#connection).invoke(this.#request(input))
    switch (answer.result.case) {
      case "reply":
        return decodeReply(this.#reply, answer.result.value.payload)
      case "error":
        throw new CommandError(errorOf(answer.result.value))
      default:
        throw new CommandError({ message: "the sidecar answered nothing", code: "INTERNAL" })
    }
  }

  /** Invokes a streaming handler; yields tokens in order; a failure rejects with `CommandError`. */
  async *stream(input?: I): AsyncIterable<string> {
    for await (const token of stubOf(this.#connection).invokeStream(this.#request(input))) {
      switch (token.token.case) {
        case "text":
          yield token.token.value
          break
        case "completed":
          return
        case "failed":
          throw new CommandError(errorOf(token.token.value))
      }
    }
  }
}

/** Calls on one component instance, by wire name. */
export class Calls {
  readonly #connection: Connection
  readonly #metadata: Metadata
  readonly #kind: ComponentKind
  readonly #componentId: string
  readonly #entityId: string

  constructor(connection: Connection, metadata: Metadata, kind: ComponentKind, componentId: string, entityId: string) {
    this.#connection = connection
    this.#metadata = metadata
    this.#kind = kind
    this.#componentId = componentId
    this.#entityId = entityId
  }

  /** `call("add-item", LineItem, Done)`: the wire name, the input shape (omit for none) and the reply shape (omit for `done`). */
  call<I = undefined, R = unknown>(name: string, input?: Shape<I>, reply?: Shape<R>): Invocation<I, R> {
    return new Invocation(this.#connection, this.#metadata, this.#kind, this.#componentId, this.#entityId, name, input, reply)
  }
}

/** Calls on one component instance through its class's handler table, fully typed. */
export class TypedCalls<C> {
  readonly #calls: Calls

  constructor(calls: Calls) {
    this.#calls = calls
  }

  /** `call(ShoppingCartEntity.handlers.addItem)`: the handler's wire name and shapes come from the declaration. */
  call<I, R>(handler: HandlerRef<C, I, R, any>): Invocation<I, R> {
    return this.#calls.call<I, R>(handler.name, handler.input, handler.reply)
  }
}

/** View queries: rows come back as a JSON array decoded with the row shape. */
export class Views {
  readonly #connection: Connection
  readonly #metadata: Metadata

  constructor(connection: Connection, metadata: Metadata = {}) {
    this.#connection = connection
    this.#metadata = metadata
  }

  async get<Row>(viewId: string, key: string, row: Shape<Row>): Promise<Row | null> {
    const rows = await this.query(viewId, "get", key, row)
    return rows[0] ?? null
  }

  async all<Row>(viewId: string, row: Shape<Row>): Promise<Row[]> {
    return this.query(viewId, "all", null, row)
  }

  async query<Row>(viewId: string, name: string, key: string | null, row: Shape<Row>): Promise<Row[]> {
    const answer = await stubOf(this.#connection).query({ viewId, name, payload: encodePayload(textCodecs.string, key ?? ""), metadata: metadataToProto(this.#metadata) })
    switch (answer.result.case) {
      case "rows": {
        const text = new TextDecoder().decode(answer.result.value.data)
        const documents = JSON.parse(text, reviver) as unknown[]
        if (!Array.isArray(documents)) throw new CommandError({ message: "the view answered something other than a JSON array", code: "INTERNAL" })
        if (isCodec(row)) {
          const utf8 = new TextEncoder()
          return documents.map((d) => row.decode(utf8.encode(JSON.stringify(d, (_k, v) => (typeof v === "bigint" ? (JSON as unknown as { rawJSON(s: string): unknown }).rawJSON(v.toString()) : v)))))
        }
        return documents.map((d) => decodeJsonValue(row as Schema<Row>, d))
      }
      case "error":
        throw new CommandError(errorOf(answer.result.value))
      default:
        throw new CommandError({ message: "the sidecar answered nothing", code: "INTERNAL" })
    }
  }
}

/** What a timer fires: a component class and one of its handlers, or the same by name. */
export type TimerTarget =
  | { readonly component: ComponentRef; readonly handler: HandlerRef<any, any, any, any>; readonly entityId?: string }
  | { readonly kind: ComponentKind; readonly componentId: string; readonly name: string; readonly input?: Shape<any>; readonly entityId?: string }

export class Timers {
  readonly #connection: Connection

  constructor(connection: Connection) {
    this.#connection = connection
  }

  /** Schedules `target` after `delay`. Scheduling twice under one id replaces the earlier schedule. */
  async schedule(timerId: string, delay: Duration, target: TimerTarget, input?: unknown): Promise<void> {
    const t =
      "component" in target
        ? { kind: target.component.prototype._kind, componentId: target.component.componentId, name: target.handler.name, input: target.handler.input as Shape<unknown> | undefined, entityId: target.entityId }
        : target
    await stubOf(this.#connection).schedule({
      timerId,
      delayMillis: BigInt(delay.toMillis()),
      kind: kindToProto(t.kind),
      componentId: t.componentId,
      ...(t.entityId !== undefined ? { entityId: t.entityId } : {}),
      name: t.name,
      payload: inputPayload(t.input, input),
    })
  }

  async cancel(timerId: string): Promise<void> {
    await stubOf(this.#connection).cancel({ timerId })
  }
}

// ── The secret store (protocol 1.6) ───────────────────────────────────────────

const SECRETS_SINCE = "1.6"
export const MAX_SECRET_NAME_LENGTH = 253
export const MAX_SECRET_VALUE_BYTES = 65536
const SECRET_NAME_RULE = `a secret's name is 1 to ${MAX_SECRET_NAME_LENGTH} characters, each a letter, a digit, '.', '_', '-' or '/'`
const VALID_SECRET_NAME = new RegExp(`^[A-Za-z0-9._/-]{1,${MAX_SECRET_NAME_LENGTH}}$`)

/** What is wrong with a secret's name, if anything: the runtime's own rule. */
export function secretNameProblem(name: string): string | undefined {
  if (VALID_SECRET_NAME.test(name)) return undefined
  const shown = name.length > 40 ? `${name.slice(0, 40)}…` : name
  return `${SECRET_NAME_RULE}; "${shown}" is not`
}

/** What is wrong with a secret's value, if anything. Never quotes the value. */
export function secretValueProblem(value: string): string | undefined {
  if (value === "") return "a secret's value must not be empty"
  const size = new TextEncoder().encode(value).length
  return size > MAX_SECRET_VALUE_BYTES ? `a secret's value is at most ${MAX_SECRET_VALUE_BYTES} bytes as UTF-8; this one is ${size}` : undefined
}

function tooOld(failure: unknown): never {
  if (failure instanceof ConnectError && failure.code === Code.Unimplemented) {
    throw new CommandError({
      message: `the runtime beside this process does not offer the secret store, which needs protocol ${SECRETS_SINCE}: ${failure.rawMessage}`,
      code: "INTERNAL",
    })
  }
  throw failure
}

/**
 * The service's secret store: named text values the runtime keeps in the service's own database,
 * encrypted with a key this process never sees. Offered to endpoints, workflow steps, consumers,
 * timed actions and agents; an entity and a view have no `secrets` at all.
 *
 * Every refusal is a `CommandError` with the runtime's code: `BAD_REQUEST` for a name or a value that
 * breaks its rule, `INTERNAL` when the service has no secret key or a value was kept with another
 * one, `UNAVAILABLE` when the database cannot be reached.
 */
export class Secrets {
  readonly #client: ComponentClient | undefined

  /** The store reached through `client`'s sidecar; with none, every call throws. */
  constructor(client?: ComponentClient) {
    this.#client = client
  }

  #stub(): ConnectClient<typeof Client> {
    const connection = this.#client ? connectionOf.get(this.#client) : undefined
    if (!connection) throw new Error("this secret store is not connected to a runtime; a unit test passes an InMemorySecrets")
    return stubOf(connection)
  }

  /** Keeps `value` under `name`, replacing what was there. */
  async put(name: string, value: string): Promise<void> {
    const reply = await this.#stub().putSecret({ name, value }).catch(tooOld)
    if (reply.error) throw new CommandError(errorOf(reply.error))
  }

  /** The value kept under `name`, or `undefined` when there is none. Never an empty string. */
  async get(name: string): Promise<string | undefined> {
    const reply = await this.#stub().getSecret({ name }).catch(tooOld)
    switch (reply.result.case) {
      case "value":
        return reply.result.value
      case "absent":
        return undefined
      case "error":
        throw new CommandError(errorOf(reply.result.value))
      default:
        throw new CommandError({ message: "the runtime answered a secret's read with nothing", code: "INTERNAL" })
    }
  }

  /** Removes what is kept under `name`. Removing nothing is not an error. */
  async delete(name: string): Promise<void> {
    const reply = await this.#stub().deleteSecret({ name }).catch(tooOld)
    if (reply.error) throw new CommandError(errorOf(reply.error))
  }
}

/** A secret store for unit tests: a map, applying the runtime's rules with its words. */
export class InMemorySecrets extends Secrets {
  readonly values = new Map<string, string>()

  override async put(name: string, value: string): Promise<void> {
    const problem = secretNameProblem(name) ?? secretValueProblem(value)
    if (problem) throw new CommandError({ message: problem, code: "BAD_REQUEST" })
    this.values.set(name, value)
  }

  override async get(name: string): Promise<string | undefined> {
    const problem = secretNameProblem(name)
    if (problem) throw new CommandError({ message: problem, code: "BAD_REQUEST" })
    return this.values.get(name)
  }

  override async delete(name: string): Promise<void> {
    const problem = secretNameProblem(name)
    if (problem) throw new CommandError({ message: problem, code: "BAD_REQUEST" })
    this.values.delete(name)
  }
}

/** A store for unit tests whose every call throws: the unit kit's default, beside `noClient()`. */
export function noSecrets(): Secrets {
  return new Secrets(undefined)
}

const connectionOf = new WeakMap<ComponentClient, Connection>()

/**
 * @internal The secret store reached through `client`'s sidecar. Not a member of the client, which
 * an entity holds too: only the component kinds that may have a store call this.
 */
export function secretsFor(client: ComponentClient): Secrets {
  return new Secrets(client)
}

export class ComponentClient {
  readonly #connection: Connection
  readonly #metadata: Metadata
  readonly views: Views
  readonly timers: Timers

  constructor(address: string = process.env.ANKKA_SIDECAR_ADDRESS ?? "127.0.0.1:9011", connection?: Connection, metadata: Metadata = {}) {
    this.#connection = connection ?? { address, transport: undefined, stub: undefined }
    this.#metadata = metadata
    this.views = new Views(this.#connection, metadata)
    this.timers = new Timers(this.#connection)
    connectionOf.set(this, this.#connection)
  }

  /** Points every client sharing this connection at a new sidecar address (the integration testkit's mapped port). */
  reconnect(address: string): void {
    this.#connection.address = address
    this.#connection.transport = undefined
    this.#connection.stub = undefined
  }

  /** The sidecar address this client dials. */
  get address(): string {
    return this.#connection.address
  }

  /** A client whose calls carry `metadata` (the current request's trace), sharing this one's connection. */
  withMetadata(metadata: Metadata): ComponentClient {
    return new ComponentClient(this.#connection.address, this.#connection, metadata)
  }

  forEventSourcedEntity(componentId: string, entityId: string): Calls {
    return new Calls(this.#connection, this.#metadata, "event-sourced", componentId, entityId)
  }

  forKeyValueEntity(componentId: string, entityId: string): Calls {
    return new Calls(this.#connection, this.#metadata, "key-value", componentId, entityId)
  }

  forWorkflow(componentId: string, workflowId: string): Calls {
    return new Calls(this.#connection, this.#metadata, "workflow", componentId, workflowId)
  }

  forAgent(componentId: string, sessionId: string): Calls {
    return new Calls(this.#connection, this.#metadata, "agent", componentId, sessionId)
  }

  /** Creates tasks for autonomous agents. */
  get tasks(): Tasks {
    return new Tasks(this)
  }

  /** Calls about one task. */
  forTask(taskId: string): TaskCalls {
    return new TaskCalls(this, taskId)
  }

  /** One instance of an autonomous agent, by the id the caller chose; without one, only `runSingleTask`, on an instance the platform names. */
  forAutonomousAgent(agent: { readonly componentId: string } | string, instanceId?: string): AutonomousAgentCalls {
    return new AutonomousAgentCalls(this, typeof agent === "string" ? agent : agent.componentId, instanceId)
  }

  /** @internal A call with a JSON body and a JSON reply, as the platform's own components take them. */
  async _raw(kind: ComponentKind, componentId: string, entityId: string, name: string, body?: unknown): Promise<unknown> {
    const answer = await stubOf(this.#connection).invoke({
      kind: kindToProto(kind),
      componentId,
      entityId,
      name,
      payload: { contentType: "application/json", manifest: "", data: body === undefined ? new Uint8Array() : new TextEncoder().encode(JSON.stringify(body)) },
      metadata: metadataToProto(this.#metadata),
    })
    switch (answer.result.case) {
      case "reply": {
        const data = answer.result.value.payload?.data
        return data && data.length > 0 ? JSON.parse(new TextDecoder().decode(data), reviver) : undefined
      }
      case "error":
        throw new CommandError(errorOf(answer.result.value))
      default:
        throw new CommandError({ message: "the sidecar answered nothing", code: "INTERNAL" })
    }
  }

  /** @internal A streaming call whose tokens are text. */
  async *_rawStream(kind: ComponentKind, componentId: string, entityId: string, name: string): AsyncIterable<string> {
    const request = { kind: kindToProto(kind), componentId, entityId, name, payload: EMPTY_PAYLOAD, metadata: metadataToProto(this.#metadata) }
    for await (const token of stubOf(this.#connection).invokeStream(request)) {
      switch (token.token.case) {
        case "text":
          yield token.token.value
          break
        case "completed":
          return
        case "failed":
          throw new CommandError(errorOf(token.token.value))
      }
    }
  }

  /** Typed calls through a component class's handler table: `client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)`. */
  of<C>(component: ComponentRef & { new (): C }, entityId: string): TypedCalls<C> {
    return new TypedCalls<C>(new Calls(this.#connection, this.#metadata, component.prototype._kind, component.componentId, entityId))
  }
}

/** A client for unit tests: every call throws, naming what was attempted. */
export function noClient(): ComponentClient {
  const fail = (): never => {
    throw new Error("this component called another component; the unit testkit has no sidecar. Pass a stub ComponentClient to the kit.")
  }
  const connection: Connection = {
    address: "unit-test",
    transport: undefined,
    stub: new Proxy({} as ConnectClient<typeof Client>, { get: () => fail }),
  }
  return new ComponentClient("unit-test", connection)
}

export { jsonCodec }
