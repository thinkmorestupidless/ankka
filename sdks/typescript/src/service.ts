// The service: the explicit registry of components and endpoints. Nothing is found by scanning; a class
// exists to the runtime only once it is `register`ed here. Problems are collected, not thrown one at a
// time, and reported together before anything listens — the same rule as the Scala builder.
//
//   await Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint).listen()

import { codecFor, isCodec, type Codec, type Shape } from "./codec.ts"
import { EventSourcedEntity, type EventSourcedEntityClass } from "./eventSourcedEntity.ts"
import { KeyValueEntity, type KeyValueEntityClass } from "./keyValueEntity.ts"
import { Workflow, type WorkflowClass } from "./workflow.ts"
import { View, type ViewClass } from "./view.ts"
import { Consumer, type ConsumerClass } from "./consumer.ts"
import { TimedAction, type TimedActionClass } from "./timedAction.ts"
import { Agent, type AgentClass } from "./agent.ts"
import { Endpoint, type EndpointClass } from "./endpoint.ts"
import type { GuardrailRef, HandlerRef, ToolRef } from "./handlers.ts"
import type { RouteRef, Acl } from "./routes.ts"
import { Acl as AclValues } from "./routes.ts"
import { ComponentClient, type ComponentRef } from "./client.ts"
import type { ComponentKind } from "./kinds.ts"
import type { WorkflowSettings } from "./effects/workflow.ts"
import { Server, type ServerOptions } from "./server/server.ts"
import { renderSpec } from "./spec.ts"
import type { Spec } from "./_proto/ankka/protocol/v1/discovery_pb.ts"

export interface RegisteredEventSourced {
  readonly kind: "event-sourced"
  readonly id: string
  readonly cls: EventSourcedEntityClass<any, any, any>
  readonly stateCodec: Codec<any>
  readonly eventCodec: Codec<any>
  readonly handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>
  readonly snapshotEvery: number
}

export interface RegisteredKeyValue {
  readonly kind: "key-value"
  readonly id: string
  readonly cls: KeyValueEntityClass<any, any>
  readonly stateCodec: Codec<any>
  readonly handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>
}

export interface RegisteredWorkflow {
  readonly kind: "workflow"
  readonly id: string
  readonly cls: WorkflowClass<any, any>
  readonly stateCodec: Codec<any>
  readonly handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>
  readonly steps: ReadonlyMap<string, HandlerRef<any, any, any, any>>
  readonly settings: WorkflowSettings | undefined
}

/** Where a view or consumer reads from: a component, by kind and id, or a topic. */
export type Source = { readonly component: { readonly kind: ComponentKind; readonly id: string } } | { readonly topic: string }

export interface RegisteredView {
  readonly kind: "view"
  readonly id: string
  readonly cls: ViewClass<any, any, any>
  readonly source: Source
  readonly eventCodec: Codec<any>
  readonly rowCodec: Codec<any>
  readonly queries: readonly string[]
}

export interface RegisteredConsumer {
  readonly kind: "consumer"
  readonly id: string
  readonly cls: ConsumerClass<any, any, any>
  readonly source: Source
  readonly messageCodec: Codec<any>
  readonly outCodec: Codec<any> | undefined
  readonly producesTo: string | undefined
}

export interface RegisteredTimedAction {
  readonly kind: "timed-action"
  readonly id: string
  readonly cls: TimedActionClass<any>
  readonly actions: ReadonlyMap<string, HandlerRef<any, any, any, any>>
}

export interface RegisteredAgent {
  readonly kind: "agent"
  readonly id: string
  readonly cls: AgentClass<any>
  readonly handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>
  readonly tools: ReadonlyMap<string, ToolRef<any, any>>
  readonly guardrails: ReadonlyMap<string, GuardrailRef>
  readonly role: string
  readonly maxToolCallSteps: number
}

export type RegisteredComponent =
  | RegisteredEventSourced
  | RegisteredKeyValue
  | RegisteredWorkflow
  | RegisteredView
  | RegisteredConsumer
  | RegisteredTimedAction
  | RegisteredAgent

export interface RegisteredEndpoint {
  readonly id: string
  readonly cls: EndpointClass<any>
  readonly prefix: string
  readonly acl: Acl
  /** Route id (the property name) → route. */
  readonly routes: ReadonlyMap<string, RouteRef<any, any, any, any>>
}

/** Everything the service hosts, validated. */
export class Registry {
  readonly components: ReadonlyMap<string, RegisteredComponent>
  readonly endpoints: ReadonlyMap<string, RegisteredEndpoint>

  constructor(components: ReadonlyMap<string, RegisteredComponent>, endpoints: ReadonlyMap<string, RegisteredEndpoint>) {
    this.components = components
    this.endpoints = endpoints
    Object.freeze(this)
  }

  component(id: string): RegisteredComponent | undefined {
    return this.components.get(id)
  }

  /** The component `id` when it is of `kind`. */
  of<K extends RegisteredComponent["kind"]>(kind: K, id: string): Extract<RegisteredComponent, { kind: K }> | undefined {
    const c = this.components.get(id)
    return c && c.kind === kind ? (c as Extract<RegisteredComponent, { kind: K }>) : undefined
  }

  endpoint(id: string): RegisteredEndpoint | undefined {
    return this.endpoints.get(id)
  }
}

/** Thrown by `validate`, `spec`, `server` and `listen` with every problem the registration has. */
export class RegistrationError extends Error {
  readonly problems: readonly string[]
  constructor(problems: readonly string[]) {
    super(`the service cannot start:\n${problems.map((p) => `  - ${p}`).join("\n")}`)
    this.name = "RegistrationError"
    this.problems = problems
  }
}

type AnyClass = abstract new (...args: never[]) => unknown

function extendsBase(cls: unknown, base: AnyClass): boolean {
  return typeof cls === "function" && cls.prototype instanceof base
}

function isShape(x: unknown): x is Shape<unknown> {
  return typeof x === "object" && x !== null && (typeof (x as { kind?: unknown }).kind === "string" || isCodec(x as Shape<unknown>))
}

function nameOf(cls: unknown): string {
  return typeof cls === "function" && cls.name ? cls.name : String(cls)
}

export interface ServiceOptions {
  /** The client handlers receive; the default dials `ANKKA_SIDECAR_ADDRESS`. */
  readonly client?: ComponentClient
  /** Where log lines go; the default is `console.error`. */
  readonly log?: (message: string) => void
}

export class ServiceBuilder {
  readonly #classes: unknown[] = []
  readonly #client: ComponentClient
  readonly #log: (message: string) => void

  constructor(options: ServiceOptions = {}) {
    this.#client = options.client ?? new ComponentClient()
    this.#log = options.log ?? ((m) => console.error(m))
  }

  /** The component client every handler receives; the integration testkit repoints it at the sidecar it started. */
  get client(): ComponentClient {
    return this.#client
  }

  /** Register a component or endpoint class. A class missing a static the kind requires is refused here, by the type checker. */
  register<S, E, C extends EventSourcedEntity<S, E>>(cls: EventSourcedEntityClass<S, E, C>): this
  register<S, C extends KeyValueEntity<S>>(cls: KeyValueEntityClass<S, C>): this
  register<S, C extends Workflow<S>>(cls: WorkflowClass<S, C>): this
  register<E, Row, C extends View<E, Row>>(cls: ViewClass<E, Row, C>): this
  register<M, Out, C extends Consumer<M, Out>>(cls: ConsumerClass<M, Out, C>): this
  register<C extends TimedAction>(cls: TimedActionClass<C>): this
  register<C extends Agent>(cls: AgentClass<C>): this
  register<C extends Endpoint>(cls: EndpointClass<C>): this
  register(cls: AnyClass): this {
    this.#classes.push(cls)
    return this
  }

  /** Validates every registration and returns the registry, or throws a `RegistrationError` naming every problem. */
  validate(): Registry {
    const problems: string[] = []
    const components = new Map<string, RegisteredComponent>()
    const endpoints = new Map<string, RegisteredEndpoint>()
    const prefixes = new Map<string, string>()

    const add = (r: RegisteredComponent | undefined, cls: unknown) => {
      if (!r) return
      if (components.has(r.id)) problems.push(`two components declare the id ${JSON.stringify(r.id)} (${nameOf(components.get(r.id)!.cls)} and ${nameOf(cls)})`)
      else components.set(r.id, r)
    }

    for (const cls of this.#classes) {
      if (extendsBase(cls, EventSourcedEntity)) add(registerEventSourced(cls as EventSourcedEntityClass<any, any, any>, problems), cls)
      else if (extendsBase(cls, KeyValueEntity)) add(registerKeyValue(cls as KeyValueEntityClass<any, any>, problems), cls)
      else if (extendsBase(cls, Workflow)) add(registerWorkflow(cls as WorkflowClass<any, any>, problems), cls)
      else if (extendsBase(cls, View)) add(registerView(cls as ViewClass<any, any, any>, problems), cls)
      else if (extendsBase(cls, Consumer)) add(registerConsumer(cls as ConsumerClass<any, any, any>, problems), cls)
      else if (extendsBase(cls, TimedAction)) add(registerTimedAction(cls as TimedActionClass<any>, problems), cls)
      else if (extendsBase(cls, Agent)) add(registerAgent(cls as AgentClass<any>, problems), cls)
      else if (extendsBase(cls, Endpoint)) {
        const ep = cls as EndpointClass<any>
        if (typeof ep.prefix === "string") {
          const other = prefixes.get(ep.prefix)
          if (other && other !== nameOf(cls)) problems.push(`endpoints ${other} and ${nameOf(cls)} share the prefix ${ep.prefix}`)
          prefixes.set(ep.prefix, nameOf(cls))
        }
        const r = registerEndpoint(ep, problems)
        if (r) {
          if (endpoints.has(r.id)) problems.push(`two endpoints are named ${r.id}`)
          else endpoints.set(r.id, r)
        }
      } else {
        problems.push(`${nameOf(cls)} is not a component class: it must extend EventSourcedEntity, KeyValueEntity, Workflow, View, Consumer, TimedAction, Agent or Endpoint`)
      }
    }

    if (problems.length > 0) throw new RegistrationError(problems)
    return new Registry(components, endpoints)
  }

  /** The discovery `Spec` for what is registered, without listening. */
  spec(): Spec {
    return renderSpec(this.validate())
  }

  /** An unstarted server over the validated registry, for the integration testkit. */
  server(options: ServerOptions = {}): Server {
    return new Server(this.validate(), this.#client, { log: this.#log, ...options })
  }

  /** Validates, binds `ANKKA_PROCESS_PORT` (default 9010) on loopback, answers discovery, and runs until stopped. */
  async listen(options: ServerOptions = {}): Promise<void> {
    const server = this.server(options)
    const { host, port } = await server.start()
    this.#log(`ankka: serving ${this.#classes.length} registered class(es) to the sidecar on ${host}:${port}`)
    const stop = () => void server.stop()
    process.once("SIGTERM", stop)
    process.once("SIGINT", stop)
    try {
      await server.closed
    } finally {
      process.off("SIGTERM", stop)
      process.off("SIGINT", stop)
    }
  }
}

type Fail = (message: string) => void

function checker(cls: unknown, problems: string[]): { fail: Fail; ok: () => boolean } {
  let ok = true
  const name = nameOf(cls)
  return {
    fail: (m) => {
      problems.push(`${name}: ${m}`)
      ok = false
    },
    ok: () => ok,
  }
}

function requireId(cls: { componentId?: unknown }, fail: Fail): void {
  if (typeof cls.componentId !== "string" || cls.componentId.trim() === "") fail("needs a static componentId")
}

function requireShape(value: unknown, what: string, fail: Fail): void {
  if (!isShape(value)) fail(`needs a static ${what}`)
}

function registerEventSourced(cls: EventSourcedEntityClass<any, any, any>, problems: string[]): RegisteredEventSourced | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  requireShape(cls.state, "state: a schema or codec for its state", fail)
  requireShape(cls.events, "events: a schema or codec for its events", fail)
  const snapshotEvery = cls.snapshotEvery ?? 0
  if (!Number.isInteger(snapshotEvery) || snapshotEvery < 0) fail(`snapshotEvery must be a non-negative integer, not ${String(cls.snapshotEvery)}`)
  const handlers = collectHandlers(cls.handlers, "handlers", ["command", "query"], fail)
  if (!ok()) return undefined
  return Object.freeze({ kind: "event-sourced", id: cls.componentId, cls, stateCodec: codecFor(cls.state), eventCodec: codecFor(cls.events), handlers, snapshotEvery })
}

function registerKeyValue(cls: KeyValueEntityClass<any, any>, problems: string[]): RegisteredKeyValue | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  requireShape(cls.state, "state: a schema or codec for its state", fail)
  const handlers = collectHandlers(cls.handlers, "handlers", ["command", "query"], fail)
  if (!ok()) return undefined
  return Object.freeze({ kind: "key-value", id: cls.componentId, cls, stateCodec: codecFor(cls.state), handlers })
}

function registerWorkflow(cls: WorkflowClass<any, any>, problems: string[]): RegisteredWorkflow | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  requireShape(cls.state, "state: a schema or codec for its state", fail)
  const handlers = collectHandlers(cls.handlers, "handlers", ["command", "query"], fail)
  const steps = collectHandlers(cls.steps, "steps", ["step"], fail)
  const settings = cls.settings
  if (settings !== undefined) {
    if (typeof settings !== "object" || settings === null) fail("settings must be a WorkflowSettings object")
    else {
      const check = (what: string, failoverTo: string | undefined) => {
        if (failoverTo !== undefined && !steps.has(failoverTo)) fail(`${what} fails over to ${JSON.stringify(failoverTo)}, which is not a declared step`)
      }
      check("default recovery", settings.defaultRecovery?.failoverTo)
      for (const [name, st] of Object.entries(settings.steps ?? {})) {
        if (!steps.has(name)) fail(`settings name step ${JSON.stringify(name)}, which is not declared`)
        check(`step ${JSON.stringify(name)}'s recovery`, st.recovery?.failoverTo)
      }
    }
  }
  if (!ok()) return undefined
  return Object.freeze({ kind: "workflow", id: cls.componentId, cls, stateCodec: codecFor(cls.state), handlers, steps, settings })
}

function sourceOf(cls: { source?: ComponentRef; topic?: string }, fail: Fail): Source | undefined {
  const hasSource = cls.source !== undefined
  const hasTopic = cls.topic !== undefined
  if (hasSource === hasTopic) {
    fail("needs a static source (a component class) or a static topic, and not both")
    return undefined
  }
  if (hasTopic) {
    if (typeof cls.topic !== "string" || cls.topic.trim() === "") {
      fail("topic must be a non-empty string")
      return undefined
    }
    return { topic: cls.topic }
  }
  const src = cls.source as ComponentRef
  const kind = src?.prototype?._kind
  if (typeof src?.componentId !== "string" || typeof kind !== "string") {
    fail("source must be a component class (one that extends EventSourcedEntity, KeyValueEntity, ...)")
    return undefined
  }
  return { component: { kind, id: src.componentId } }
}

function registerView(cls: ViewClass<any, any, any>, problems: string[]): RegisteredView | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  const source = sourceOf(cls, fail)
  requireShape(cls.events, "events: a schema or codec for the source's events", fail)
  requireShape(cls.row, "row: a schema or codec for its rows", fail)
  const queries = cls.queries ?? ["get", "all"]
  if (!Array.isArray(queries) || queries.some((q) => typeof q !== "string" || q.trim() === "")) fail("queries must be a list of names")
  if (!ok() || !source) return undefined
  return Object.freeze({ kind: "view", id: cls.componentId, cls, source, eventCodec: codecFor(cls.events), rowCodec: codecFor(cls.row), queries: Object.freeze([...queries]) })
}

function registerConsumer(cls: ConsumerClass<any, any, any>, problems: string[]): RegisteredConsumer | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  const source = sourceOf(cls, fail)
  requireShape(cls.message, "message: a schema or codec for the source's messages", fail)
  if (cls.producesTo !== undefined && (typeof cls.producesTo !== "string" || cls.producesTo.trim() === "")) fail("producesTo must be a topic name")
  if (cls.producesTo !== undefined && !isShape(cls.out)) fail(`produces to ${JSON.stringify(cls.producesTo)} and needs a static out: the shape of what it produces`)
  if (!ok() || !source) return undefined
  return Object.freeze({
    kind: "consumer",
    id: cls.componentId,
    cls,
    source,
    messageCodec: codecFor(cls.message),
    outCodec: cls.out ? codecFor(cls.out) : undefined,
    producesTo: cls.producesTo,
  })
}

function registerTimedAction(cls: TimedActionClass<any>, problems: string[]): RegisteredTimedAction | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  const actions = collectHandlers(cls.actions, "actions", ["action"], fail)
  if (!ok()) return undefined
  return Object.freeze({ kind: "timed-action", id: cls.componentId, cls, actions })
}

function registerAgent(cls: AgentClass<any>, problems: string[]): RegisteredAgent | undefined {
  const { fail, ok } = checker(cls, problems)
  requireId(cls, fail)
  const handlers = collectHandlers(cls.handlers, "handlers", ["command", "stream"], fail)
  const tools = new Map<string, ToolRef<any, any>>()
  for (const [property, t] of Object.entries(cls.tools ?? {})) {
    if (typeof t !== "object" || t === null || typeof t.name !== "string" || typeof t.run !== "function") {
      fail(`tools.${property} is not a tool: declare it with tool(name, description, input, run)`)
      continue
    }
    if (typeof t.description !== "string" || t.description.trim() === "") fail(`tool ${JSON.stringify(t.name)} needs a description; the model decides by it`)
    if (tools.has(t.name)) fail(`two tools declare the name ${JSON.stringify(t.name)}`)
    tools.set(t.name, t)
  }
  const guardrails = new Map<string, GuardrailRef>()
  for (const [property, g] of Object.entries(cls.guardrails ?? {})) {
    if (typeof g !== "object" || g === null || typeof g.name !== "string" || typeof g.check !== "function") {
      fail(`guardrails.${property} is not a guardrail: declare it with guardrail(name, check)`)
      continue
    }
    if (guardrails.has(g.name)) fail(`two guardrails declare the name ${JSON.stringify(g.name)}`)
    guardrails.set(g.name, g)
  }
  const maxToolCallSteps = cls.maxToolCallSteps ?? 100
  if (!Number.isInteger(maxToolCallSteps) || maxToolCallSteps <= 0) fail("maxToolCallSteps must be a positive integer")
  if (!ok()) return undefined
  return Object.freeze({ kind: "agent", id: cls.componentId, cls, handlers, tools, guardrails, role: cls.role ?? "", maxToolCallSteps })
}

/** Collects a handler table by wire name, reporting a duplicate wire name or a kind the component cannot host. */
export function collectHandlers(
  table: unknown,
  what: string,
  allowed: readonly HandlerRef["kind"][],
  fail: Fail,
): ReadonlyMap<string, HandlerRef<any, any, any, any>> {
  const out = new Map<string, HandlerRef<any, any, any, any>>()
  if (typeof table !== "object" || table === null) {
    fail(`needs a static ${what} table`)
    return out
  }
  for (const [property, ref] of Object.entries(table as Record<string, unknown>)) {
    if (!isHandlerRef(ref)) {
      fail(`${what}.${property} is not a handler: declare it with command(...), query(...), step(...), action(...) or stream(...)`)
      continue
    }
    if (!allowed.includes(ref.kind)) {
      fail(`${what}.${property} is a ${ref.kind}, which does not belong in ${what}`)
      continue
    }
    if (out.has(ref.name)) {
      fail(`two ${what} declare the wire name ${JSON.stringify(ref.name)}`)
      continue
    }
    out.set(ref.name, ref)
  }
  return out
}

function isHandlerRef(x: unknown): x is HandlerRef<any, any, any, any> {
  return typeof x === "object" && x !== null && typeof (x as HandlerRef).name === "string" && typeof (x as HandlerRef).run === "function" && typeof (x as HandlerRef).kind === "string"
}

function registerEndpoint(cls: EndpointClass<any>, problems: string[]): RegisteredEndpoint | undefined {
  const { fail, ok } = checker(cls, problems)
  const name = nameOf(cls)
  if (typeof cls.prefix !== "string" || !cls.prefix.startsWith("/") || (cls.prefix.length > 1 && cls.prefix.endsWith("/"))) {
    fail(`needs a static prefix starting with "/" and not ending with one, not ${JSON.stringify(cls.prefix)}`)
  }
  if (!Object.values(AclValues).includes(cls.acl as Acl)) {
    fail(`needs a static acl — Acl.allowAll, Acl.denyAll or Acl.authenticated. An unstated access rule is a decision nobody made`)
  }
  const routes = new Map<string, RouteRef<any, any, any, any>>()
  const seen = new Map<string, string>()
  if (typeof cls.routes !== "object" || cls.routes === null) {
    fail("needs a static routes table")
  } else {
    for (const [id, route] of Object.entries(cls.routes)) {
      if (typeof route !== "object" || route === null || typeof (route as RouteRef).template !== "string" || typeof (route as RouteRef).run !== "function") {
        fail(`routes.${id} is not a route: declare it with get(...), post(...), put(...), patch(...), del(...) or sse(...)`)
        continue
      }
      const r = route as RouteRef<any, any, any, any>
      const key = `${r.method} ${r.template}`
      const other = seen.get(key)
      if (other) fail(`routes ${other} and ${id} both declare ${key}`)
      seen.set(key, id)
      if (r.acl !== undefined && !Object.values(AclValues).includes(r.acl)) fail(`routes.${id}: unknown acl ${JSON.stringify(r.acl)}`)
      routes.set(id, r)
    }
  }
  if (!ok()) return undefined
  return Object.freeze({ id: name, cls, prefix: cls.prefix, acl: cls.acl, routes })
}

/** The entry point: `Ankka.service().register(...).listen()`. */
export const Ankka = Object.freeze({
  service(options?: ServiceOptions): ServiceBuilder {
    return new ServiceBuilder(options)
  },
})
