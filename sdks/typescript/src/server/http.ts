// HTTP requests the sidecar forwards for the routes we declared. The sidecar's router matched the route,
// applied the access rule and opened the span; here the handler runs. One endpoint instance serves every
// request, so the request itself lives in the async context (`this.request`), never on the instance.
//
// `createHttpDispatcher` is the whole behaviour; the Connect servicer and the endpoint unit testkit both
// call it, so a test cannot pass on a path the sidecar would not take.

import type { ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Http, HttpReplySchema, StreamFrameSchema, type HttpRequest, type HttpReply, type StreamFrame } from "../_proto/ankka/protocol/v1/endpoint_pb.ts"
import { codecFor, type Codec } from "../codec.ts"
import { Headers, Query, metadataFromProto, withRequest, type Principal, type RequestContext } from "../context.ts"
import { CommandError, ErrorCode, httpStatusOf } from "../effects/common.ts"
import type { Endpoint } from "../endpoint.ts"
import { DecodingError } from "../json.ts"
import { errorCodeToProto } from "../kinds.ts"
import { HttpProblem, type RouteRef } from "../routes.ts"
import { done, resolve, type Schema } from "../schema.ts"
import type { RegisteredEndpoint } from "../service.ts"
import { AsyncQueue } from "./queue.ts"
import type { ServerContext } from "./server.ts"

const utf8 = new TextEncoder()

function textResponse(status: number, text: string): HttpReply {
  return create(HttpReplySchema, { message: { case: "response", value: { status, contentType: "text/plain; charset=utf-8", body: utf8.encode(text), headers: [] } } })
}

function failureReply(message: string): HttpReply {
  return create(HttpReplySchema, { message: { case: "failure", value: { commandId: 0n, error: { message, code: errorCodeToProto(ErrorCode.Internal) } } } })
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function principalOf(req: HttpRequest): Principal | null {
  const p = req.principal
  if (!p) return null
  return Object.freeze({ subject: p.subject, name: p.name ?? null, email: p.email ?? null, emailVerified: p.emailVerified, roles: Object.freeze([...p.roles]) })
}

/** A path parameter as its declared schema: strings as they are, scalars through their text codec. */
function parseParam(name: string, text: string, shape: Schema | undefined): unknown {
  if (!shape) return text
  const r = resolve(shape)
  if (r.kind === "string") return text
  const codec = codecFor(shape) as Codec<unknown>
  if (codec.contentType !== "text/plain") throw new DecodingError(`a path parameter must be a scalar, not ${r.kind}`, name)
  try {
    return codec.decode(utf8.encode(text))
  } catch (e) {
    throw new DecodingError(`path parameter ${name}: ${messageOf(e)}`, name)
  }
}

interface Prepared {
  endpoint: RegisteredEndpoint
  route: RouteRef<any, any, any, any>
  instance: Endpoint
  request: RequestContext
  body: unknown
}

type Prep = { ok: true; prepared: Prepared } | { ok: false; reply: HttpReply }

function encodeResult(route: RouteRef<any, any, any, any>, result: unknown): HttpReply {
  if (result === undefined || result === done) {
    return create(HttpReplySchema, { message: { case: "response", value: { status: 204, contentType: "", body: new Uint8Array(), headers: [] } } })
  }
  if (!route.reply) return failureReply(`route ${route.method} ${route.template} returned a value but declares no reply shape`)
  const codec = codecFor(route.reply) as Codec<unknown>
  const contentType = codec.contentType === "text/plain" ? "text/plain; charset=utf-8" : codec.contentType
  return create(HttpReplySchema, { message: { case: "response", value: { status: 200, contentType, body: codec.encode(result), headers: [] } } })
}

export interface HttpDispatcher {
  handle(req: HttpRequest): Promise<HttpReply>
  handleStream(req: HttpRequest): AsyncIterable<StreamFrame>
}

/** The behaviour behind `Http.Handle` and `Http.HandleStream`, over the registry's endpoints. */
export function createHttpDispatcher(ctx: ServerContext): HttpDispatcher {
  const instances = new Map<string, Endpoint>()

  function prepare(req: HttpRequest): Prep {
    const endpoint = ctx.registry.endpoint(req.endpointId)
    const route = endpoint?.routes.get(req.routeId)
    if (!endpoint || !route) return { ok: false, reply: textResponse(404, `no route ${req.endpointId}/${req.routeId}`) }

    let instance = instances.get(endpoint.id)
    if (!instance) {
      instance = new endpoint.cls() as Endpoint
      instance._bindClient(ctx.client)
      instances.set(endpoint.id, instance)
    }

    try {
      const params: Record<string, unknown> = {}
      route.paramNames.forEach((name, i) => {
        params[name] = parseParam(name, req.pathArgs[i] ?? "", route.paramShapes[name])
      })
      const request: RequestContext = Object.freeze({
        params: Object.freeze(params),
        query: new Query(req.query.map((p) => [p.name, p.value] as const)),
        headers: new Headers(req.headers.map((p) => [p.name, p.value] as const)),
        principal: principalOf(req),
        metadata: metadataFromProto(req.metadata),
      })
      const body = route.body ? codecFor(route.body).decode(req.body) : undefined
      return { ok: true, prepared: { endpoint, route, instance, request, body } }
    } catch (e) {
      return { ok: false, reply: textResponse(400, messageOf(e)) }
    }
  }

  function errorReply(e: unknown, where: string): HttpReply {
    if (e instanceof HttpProblem) return textResponse(e.status, e.message)
    if (e instanceof CommandError) return textResponse(httpStatusOf(e.code), e.message)
    if (e instanceof DecodingError) return textResponse(400, e.message)
    ctx.log(`ankka: ${where} threw: ${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}`)
    return failureReply(messageOf(e))
  }

  return {
    async handle(req: HttpRequest): Promise<HttpReply> {
      const prep = prepare(req)
      if (!prep.ok) return prep.reply
      const { route, instance, request, body } = prep.prepared
      try {
        const result = await withRequest(request, () => route.run(instance, request, body))
        return encodeResult(route, result)
      } catch (e) {
        return errorReply(e, `${req.endpointId}/${req.routeId}`)
      }
    },

    async *handleStream(req: HttpRequest): AsyncIterable<StreamFrame> {
      const prep = prepare(req)
      if (!prep.ok) {
        const r = prep.reply.message.case === "response" ? prep.reply.message.value : undefined
        yield create(StreamFrameSchema, { frame: { case: "failed", value: { message: r ? new TextDecoder().decode(r.body) : "no route", code: errorCodeToProto(ErrorCode.NotFound) } } })
        return
      }
      const { route, instance, request } = prep.prepared
      // Produce inside the request's async context, consume here: the handler's `this.request` stays visible.
      const queue = new AsyncQueue<StreamFrame>()
      void withRequest(request, async () => {
        try {
          const frames = (await route.run(instance, request, undefined)) as AsyncIterable<string>
          for await (const text of frames) queue.push(create(StreamFrameSchema, { frame: { case: "text", value: text } }))
          queue.push(create(StreamFrameSchema, { frame: { case: "completed", value: {} } }))
        } catch (e) {
          const code = e instanceof CommandError ? e.code : ErrorCode.Internal
          ctx.log(`ankka: ${req.endpointId}/${req.routeId} (stream) threw: ${messageOf(e)}`)
          queue.push(create(StreamFrameSchema, { frame: { case: "failed", value: { message: messageOf(e), code: errorCodeToProto(code) } } }))
        } finally {
          queue.close()
        }
      })
      yield* queue
    },
  }
}

export function httpRoutes(router: ConnectRouter, ctx: ServerContext): void {
  const dispatcher = createHttpDispatcher(ctx)
  router.service(Http, {
    handle: (req) => dispatcher.handle(req),
    handleStream: (req) => dispatcher.handleStream(req),
  })
}
