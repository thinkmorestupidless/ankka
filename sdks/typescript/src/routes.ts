// Route declarations for an endpoint's static `routes` table, and the access rules. A route names its
// template, its body and reply shapes and the function that runs it; the sidecar serves the route,
// applies the access rule and forwards the request, and the process only runs the handler.
//
//   static readonly routes = {
//     addItem: post("/{cartId}/items", LineItem, Done, (ep: CartEndpoint, req, item) => ep.addItem(req.params.cartId, item)),
//     getCart: get("/{cartId}", ShoppingCart, (ep: CartEndpoint, req) => ep.getCart(req.params.cartId)),
//   }
//
// `req.params` is typed from the template: "/{cartId}/items" gives { cartId: string }.

import type { Socket } from "./socket.ts"
import type { Shape } from "./codec.ts"
import type { RequestContext } from "./context.ts"
import { isCodec } from "./codec.ts"
import type { Infer, Schema } from "./schema.ts"

/** One kind of caller an `Acl.allowCallers` admits; build one through `Callers`. */
export type CallerMatcher =
  | { readonly kind: "internet" }
  | { readonly kind: "service"; readonly name: string; readonly project?: string }
  | { readonly kind: "anyInProject" }
  | { readonly kind: "self" }

/**
 * The callers an endpoint can name: `Acl.allowCallers(Callers.internet, Callers.service("orders"))`. A named
 * service is in this service's own project unless `project` names another.
 */
export const Callers = Object.freeze({
  internet: Object.freeze({ kind: "internet" }) as CallerMatcher,
  anyInProject: Object.freeze({ kind: "anyInProject" }) as CallerMatcher,
  self: Object.freeze({ kind: "self" }) as CallerMatcher,
  service(name: string, options?: { readonly project?: string }): CallerMatcher {
    return Object.freeze(options?.project !== undefined ? { kind: "service", name, project: options.project } : { kind: "service", name })
  },
})

/** Only the callers named. In a cluster the caller is read from the certificate the platform issued. */
export interface CallersAcl {
  readonly kind: "callers"
  readonly callers: readonly CallerMatcher[]
}

/**
 * Who may call: everyone, no one, authenticated callers only, or only the callers named. There is no default.
 *
 * Outside a cluster every caller is `{ kind: "local" }`, and `allowCallers` admits it.
 */
export type Acl = "allow-all" | "deny-all" | "authenticated" | CallersAcl
export const Acl = Object.freeze({
  allowAll: "allow-all" as Acl,
  denyAll: "deny-all" as Acl,
  authenticated: "authenticated" as Acl,
  allowCallers(first: CallerMatcher, ...rest: CallerMatcher[]): Acl {
    return Object.freeze({ kind: "callers", callers: Object.freeze([first, ...rest]) })
  },
})

/** Whether `x` is an access rule: one of the three words, or a non-empty callers rule. */
export function isAcl(x: unknown): x is Acl {
  if (x === "allow-all" || x === "deny-all" || x === "authenticated") return true
  if (typeof x !== "object" || x === null) return false
  const rule = x as Partial<CallersAcl>
  return rule.kind === "callers" && Array.isArray(rule.callers) && rule.callers.length > 0
}

/** Thrown from a route handler to answer a status other than 200 with a plain-text message. */
export class HttpProblem extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = "HttpProblem"
    this.status = status
  }
}

export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE" | "PATCH"

type MaybePromise<T> = T | Promise<T>

/** The parameter names in a template: `ParamNames<"/{cartId}/items/{productId}">` is `"cartId" | "productId"`. */
export type ParamNames<T extends string> = T extends `${string}{${infer P}}${infer Rest}` ? P | ParamNames<Rest> : never

/** The typed parameters of a template, each a `string` unless `params` narrows it with a schema. */
export type Params<T extends string, PS> = { readonly [K in ParamNames<T>]: K extends keyof PS ? Infer<PS[K]> : string }

/** Options on a route: an access rule for this route alone, and schemas narrowing path parameters. */
export interface RouteOptions<PS extends Readonly<Record<string, Schema>> = {}> {
  readonly acl?: Acl
  readonly params?: PS
}

/** One entry of an endpoint's `routes` table. */
export interface RouteRef<Ep = unknown, P = unknown, B = unknown, R = unknown> {
  readonly method: HttpMethod
  /** As declared: `/{cartId}/items`, relative to the endpoint's prefix. */
  readonly template: string
  /** Parameter names in template order. */
  readonly paramNames: readonly string[]
  /** Schemas narrowing parameters; a parameter not named here is a string. */
  readonly paramShapes: Readonly<Record<string, Schema>>
  readonly body: Shape<B> | undefined
  readonly reply: Shape<R> | undefined
  readonly streaming: boolean
  /** Answered by opening a socket (protocol 1.9); `run`'s third argument is then the `Socket`. */
  readonly socket: boolean
  readonly acl: Acl | undefined
  readonly run: (self: Ep, request: RequestContext<P>, body: B) => MaybePromise<R> | AsyncIterable<string>
}

const TEMPLATE = /^(\/(?:[^/{}]+|\{[A-Za-z_][A-Za-z0-9_]*\})?)*$/

export function parseTemplate(template: string): string[] {
  if (typeof template !== "string" || !template.startsWith("/") || !TEMPLATE.test(template) || template.includes("//")) {
    throw new TypeError(`not a route template: ${JSON.stringify(template)} (segments are literals or {name})`)
  }
  const names = [...template.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)].map((m) => m[1]!)
  if (new Set(names).size !== names.length) throw new TypeError(`route template ${template} repeats a parameter`)
  return names
}

function route<Ep, P, B, R>(
  method: HttpMethod,
  template: string,
  body: Shape<B> | undefined,
  reply: Shape<R> | undefined,
  run: RouteRef<Ep, P, B, R>["run"],
  options: RouteOptions<Readonly<Record<string, Schema>>> | undefined,
  streaming: boolean,
  socket = false,
): RouteRef<Ep, P, B, R> {
  const paramNames = parseTemplate(template)
  if (typeof run !== "function") throw new TypeError(`route ${method} ${template}: the handler is not a function`)
  if (method === "GET" && body !== undefined) throw new TypeError(`route GET ${template} cannot have a body`)
  const paramShapes = options?.params ?? {}
  for (const name of Object.keys(paramShapes)) {
    if (!paramNames.includes(name)) throw new TypeError(`route ${method} ${template}: params names ${name}, which is not in the template`)
  }
  return Object.freeze({ method, template, paramNames, paramShapes, body, reply, streaming, socket, acl: options?.acl, run })
}

function isShape(x: unknown): x is Shape<unknown> {
  return typeof x === "object" && x !== null && (typeof (x as { kind?: unknown }).kind === "string" || isCodec(x as Shape<unknown>))
}

type NoBodyRun<Ep, T extends string, PS, R> = (self: Ep, request: RequestContext<Params<T, PS>>) => MaybePromise<R>
type BodyRun<Ep, T extends string, PS, B, R> = (self: Ep, request: RequestContext<Params<T, PS>>, body: B) => MaybePromise<R>

/** A GET route: `get("/{cartId}", ShoppingCart, (ep: CartEndpoint, req) => ep.getCart(req.params.cartId))`. */
export function get<Ep, T extends string, R, PS extends Readonly<Record<string, Schema>> = {}>(
  template: T,
  reply: Shape<R>,
  run: NoBodyRun<Ep, T, PS, R>,
  options?: RouteOptions<PS>,
): RouteRef<Ep, Params<T, PS>, undefined, R> {
  return route("GET", template, undefined, reply, run as RouteRef<Ep, Params<T, PS>, undefined, R>["run"], options, false)
}

function withBody(method: HttpMethod) {
  function declare<Ep, T extends string, B, R, PS extends Readonly<Record<string, Schema>> = {}>(
    template: T,
    body: Shape<B>,
    reply: Shape<R>,
    run: BodyRun<Ep, T, PS, B, R>,
    options?: RouteOptions<PS>,
  ): RouteRef<Ep, Params<T, PS>, B, R>
  function declare<Ep, T extends string, R, PS extends Readonly<Record<string, Schema>> = {}>(
    template: T,
    reply: Shape<R>,
    run: NoBodyRun<Ep, T, PS, R>,
    options?: RouteOptions<PS>,
  ): RouteRef<Ep, Params<T, PS>, undefined, R>
  function declare(template: string, ...rest: unknown[]): RouteRef<any, any, any, any> {
    if (typeof rest[1] === "function") {
      const [reply, run, options] = rest as [Shape<unknown>, RouteRef["run"], RouteOptions | undefined]
      return route(method, template, undefined, reply, run, options, false)
    }
    const [body, reply, run, options] = rest as [Shape<unknown>, Shape<unknown>, RouteRef["run"], RouteOptions | undefined]
    if (!isShape(body)) throw new TypeError(`route ${method} ${template}: expected a body shape`)
    return route(method, template, body, reply, run, options, false)
  }
  return declare
}

/** A POST route, with a body (`post(template, Body, Reply, run)`) or without (`post(template, Reply, run)`). */
export const post = withBody("POST")
export const put = withBody("PUT")
export const patch = withBody("PATCH")
/** A DELETE route; `delete` is a reserved word, so `del`. */
export const del = withBody("DELETE")

/** A server-sent-events route: the handler returns an `AsyncIterable<string>`, one frame per string. */
export function sse<Ep, T extends string, PS extends Readonly<Record<string, Schema>> = {}>(
  template: T,
  run: (self: Ep, request: RequestContext<Params<T, PS>>) => AsyncIterable<string> | Promise<AsyncIterable<string>>,
  options?: RouteOptions<PS>,
): RouteRef<Ep, Params<T, PS>, undefined, never> {
  return route("GET", template, undefined, undefined, run as RouteRef<Ep, Params<T, PS>, undefined, never>["run"], options, true)
}

/**
 * A socket route: `socket("/{room}", async (ep: Rooms, req, socket) => { for await (const t of socket) await socket.send(t) })`.
 * The handler runs for as long as the socket is open, with the opening request as `req` throughout.
 * Needs protocol 1.9 of the runtime.
 */
export function socket<Ep, T extends string, PS extends Readonly<Record<string, Schema>> = {}>(
  template: T,
  run: (self: Ep, request: RequestContext<Params<T, PS>>, socket: Socket) => Promise<void>,
  options?: RouteOptions<PS>,
): RouteRef<Ep, Params<T, PS>, Socket, void> {
  return route("GET", template, undefined, undefined, run as RouteRef<Ep, Params<T, PS>, Socket, void>["run"], options, false, true)
}

/** The routes table type an endpoint declares. */
export type RouteTable<Ep> = Readonly<Record<string, RouteRef<Ep, any, any, any>>>
