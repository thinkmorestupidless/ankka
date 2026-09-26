// The agent conversation: the sidecar asks this process to plan (an AgentEffect as data), to run a tool
// with the model's arguments, and to check a guardrail. The loop, the memory and the model's key are
// the sidecar's.

import type { ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import {
  Agent as AgentService,
  AgentPlan_Memory,
  GuardrailRequest_Stage,
  GuardrailResultSchema,
  PlanReplySchema,
  ToolResultSchema,
  type GuardrailRequest,
  type GuardrailResult,
  type PlanReply,
  type PlanRequest,
  type ToolRequest,
  type ToolResult,
} from "../_proto/ankka/protocol/v1/agent_pb.ts"
import { codecFor, isCodec, type Codec } from "../codec.ts"
import { metadataFromProto } from "../context.ts"
import { ErrorCode, type ErrorDetail } from "../effects/common.ts"
import type { AgentEffect } from "../effects/agent.ts"
import type { Agent } from "../agent.ts"
import { decodeJsonValue, reviver } from "../json.ts"
import { errorCodeToProto } from "../kinds.ts"
import type { Schema } from "../schema.ts"
import type { RegisteredAgent } from "../service.ts"
import { decodePayload } from "./payloads.ts"
import type { ServerContext } from "./server.ts"

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function planFailure(commandId: bigint, error: ErrorDetail): PlanReply {
  return create(PlanReplySchema, { message: { case: "failure", value: { commandId, error: { message: error.message, code: errorCodeToProto(error.code) } } } })
}

/** Checks a plan against the declaration: a plan may only name tools and guardrails discovery declared. */
export function checkPlan(registered: RegisteredAgent, effect: AgentEffect<unknown>): string | undefined {
  const unknown = [...effect.toolNames.filter((t) => !registered.tools.has(t)).map((t) => `tool ${JSON.stringify(t)}`), ...effect.guardrailNames.filter((g) => !registered.guardrails.has(g)).map((g) => `guardrail ${JSON.stringify(g)}`)]
  return unknown.length > 0 ? `${registered.id}: the plan names undeclared ${unknown.join(", ")}` : undefined
}

export async function handlePlan(req: PlanRequest, ctx: ServerContext): Promise<PlanReply> {
  const registered = ctx.registry.of("agent", req.componentId)
  if (!registered) return planFailure(0n, { message: `no agent ${JSON.stringify(req.componentId)} is registered`, code: ErrorCode.NotFound })
  const handler = registered.handlers.get(req.name)
  if (!handler) return planFailure(0n, { message: `no handler ${JSON.stringify(req.name)} on ${registered.id}`, code: ErrorCode.NotFound })
  const metadata = metadataFromProto(req.metadata)
  const agent = new registered.cls() as Agent
  agent._bind(req.sessionId, metadata, ctx.client.withMetadata(metadata))
  let effect: AgentEffect<unknown>
  try {
    const input = handler.input ? decodePayload(codecFor(handler.input), req.payload) : undefined
    effect = (await handler.run(agent, input)) as AgentEffect<unknown>
    if (effect?.kind !== "agent") throw new TypeError(`${registered.id}/${req.name} returned something that is not an agent effect`)
    const problem = checkPlan(registered, effect)
    if (problem) throw new Error(problem)
  } catch (e) {
    ctx.log(`ankka: agent ${registered.id}/${req.sessionId}/${req.name} threw: ${messageOf(e)}`)
    return planFailure(0n, { message: messageOf(e), code: ErrorCode.Internal })
  }
  return create(PlanReplySchema, {
    message: {
      case: "plan",
      value: {
        ...(effect.model !== null ? { model: effect.model } : {}),
        ...(effect.system !== null ? { system: effect.system } : {}),
        ...(effect.user !== null ? { user: effect.user } : {}),
        context: [...effect.context],
        memory: effect.sessionMemory ? AgentPlan_Memory.SESSION : AgentPlan_Memory.NONE,
        tools: [...effect.toolNames],
        guardrails: [...effect.guardrailNames],
        responseShape: effect.jsonReply ? { shape: { case: "json", value: { schemaHint: effect.schemaHint } } } : { shape: { case: "text", value: {} } },
        ...(effect.failure ? { failure: { message: effect.failure.message, code: errorCodeToProto(effect.failure.code) } } : {}),
      },
    },
  })
}

/** Runs a tool: the model's arguments, as JSON text, decoded with the tool's input shape; the result as text for the model. */
export async function runTool(registered: RegisteredAgent, agent: Agent, name: string, argumentsJson: string): Promise<string> {
  const tool = registered.tools.get(name)
  if (!tool) throw new Error(`no tool ${JSON.stringify(name)} on ${registered.id}`)
  let input: unknown
  if (isCodec(tool.input)) input = (tool.input as Codec<unknown>).decode(new TextEncoder().encode(argumentsJson))
  else input = decodeJsonValue(tool.input as Schema<unknown>, JSON.parse(argumentsJson || "{}", reviver))
  const result = await tool.run(agent, input)
  return typeof result === "string" ? result : JSON.stringify(result, (_k, v) => (typeof v === "bigint" ? v.toString() : v))
}

export async function handleTool(req: ToolRequest, ctx: ServerContext): Promise<ToolResult> {
  const registered = ctx.registry.of("agent", req.componentId)
  if (!registered) return create(ToolResultSchema, { result: { case: "error", value: `no agent ${JSON.stringify(req.componentId)} is registered` } })
  const agent = new registered.cls() as Agent
  agent._bind(req.sessionId, {}, ctx.client)
  try {
    return create(ToolResultSchema, { result: { case: "ok", value: await runTool(registered, agent, req.tool, req.argumentsJson) } })
  } catch (e) {
    // A tool error is fed back to the model, which usually corrects itself.
    return create(ToolResultSchema, { result: { case: "error", value: messageOf(e) } })
  }
}

export async function handleGuardrail(req: GuardrailRequest, ctx: ServerContext): Promise<GuardrailResult> {
  const registered = ctx.registry.of("agent", req.componentId)
  const guardrail = registered?.guardrails.get(req.guardrail)
  if (!registered || !guardrail) return create(GuardrailResultSchema, { result: { case: "block", value: `no guardrail ${JSON.stringify(req.guardrail)} on ${req.componentId}` } })
  try {
    const reason = await guardrail.check(req.stage === GuardrailRequest_Stage.OUTPUT ? "output" : "input", req.text)
    return reason === null || reason === undefined
      ? create(GuardrailResultSchema, { result: { case: "pass", value: {} } })
      : create(GuardrailResultSchema, { result: { case: "block", value: reason } })
  } catch (e) {
    ctx.log(`ankka: guardrail ${registered.id}/${req.guardrail} threw: ${messageOf(e)}`)
    return create(GuardrailResultSchema, { result: { case: "block", value: `guardrail ${req.guardrail} failed: ${messageOf(e)}` } })
  }
}

export function agentRoutes(router: ConnectRouter, ctx: ServerContext): void {
  router.service(AgentService, {
    plan: (req) => handlePlan(req, ctx),
    invokeTool: (req) => handleTool(req, ctx),
    checkGuardrail: (req) => handleGuardrail(req, ctx),
  })
}
