// A tool of one of this process's MCP endpoints (protocol 1.20). The platform answered the protocol,
// verified the credential and checked the arguments against the tool's schema; here the tool runs, as
// a route does in `http.ts`: the call in the async context (`this.request`, with the principal and the
// caller, never the credential), the run awaited, and its outcome answered as `ok`, `error` (the tool
// error a client is shown) or `failure` (this process could not run it at all).
//
// `createMcpDispatcher` is the whole behaviour; the Connect servicer and the unit tests both call it.

import type { ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Mcp, McpToolReplySchema, type McpToolReply, type McpToolRequest } from "../_proto/ankka/protocol/v1/mcp_pb.ts"
import { codecFor, isCodec, type Codec, type Shape } from "../codec.ts"
import { Headers, Query, metadataFromProto, withRequest, type RequestContext } from "../context.ts"
import { CommandError, ErrorCode } from "../effects/common.ts"
import type { Endpoint } from "../endpoint.ts"
import { decodeJsonValue, reviver } from "../json.ts"
import { errorCodeToProto } from "../kinds.ts"
import type { McpEndpoint, RegisteredMcpEndpoint, RegisteredMcpTool, ToolRun } from "../mcp-endpoint.ts"
import { HttpProblem } from "../routes.ts"
import { done, type Schema } from "../schema.ts"
import { callerOf, parseParam, principalOf } from "./http.ts"
import type { ServerContext } from "./server.ts"

const utf8 = new TextEncoder()
const utf8Decoder = new TextDecoder()

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** What a tool answered: its text, and the same as structured content when it is a JSON object. */
export interface ToolAnswer {
  readonly text: string
  readonly structuredJson?: string
}

function isPlainObject(x: unknown): boolean {
  return typeof x === "object" && x !== null && !Array.isArray(x)
}

function structured(text: string): ToolAnswer {
  try {
    return isPlainObject(JSON.parse(text)) ? { text, structuredJson: text } : { text }
  } catch {
    return { text }
  }
}

/** A tool's result as text and structured content, encoded with its reply shape when it has one. */
export function answerOf(value: unknown, reply: Shape<unknown> | undefined): ToolAnswer {
  if (value === undefined || value === done) return { text: "done" }
  if (typeof value === "string" && (reply === undefined || codecFor(reply).contentType === "text/plain")) return { text: value }
  if (reply === undefined) return structured(JSON.stringify(value, (_k, v) => (typeof v === "bigint" ? v.toString() : v)))
  const codec = codecFor(reply) as Codec<unknown>
  const text = utf8Decoder.decode(codec.encode(value))
  return codec.contentType === "application/json" ? structured(text) : { text }
}

/** An argument as a handler's input: through its codec when it has one, else its schema. */
function decodeArgument(shape: Shape<unknown>, raw: unknown): unknown {
  if (isCodec(shape)) return shape.decode(utf8.encode(JSON.stringify(raw)))
  return decodeJsonValue(shape as Schema, raw)
}

class ToolRefusal extends Error {}

export interface McpDispatcher {
  invokeTool(req: McpToolRequest): Promise<McpToolReply>
}

/** The behaviour behind `Mcp.InvokeTool`, over the registry's MCP endpoints. */
export function createMcpDispatcher(ctx: ServerContext): McpDispatcher {
  const instances = new Map<string, Endpoint>()

  function instanceOf(id: string, cls: new () => Endpoint): Endpoint {
    let instance = instances.get(id)
    if (!instance) {
      instance = new cls()
      instance._bindClient(ctx.client)
      instances.set(id, instance)
    }
    return instance
  }

  async function runHandler(run: Extract<ToolRun, { kind: "handler" }>, args: Record<string, unknown>, self: McpEndpoint): Promise<ToolAnswer> {
    const id = args[run.id]
    if (typeof id !== "string" || id === "") throw new ToolRefusal(`parameter '${run.id}': expected a non-empty string`)
    const handler = run.handler
    let input: unknown
    if (run.input?.kind === "merge") {
      const { [run.id]: _id, ...rest } = args
      input = decodeArgument(handler.input as Shape<unknown>, rest)
    } else if (run.input?.kind === "argument") {
      input = decodeArgument(handler.input as Shape<unknown>, args[run.input.name])
    }
    const client = self.client
    const calls =
      run.componentKind === "event-sourced"
        ? client.forEventSourcedEntity(run.componentId, id)
        : run.componentKind === "key-value"
          ? client.forKeyValueEntity(run.componentId, id)
          : client.forWorkflow(run.componentId, id)
    const result = await calls.call(handler.name, handler.input as Shape<unknown> | undefined, handler.reply as Shape<unknown> | undefined).invoke(input)
    return answerOf(result, handler.reply as Shape<unknown> | undefined)
  }

  async function runRoute(run: Extract<ToolRun, { kind: "route" }>, args: Record<string, unknown>, call: RequestContext): Promise<ToolAnswer> {
    const endpoint = ctx.registry.endpoint(run.endpointId)
    if (!endpoint) throw new Error(`the endpoint ${run.endpointId} is not registered`)
    const route = run.route
    const params: Record<string, unknown> = {}
    for (const name of run.paramNames) {
      const raw = args[name]
      if (typeof raw !== "string" && typeof raw !== "number") throw new ToolRefusal(`parameter '${name}': expected a string`)
      params[name] = parseParam(name, String(raw), route.paramShapes[name])
    }
    const body = route.body ? codecFor(route.body).decode(utf8.encode(JSON.stringify(args.body ?? {}))) : undefined
    const request: RequestContext = Object.freeze({ ...call, params: Object.freeze(params) })
    const instance = instanceOf(endpoint.id, endpoint.cls as unknown as new () => Endpoint)
    const result = await withRequest(request, () => route.run(instance, request, body))
    return answerOf(result, route.reply as Shape<unknown> | undefined)
  }

  async function run(endpoint: RegisteredMcpEndpoint, tool: RegisteredMcpTool, req: McpToolRequest): Promise<ToolAnswer> {
    const parsed: unknown = req.argumentsJson.trim() === "" ? {} : JSON.parse(req.argumentsJson, reviver)
    if (!isPlainObject(parsed)) throw new ToolRefusal("the arguments must be an object")
    const args = parsed as Record<string, unknown>
    const self = instanceOf(endpoint.id, endpoint.cls as unknown as new () => Endpoint) as McpEndpoint
    const call: RequestContext = Object.freeze({
      params: Object.freeze({}),
      query: new Query([]),
      headers: new Headers([]),
      principal: principalOf(req),
      metadata: metadataFromProto(req.metadata),
      caller: callerOf(req),
    })
    return withRequest(call, async () => {
      switch (tool.run.kind) {
        case "function":
          return answerOf(await tool.run.run(decodeArgument(tool.run.input, args), self), tool.run.reply)
        case "handler":
          return runHandler(tool.run, args, self)
        case "route":
          return runRoute(tool.run, args, call)
      }
    })
  }

  return {
    async invokeTool(req: McpToolRequest): Promise<McpToolReply> {
      const endpoint = ctx.registry.mcpEndpoint(req.endpointId)
      const tool = endpoint?.tools.find((t) => t.name === req.tool)
      if (!endpoint || !tool) {
        return create(McpToolReplySchema, {
          reply: { case: "failure", value: { commandId: 0n, error: { message: `no tool ${req.endpointId}/${req.tool} is registered`, code: errorCodeToProto(ErrorCode.NotFound) } } },
        })
      }
      try {
        const answer = await run(endpoint, tool, req)
        return create(McpToolReplySchema, { reply: { case: "ok", value: { text: answer.text, ...(answer.structuredJson !== undefined ? { structuredJson: answer.structuredJson } : {}) } } })
      } catch (e) {
        // A refusal, a decoding failure or a throw: the tool error the client is shown, which a model can act on.
        if (!(e instanceof CommandError || e instanceof HttpProblem || e instanceof ToolRefusal)) {
          ctx.log(`ankka: tool ${endpoint.path} ${tool.name} threw: ${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}`)
        }
        return create(McpToolReplySchema, { reply: { case: "error", value: messageOf(e) } })
      }
    },
  }
}

export function mcpRoutes(router: ConnectRouter, ctx: ServerContext): void {
  const dispatcher = createMcpDispatcher(ctx)
  router.service(Mcp, { invokeTool: (req) => dispatcher.invokeTool(req) })
}
