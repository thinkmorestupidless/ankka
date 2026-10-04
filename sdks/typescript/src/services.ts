// Other services, called as this one. The runtime beside this process makes the call: it finds the
// service, presents this service's certificate and checks that what answered is the service asked
// for. This process never holds a key. Offered to endpoints, workflow steps, consumers, timed actions
// and agents; an entity and a view have no `services` at all.

import { Code, ConnectError } from "@connectrpc/connect"
import { ServiceFailure_Reason, type ServiceReply } from "./_proto/ankka/protocol/v1/client_pb.ts"
import { channelOf, type ComponentClient } from "./client.ts"
import { codecFor, type Shape } from "./codec.ts"
import { CommandError } from "./effects/common.ts"
import { errorCodeFromProto } from "./kinds.ts"
import { metadataToProto } from "./context.ts"
import { PROTOCOL_VERSION } from "./spec.ts"

/** The protocol that added calls to other services. */
export const SERVICES_SINCE = "1.7"

/** The largest body, either way, a call through the sidecar carries: under gRPC's 4 MiB. */
export const MAX_BODY_BYTES = 4_000_000

/** Headers, in order; a name may appear twice. The platform's own are removed before sending. */
export type ServiceHeaders = ReadonlyArray<readonly [string, string]>

/** What the service answered: its status, content type, body and headers, as it sent them. */
export interface ServiceResponse {
  readonly status: number
  readonly contentType: string
  readonly body: Uint8Array
  readonly headers: ServiceHeaders
  readonly text: string
}

/** A call to another service that did not end in an answer a typed helper accepts. */
export class ServiceError extends Error {
  readonly service: string
  constructor(service: string, message: string) {
    super(message)
    this.name = "ServiceError"
    this.service = service
  }
}

/** No such service was found; nothing was sent. `reason` says what was tried. */
export class ServiceUnresolvable extends ServiceError {
  readonly reason: string
  constructor(service: string, reason: string) {
    super(service, `cannot reach ${service}: ${reason}`)
    this.name = "ServiceUnresolvable"
    this.reason = reason
  }
}

/** What answered for the name is not the service asked for; nothing was sent. */
export class ServiceIdentityMismatch extends ServiceError {
  readonly detail: string
  constructor(service: string, detail: string) {
    super(service, `the service reached as ${service} is not it: ${detail}`)
    this.name = "ServiceIdentityMismatch"
    this.detail = detail
  }
}

/**
 * No answer came: the connection was refused or broke, or the service's timeout passed. The service
 * may have received the request; one that may change something was sent at most once.
 */
export class ServiceUnanswered extends ServiceError {
  readonly reason: string
  constructor(service: string, reason: string) {
    super(service, `${service} did not answer: ${reason}`)
    this.name = "ServiceUnanswered"
    this.reason = reason
  }
}

/** The service answered with a status outside 2xx, to a typed helper. `request` returns every answer. */
export class ServiceCallFailed extends ServiceError {
  readonly status: number
  readonly body: string
  constructor(service: string, status: number, body: string) {
    super(service, `${service} answered ${status}: ${body}`)
    this.name = "ServiceCallFailed"
    this.status = status
    this.body = body
  }
}

/** One request, as a client sends it. */
export interface ServiceRequest {
  readonly service: string
  readonly project: string | undefined
  readonly method: string
  readonly path: string
  readonly headers: ServiceHeaders
  readonly contentType: string | undefined
  readonly body: Uint8Array | undefined
}

type Send = (request: ServiceRequest, target: string) => Promise<ServiceResponse>

const decoder = new TextDecoder()

function response(status: number, contentType: string, body: Uint8Array, headers: ServiceHeaders): ServiceResponse {
  return { status, contentType, body, headers, get text() { return decoder.decode(body) } }
}

/** Calls one service. `target` is the service as it was named: `name` or `project/name`. */
export class ServiceClient {
  readonly target: string
  readonly #send: Send
  readonly #service: string
  readonly #project: string | undefined

  /** @internal */
  constructor(send: Send, service: string, project: string | undefined) {
    this.#send = send
    this.#service = service
    this.#project = project
    this.target = project ? `${project}/${service}` : service
  }

  /** One request, answered whatever its status. Throws only when no answer came. */
  async request(method: string, path: string, options: { body?: Uint8Array; contentType?: string; headers?: ServiceHeaders } = {}): Promise<ServiceResponse> {
    if (options.body && options.body.length > MAX_BODY_BYTES) {
      throw new CommandError({
        message: `a call's body is at most ${MAX_BODY_BYTES} bytes through the sidecar; this one is ${options.body.length}`,
        code: "BAD_REQUEST",
      })
    }
    return this.#send(
      {
        service: this.#service,
        project: this.#project,
        method,
        path,
        headers: options.headers ?? [],
        contentType: options.contentType,
        body: options.body,
      },
      this.target,
    )
  }

  /** A JSON answer, decoded as `returns`; a status outside 2xx is a `ServiceCallFailed`. */
  async get<R>(path: string, returns: Shape<R>, options: { headers?: ServiceHeaders } = {}): Promise<R> {
    return codecFor(returns).decode(this.#succeeded(await this.request("GET", path, options)).body)
  }

  /** A text answer: what an endpoint returning a string sends. */
  async getText(path: string, options: { headers?: ServiceHeaders } = {}): Promise<string> {
    return this.#succeeded(await this.request("GET", path, options)).text
  }

  async post<B, R>(path: string, body: B, shapes: { body: Shape<B>; returns: Shape<R> }, options: { headers?: ServiceHeaders } = {}): Promise<R> {
    return this.#withBody("POST", path, body, shapes, options)
  }

  async put<B, R>(path: string, body: B, shapes: { body: Shape<B>; returns: Shape<R> }, options: { headers?: ServiceHeaders } = {}): Promise<R> {
    return this.#withBody("PUT", path, body, shapes, options)
  }

  /** For a route answering nothing: succeeds on any 2xx. */
  async delete(path: string, options: { headers?: ServiceHeaders } = {}): Promise<void> {
    this.#succeeded(await this.request("DELETE", path, options))
  }

  async #withBody<B, R>(method: string, path: string, body: B, shapes: { body: Shape<B>; returns: Shape<R> }, options: { headers?: ServiceHeaders }): Promise<R> {
    const codec = codecFor(shapes.body)
    const answer = await this.request(method, path, { body: codec.encode(body), contentType: codec.contentType, headers: options.headers })
    return codecFor(shapes.returns).decode(this.#succeeded(answer).body)
  }

  #succeeded(answer: ServiceResponse): ServiceResponse {
    if (Math.floor(answer.status / 100) !== 2) throw new ServiceCallFailed(this.target, answer.status, answer.text)
    return answer
  }
}

/**
 * How a component obtains clients for other services: `this.services.service("orders")`, or
 * `this.services.service("billing", "invoices")` for a service in another project — whether it admits
 * this one is its own ACL's decision. The call carries the metadata of the handler that is running, so
 * the runtime makes it as that handler: counted from it, and in its trace.
 */
export class Services {
  readonly #client: ComponentClient | undefined

  /** Calls through `client`'s sidecar, as the handler `client` is scoped to; with none, every call throws. */
  constructor(client?: ComponentClient) {
    this.#client = client
  }

  service(name: string): ServiceClient
  service(project: string, name: string): ServiceClient
  service(first: string, second?: string): ServiceClient {
    const send: Send = (request, target) => this._send(request, target)
    return second === undefined ? new ServiceClient(send, first, undefined) : new ServiceClient(send, second, first)
  }

  /** @internal */
  async _send(request: ServiceRequest, target: string): Promise<ServiceResponse> {
    const channel = this.#client ? channelOf(this.#client) : undefined
    if (!channel) throw new Error("these services are not connected to a runtime; a unit test passes a ScriptedServices")
    const reply = await channel
      .stub()
      .request({
        service: request.service,
        project: request.project,
        method: request.method,
        path: request.path,
        headers: request.headers.map(([name, value]) => ({ name, value })),
        contentType: request.contentType,
        body: request.body,
        metadata: metadataToProto(channel.metadata),
      })
      .catch(tooOld)
    return answerOf(target, reply)
  }
}

function tooOld(failure: unknown): never {
  if (failure instanceof ConnectError && failure.code === Code.Unimplemented) {
    throw new CommandError({
      message: `the runtime beside this process cannot call another service, which needs protocol ${SERVICES_SINCE} (this SDK speaks ${PROTOCOL_VERSION}): ${failure.rawMessage}`,
      code: "INTERNAL",
    })
  }
  throw failure
}

function answerOf(target: string, reply: ServiceReply): ServiceResponse {
  switch (reply.result.case) {
    case "response": {
      const r = reply.result.value
      return response(r.status, r.contentType, r.body, r.headers.map((h) => [h.name, h.value] as const))
    }
    case "failure": {
      const { reason, detail } = reply.result.value
      if (reason === ServiceFailure_Reason.UNRESOLVABLE) throw new ServiceUnresolvable(target, detail)
      if (reason === ServiceFailure_Reason.IDENTITY_MISMATCH) throw new ServiceIdentityMismatch(target, detail)
      throw new ServiceUnanswered(target, detail)
    }
    case "error":
      throw new CommandError({ message: reply.result.value.message, code: errorCodeFromProto(reply.result.value.code) })
    default:
      throw new CommandError({ message: `the runtime answered a call to ${target} with nothing`, code: "INTERNAL" })
  }
}

type Script = ((request: ServiceRequest) => ServiceResponse) | ServiceError

/**
 * Other services, for a unit test: each answers as the test scripted it, and every request is
 * recorded. A call to a service nothing is scripted for fails the test, naming the service: a test
 * whose call quietly got a default answer is no longer testing what it says.
 */
export class ScriptedServices extends Services {
  readonly requests: ServiceRequest[] = []
  readonly #scripts = new Map<string, Script>()

  constructor() {
    super(undefined)
  }

  /** `name` answers with what `handler` returns; in this service's own project unless one is given. */
  answer(name: string, handler: (request: ServiceRequest) => ServiceResponse, project?: string): this {
    this.#scripts.set(keyOf(project, name), handler)
    return this
  }

  unresolvable(name: string, project?: string): this {
    this.#scripts.set(keyOf(project, name), new ServiceUnresolvable(keyOf(project, name), "scripted as unresolvable"))
    return this
  }

  unanswered(name: string, project?: string): this {
    this.#scripts.set(keyOf(project, name), new ServiceUnanswered(keyOf(project, name), "scripted as unanswered"))
    return this
  }

  mismatch(name: string, project?: string): this {
    this.#scripts.set(keyOf(project, name), new ServiceIdentityMismatch(keyOf(project, name), "scripted as another service"))
    return this
  }

  /** An answer of `body` as `text/plain`, for a script. */
  static text(body: string, status = 200): ServiceResponse {
    return response(status, "text/plain", new TextEncoder().encode(body), [])
  }

  /** An answer of `body` as `application/json`, for a script. */
  static json(body: string, status = 200): ServiceResponse {
    return response(status, "application/json", new TextEncoder().encode(body), [])
  }

  /** @internal */
  override async _send(request: ServiceRequest, target: string): Promise<ServiceResponse> {
    this.requests.push(request)
    const script = this.#scripts.get(keyOf(request.project, request.service))
    if (script === undefined) {
      const project = request.project ? `, "${request.project}"` : ""
      throw new Error(
        `the test called the service '${target}', and nothing is scripted for it: script it with answer("${request.service}", (request) => ScriptedServices.text(...)${project})`,
      )
    }
    if (script instanceof ServiceError) throw script
    return script(request)
  }
}

function keyOf(project: string | undefined, name: string): string {
  return project ? `${project}/${name}` : name
}

/** Services for a unit test whose every call throws: the unit kit's default, beside `noClient()`. */
export function noServices(): Services {
  return new Services(undefined)
}

/**
 * @internal Other services, reached through `client`'s sidecar as the handler it is scoped to. Not a
 * member of the client, which an entity holds too: only the kinds that may call one call this.
 */
export function servicesFor(client: ComponentClient): Services {
  return new Services(client)
}
