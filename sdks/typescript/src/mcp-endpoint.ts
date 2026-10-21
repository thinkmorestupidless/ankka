// An MCP endpoint (protocol 1.20): a path, an access rule and a table of tools, which the platform
// serves to any MCP client over Streamable HTTP. The platform answers the protocol, verifies the
// credential (a client key or a token), checks the arguments against each tool's schema and asks this
// process to run the tool; the process never sees the credential, only the principal it stood for.
//
//   export class CartMcp extends McpEndpoint {
//     static readonly path = "/mcp"
//     static readonly acl = Acl.anyOf(Acl.clientKeys, Acl.authenticated)
//     static readonly tools = tools<CartMcp>()
//       .tool("get_cart", "Reads one cart", s.record("GetCart", { cartId: s.string }), Cart,
//             (args, ep) => ep.client.of(ShoppingCart, args.cartId).call(ShoppingCart.handlers.getCart).invoke())
//       .command("checkout", "Checks a cart out", ShoppingCart, "checkout", { id: "cartId" })
//   }
//
// A tool is either run by this process's own function, or a reference to a component's handler or to
// a route of one of this service's endpoints. References are resolved at registration, so one that
// names something not registered stops the service before it starts.

import { codecFor, isCodec, type Codec, type Shape } from "./codec.ts"
import type { ComponentRef } from "./client.ts"
import { Endpoint } from "./endpoint.ts"
import type { HandlerRef } from "./handlers.ts"
import { isAcl, parseTemplate, type Acl, type HttpMethod, type RouteRef } from "./routes.ts"
import { resolve, toJsonSchema, type Schema } from "./schema.ts"
import type { ComponentKind } from "./kinds.ts"
import type { EndpointClass } from "./endpoint.ts"
import type { RegisteredComponent, RegisteredEndpoint } from "./service.ts"

type MaybePromise<T> = T | Promise<T>

/** The first protocol whose runtime serves a process's MCP endpoint. */
export const MCP_ENDPOINTS_SINCE = "1.20"

/** What a tool name may be: what MCP clients accept everywhere. */
export const TOOL_NAME = /^[a-z0-9_-]{1,128}$/

/**
 * The base of an MCP endpoint. One instance serves every call, so the call's principal and caller are
 * on `this.request`, as for an HTTP endpoint; `client`, `secrets`, `services` and `clientKeys` likewise.
 */
export abstract class McpEndpoint extends Endpoint {}

/** One tool as declared, before its references are resolved. */
export type DeclaredTool<Ep> =
  | {
      readonly kind: "tool"
      readonly name: string
      readonly description: string
      readonly input: Shape<any>
      readonly reply: Shape<any> | undefined
      readonly run: (args: any, self: Ep) => MaybePromise<unknown>
      readonly acl: Acl | undefined
    }
  | {
      readonly kind: "command" | "query"
      readonly name: string
      readonly description: string
      readonly component: ComponentRef
      readonly handler: string
      /** The argument holding the entity's or workflow's id. */
      readonly id: string
      /** The argument holding the handler's input; absent merges a record input's fields beside the id. */
      readonly input: string | undefined
      readonly acl: Acl | undefined
    }
  | {
      readonly kind: "route"
      readonly name: string
      readonly description: string
      readonly endpoint: EndpointClass<any>
      readonly method: HttpMethod
      /** The full path: the endpoint's prefix and the route's template, `/orders/{day}`. */
      readonly path: string
      readonly acl: Acl | undefined
    }

export interface ToolOptions {
  /** Who may call this tool, beside the endpoint's rule; client keys and `anyOf` are allowed. */
  readonly acl?: Acl
}

export interface ReferenceOptions extends ToolOptions {
  /** The argument that names the entity or workflow. */
  readonly id: string
  /** The argument holding the handler's input, when it is not to be merged beside the id. */
  readonly input?: string
}

/** The tools of an MCP endpoint, built as `tools<CartMcp>().tool(...).command(...)`. */
export class ToolTable<Ep = McpEndpoint> {
  readonly declared: readonly DeclaredTool<Ep>[]

  constructor(declared: readonly DeclaredTool<Ep>[] = []) {
    this.declared = Object.freeze([...declared])
    Object.freeze(this)
  }

  #with(tool: DeclaredTool<Ep>): ToolTable<Ep> {
    return new ToolTable([...this.declared, Object.freeze(tool)])
  }

  /**
   * A tool this process runs: its arguments decoded with `input` (a record schema becomes the tool's
   * object schema), its answer encoded with `reply` (a string answers as text; an object also as
   * structured content). A thrown error or a refusal is the tool error the client is shown.
   */
  tool<I, R>(name: string, description: string, input: Shape<I>, reply: Shape<R> | undefined, run: (args: I, self: Ep) => MaybePromise<R>, options?: ToolOptions): ToolTable<Ep> {
    return this.#with({ kind: "tool", name, description, input, reply, run: run as (args: any, self: Ep) => MaybePromise<unknown>, acl: options?.acl })
  }

  /** A tool that runs a command of a component, by its wire name: `command("checkout", "...", ShoppingCart, "checkout", { id: "cartId" })`. */
  command(name: string, description: string, component: ComponentRef, handler: string | HandlerRef<any, any, any, any>, options: ReferenceOptions): ToolTable<Ep> {
    return this.#reference("command", name, description, component, handler, options)
  }

  /** A tool that runs a query of a component; it cannot change anything. */
  query(name: string, description: string, component: ComponentRef, handler: string | HandlerRef<any, any, any, any>, options: ReferenceOptions): ToolTable<Ep> {
    return this.#reference("query", name, description, component, handler, options)
  }

  /** A tool that calls a route of one of this service's endpoints, in process: its path names are the arguments, and `body` the body. */
  route(name: string, description: string, endpoint: EndpointClass<any>, method: HttpMethod, path: string, options?: ToolOptions): ToolTable<Ep> {
    return this.#with({ kind: "route", name, description, endpoint, method, path, acl: options?.acl })
  }

  #reference(kind: "command" | "query", name: string, description: string, component: ComponentRef, handler: string | HandlerRef<any, any, any, any>, options: ReferenceOptions): ToolTable<Ep> {
    const wire = typeof handler === "string" ? handler : handler?.name
    return this.#with({ kind, name, description, component, handler: wire, id: options?.id, input: options?.input, acl: options?.acl })
  }
}

/** An empty tool table to build on. */
export function tools<Ep = McpEndpoint>(): ToolTable<Ep> {
  return new ToolTable<Ep>()
}

/** The statics an MCP endpoint class declares; `register` constrains on this type. */
export interface McpEndpointClass<C extends McpEndpoint = McpEndpoint> {
  new (): C
  /** `/mcp`: where the endpoint is served, on the service's own hostnames. */
  readonly path: string
  /** Who may connect: `Acl.clientKeys`, `Acl.authenticated`, `Acl.anyOf(...)`. There is no default. */
  readonly acl: Acl
  readonly tools: ToolTable<C>
  /** The platform signs people in for clients that cannot hold a token (needs `authenticated` in the rule). */
  readonly brokered?: boolean
  /** The server's name in `initialize`; the class name when absent. */
  readonly serverName?: string
  readonly serverVersion?: string
}

/** How a resolved tool is run; data the server interprets. */
export type ToolRun =
  | { readonly kind: "function"; readonly input: Shape<any>; readonly reply: Shape<any> | undefined; readonly run: (args: any, self: McpEndpoint) => MaybePromise<unknown> }
  | {
      readonly kind: "handler"
      readonly componentKind: ComponentKind
      readonly componentId: string
      readonly handler: HandlerRef<any, any, any, any>
      readonly id: string
      /** `merge`: a record input's fields sit beside the id; a name: the input is that argument; absent: there is none. */
      readonly input: { readonly kind: "merge" } | { readonly kind: "argument"; readonly name: string } | undefined
    }
  | { readonly kind: "route"; readonly endpointId: string; readonly route: RouteRef<any, any, any, any>; readonly paramNames: readonly string[] }

export interface RegisteredMcpTool {
  readonly name: string
  readonly description: string
  /** The JSON Schema of the arguments, which the platform checks before the process is asked. */
  readonly inputSchema: Record<string, unknown>
  readonly acl: Acl | undefined
  readonly run: ToolRun
}

export interface RegisteredMcpEndpoint {
  readonly id: string
  readonly cls: McpEndpointClass<any>
  readonly path: string
  readonly acl: Acl
  readonly tools: readonly RegisteredMcpTool[]
  readonly brokered: boolean
  readonly serverName: string | undefined
  readonly serverVersion: string | undefined
}

function hasTokens(acl: Acl): boolean {
  if (acl === "authenticated") return true
  return typeof acl === "object" && acl.kind === "anyOf" && acl.acls.some(hasTokens)
}

function objectSchema(properties: Record<string, unknown>, required: string[]): Record<string, unknown> {
  return { type: "object", properties, required, additionalProperties: false }
}

/** The JSON Schema of a tool's own input: an object schema, whatever the shape (a codec admits any object). */
function inputSchemaOf(input: Shape<any>): Record<string, unknown> | string {
  if (isCodec(input)) return { type: "object" }
  const schema = toJsonSchema(input as Schema)
  if (schema.type !== "object") return `its input must be a record, so its arguments are an object; it is ${String(resolve(input as Schema).kind)}`
  return schema
}

/**
 * Resolves an MCP endpoint class against the registered components and endpoints, collecting every
 * problem. A reference to a component or route that is not registered is refused here, before start.
 */
export function registerMcpEndpoint(
  cls: McpEndpointClass<any>,
  components: ReadonlyMap<string, RegisteredComponent>,
  endpoints: ReadonlyMap<string, RegisteredEndpoint>,
  problems: string[],
): RegisteredMcpEndpoint | undefined {
  const name = typeof cls === "function" && cls.name ? cls.name : String(cls)
  let ok = true
  const fail = (m: string) => {
    problems.push(`${name}: ${m}`)
    ok = false
  }
  const path = cls.path
  if (typeof path !== "string" || !path.startsWith("/") || (path.length > 1 && path.endsWith("/")) || path.includes("{")) {
    fail(`needs a static path starting with "/", with no parameters and not ending with "/", not ${JSON.stringify(path)}`)
  } else if (path.startsWith("/_ankka/")) fail(`path ${path} is the platform's`)
  if (!isAcl(cls.acl)) fail("needs a static acl — Acl.clientKeys, Acl.authenticated, Acl.anyOf(...) or another rule. An unstated access rule is a decision nobody made")
  if (cls.brokered !== undefined && typeof cls.brokered !== "boolean") fail("brokered must be true or false")
  if (cls.brokered === true && isAcl(cls.acl) && !hasTokens(cls.acl)) fail("is brokered, which signs people in with the service's issuer; its acl must admit Acl.authenticated")
  for (const [what, value] of [["serverName", cls.serverName], ["serverVersion", cls.serverVersion]] as const) {
    if (value !== undefined && (typeof value !== "string" || value.trim() === "")) fail(`${what} must be a non-empty string`)
  }
  const table = cls.tools
  if (!(table instanceof ToolTable)) {
    fail("needs a static tools table: tools().tool(...)")
    return undefined
  }
  if (table.declared.length === 0) fail("declares no tool; an MCP endpoint needs at least one")

  const resolved: RegisteredMcpTool[] = []
  const seen = new Set<string>()
  for (const t of table.declared) {
    const where = `tool '${t.name}'`
    if (typeof t.name !== "string" || !TOOL_NAME.test(t.name)) {
      fail(`tool '${String(t.name)}' is not a tool name ([a-z0-9_-]+, at most 128 characters)`)
      continue
    }
    if (seen.has(t.name)) fail(`declares the tool '${t.name}' twice`)
    seen.add(t.name)
    if (typeof t.description !== "string" || t.description.trim() === "") fail(`${where} needs a description; it is what a model reads to choose it`)
    if (t.acl !== undefined && !isAcl(t.acl)) fail(`${where}: unknown acl ${JSON.stringify(t.acl)}`)
    const tool = resolveTool(t, where, components, endpoints, fail)
    if (tool) resolved.push(tool)
  }
  if (!ok) return undefined
  return Object.freeze({
    id: name,
    cls,
    path,
    acl: cls.acl,
    tools: Object.freeze(resolved),
    brokered: cls.brokered === true,
    serverName: cls.serverName,
    serverVersion: cls.serverVersion,
  })
}

function resolveTool(
  t: DeclaredTool<any>,
  where: string,
  components: ReadonlyMap<string, RegisteredComponent>,
  endpoints: ReadonlyMap<string, RegisteredEndpoint>,
  fail: (m: string) => void,
): RegisteredMcpTool | undefined {
  const base = { name: t.name, description: t.description, acl: t.acl }
  switch (t.kind) {
    case "tool": {
      if (typeof t.run !== "function") {
        fail(`${where}: the handler is not a function`)
        return undefined
      }
      const schema = typeof t.input === "object" && t.input !== null ? inputSchemaOf(t.input) : "it needs an input shape"
      if (typeof schema === "string") {
        fail(`${where}: ${schema}`)
        return undefined
      }
      return Object.freeze({ ...base, inputSchema: schema, run: Object.freeze({ kind: "function", input: t.input, reply: t.reply, run: t.run }) })
    }
    case "command":
    case "query": {
      const componentId = t.component?.componentId
      const registered = typeof componentId === "string" ? components.get(componentId) : undefined
      if (!registered || registered.cls !== (t.component as unknown)) {
        fail(`${where} refers to the component ${typeof componentId === "string" ? componentId : String(t.component)}, which is not registered`)
        return undefined
      }
      if (registered.kind !== "event-sourced" && registered.kind !== "key-value" && registered.kind !== "workflow") {
        fail(`${where} refers to ${registered.id}, a ${registered.kind}; a reference names an entity's or a workflow's handler`)
        return undefined
      }
      const handler = registered.handlers.get(t.handler)
      if (!handler) {
        fail(`${where} refers to the handler '${String(t.handler)}' of ${registered.id}, which it does not declare`)
        return undefined
      }
      if (t.kind === "query" && !handler.readOnly) fail(`${where} is a query of '${handler.name}', which is a command`)
      if (t.kind === "command" && handler.readOnly) fail(`${where} is a command of '${handler.name}', which is a query; declare it with query(...)`)
      if (handler.streaming) fail(`${where} refers to '${handler.name}', which streams; a tool answers once`)
      if (typeof t.id !== "string" || t.id.trim() === "") {
        fail(`${where} needs the argument that names the ${registered.kind === "workflow" ? "workflow" : "entity"}: { id: "cartId" }`)
        return undefined
      }
      const properties: Record<string, unknown> = { [t.id]: { type: "string", description: `The id of the ${registered.id}` } }
      const required = [t.id]
      let input: Extract<ToolRun, { kind: "handler" }>["input"]
      if (handler.input !== undefined) {
        const shape = handler.input as Shape<unknown>
        const json = isCodec(shape) ? { type: "object" } : toJsonSchema(shape as Schema)
        if (t.input !== undefined || json.type !== "object" || isCodec(shape)) {
          const argument = t.input ?? "input"
          if (argument === t.id) fail(`${where}: the id and the input are both the argument '${argument}'`)
          properties[argument] = json
          required.push(argument)
          input = { kind: "argument", name: argument }
        } else {
          const fields = (json.properties ?? {}) as Record<string, unknown>
          if (t.id in fields) fail(`${where}: the input has a field '${t.id}', which is the id's argument; name the input with { input: "..." }`)
          Object.assign(properties, fields)
          required.push(...((json.required ?? []) as string[]))
          input = { kind: "merge" }
        }
      } else if (t.input !== undefined) fail(`${where} names an input argument, but '${handler.name}' takes no input`)
      return Object.freeze({
        ...base,
        inputSchema: objectSchema(properties, required),
        run: Object.freeze({ kind: "handler", componentKind: registered.kind, componentId: registered.id, handler, id: t.id, input }),
      })
    }
    case "route": {
      const endpoint = [...endpoints.values()].find((e) => e.cls === t.endpoint)
      if (!endpoint) {
        fail(`${where} refers to the endpoint ${typeof t.endpoint === "function" ? t.endpoint.name : String(t.endpoint)}, which is not registered`)
        return undefined
      }
      const relative = typeof t.path === "string" && t.path.startsWith(endpoint.prefix) ? t.path.slice(endpoint.prefix === "/" ? 0 : endpoint.prefix.length) || "/" : undefined
      const route = relative === undefined ? undefined : [...endpoint.routes.values()].find((r) => r.method === t.method && r.template === relative)
      if (!route) {
        fail(`${where} refers to ${t.method} ${t.path}, which ${endpoint.id} does not declare`)
        return undefined
      }
      if (route.streaming || route.socket) {
        fail(`${where} refers to ${t.method} ${t.path}, which streams; a tool answers once`)
        return undefined
      }
      const paramNames = parseTemplate(route.template)
      const properties: Record<string, unknown> = Object.fromEntries(paramNames.map((n) => [n, { type: "string" }]))
      if (route.body !== undefined) properties.body = { type: "object" }
      return Object.freeze({ ...base, inputSchema: objectSchema(properties, [...paramNames]), run: Object.freeze({ kind: "route", endpointId: endpoint.id, route, paramNames }) })
    }
  }
}

/** The codec a tool's own reply is encoded with, when it declares one. */
export function replyCodecOf(reply: Shape<any> | undefined): Codec<unknown> | undefined {
  return reply === undefined ? undefined : (codecFor(reply) as Codec<unknown>)
}
