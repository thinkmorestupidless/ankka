// The workflow conversation: one bidirectional stream per loaded instance, carrying commands *and*
// steps. The engine keeps answering commands while a step runs, so this side holds two slots: the
// stream's own instance answers commands from the state before the step, and each step runs on a fresh
// instance so the two never share a context. Replies leave through one queue, so a command is never
// stuck behind a step.

import type { ConnectRouter } from "@connectrpc/connect"
import { create, type MessageInitShape } from "@bufbuild/protobuf"
import {
  Workflow as WorkflowService,
  WorkflowOutSchema,
  type StepRefSchema,
  type StepOutcomeSchema,
  type WorkflowIn,
  type WorkflowIn_Command,
  type WorkflowIn_RunStep,
  type WorkflowOut,
} from "../_proto/ankka/protocol/v1/workflow_pb.ts"
import type { OutcomeSchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import { binaryCodecs, codecFor } from "../codec.ts"
import { commandContext, metadataFromProto } from "../context.ts"
import { ErrorCode, type ErrorDetail } from "../effects/common.ts"
import type { StepEffect, StepRef, WorkflowCommandEffect } from "../effects/workflow.ts"
import type { Workflow } from "../workflow.ts"
import { errorCodeToProto } from "../kinds.ts"
import { materialiseStep, materialiseWorkflowCommand } from "../materialise.ts"
import type { RegisteredWorkflow } from "../service.ts"
import { decodePayload, encodePayload } from "./payloads.ts"
import { AsyncQueue } from "./queue.ts"
import type { ServerContext } from "./server.ts"

type OutcomeInit = MessageInitShape<typeof OutcomeSchema>
type StepRefInit = MessageInitShape<typeof StepRefSchema>
type StepOutcomeInit = MessageInitShape<typeof StepOutcomeSchema>

function failure(commandId: bigint, error: ErrorDetail): WorkflowOut {
  return create(WorkflowOutSchema, { message: { case: "failure", value: { commandId, error: { message: error.message, code: errorCodeToProto(error.code) } } } })
}

function messageOf(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e)
}

/** A step reference as the protocol carries it; the input is encoded with the *target* step's declared shape. */
function stepRefInit(registered: RegisteredWorkflow, ref: StepRef): StepRefInit {
  const target = registered.steps.get(ref.step)
  if (!target) throw new Error(`transition to ${JSON.stringify(ref.step)}, which is not a declared step`)
  if (ref.input === undefined || ref.input === null) return { step: ref.step }
  if (!target.input) throw new Error(`step ${JSON.stringify(ref.step)} takes no input, but one was given`)
  return { step: ref.step, input: encodePayload(codecFor(target.input), ref.input) }
}

class WorkflowStream {
  readonly registered: RegisteredWorkflow
  readonly instance: Workflow<unknown>
  readonly entityId: string
  state: unknown
  running: Promise<void> | undefined
  readonly out = new AsyncQueue<WorkflowOut>()

  constructor(registered: RegisteredWorkflow, instance: Workflow<unknown>, entityId: string, state: unknown) {
    this.registered = registered
    this.instance = instance
    this.entityId = entityId
    this.state = state
  }

  async command(cmd: WorkflowIn_Command, ctx: ServerContext): Promise<WorkflowOut> {
    const { registered, instance } = this
    const handler = registered.handlers.get(cmd.name)
    if (!handler) return failure(cmd.id, { message: `no handler ${JSON.stringify(cmd.name)} on ${registered.id}`, code: ErrorCode.NotFound })
    let input: unknown
    try {
      input = handler.input ? decodePayload(codecFor(handler.input), cmd.payload) : undefined
    } catch (e) {
      return failure(cmd.id, { message: `${registered.id}/${cmd.name}: the input could not be decoded: ${messageOf(e)}`, code: ErrorCode.BadRequest })
    }
    const metadata = metadataFromProto(cmd.metadata)
    let effect: WorkflowCommandEffect<unknown, unknown>
    instance._bindCommand(this.state, commandContext(registered.id, this.entityId, 0n, metadata), ctx.client.withMetadata(metadata))
    try {
      effect = (await handler.run(instance, input)) as WorkflowCommandEffect<unknown, unknown>
    } catch (e) {
      ctx.log(`ankka: ${registered.id}/${this.entityId}/${cmd.name} threw: ${messageOf(e)}`)
      return failure(cmd.id, { message: messageOf(e), code: ErrorCode.Internal })
    } finally {
      instance._unbindCommand()
    }
    if (handler.readOnly && effect?.kind !== "read-only") {
      return failure(cmd.id, { message: `${registered.id}/${cmd.name} is a query and returned a changing effect`, code: ErrorCode.Internal })
    }
    try {
      const m = materialiseWorkflowCommand(effect, this.state)
      const replyCodec = handler.reply ? codecFor(handler.reply) : binaryCodecs.done
      const outcome: OutcomeInit = m.error
        ? { outcome: { case: "error", value: { message: m.error.message, code: errorCodeToProto(m.error.code) } } }
        : m.noReply
          ? { outcome: { case: "noReply", value: {} } }
          : { outcome: { case: "reply", value: { payload: encodePayload(replyCodec, m.reply), metadata: { entries: [] } } } }
      const out = create(WorkflowOutSchema, {
        message: {
          case: "reply",
          value: {
            commandId: cmd.id,
            newState: m.changed && !m.error ? encodePayload(registered.stateCodec, m.newState) : undefined,
            transition: m.transition && !m.error ? stepRefInit(registered, m.transition) : undefined,
            outcome,
          },
        },
      })
      if (!m.error) this.state = m.newState
      return out
    } catch (e) {
      ctx.log(`ankka: ${registered.id}/${this.entityId}/${cmd.name}: the effect could not be applied: ${messageOf(e)}`)
      return failure(cmd.id, { message: messageOf(e), code: ErrorCode.Internal })
    }
  }

  /** Runs a step on a fresh instance, as the sidecar's engine does: commands keep arriving on the stream's instance meanwhile. */
  async step(run: WorkflowIn_RunStep, ctx: ServerContext): Promise<WorkflowOut> {
    const { registered } = this
    const step = registered.steps.get(run.step)
    if (!step) return failure(run.id, { message: `no step ${JSON.stringify(run.step)} on ${registered.id}`, code: ErrorCode.NotFound })
    const fresh = new registered.cls() as Workflow<unknown>
    fresh._bindInstance(this.entityId)
    let input: unknown
    try {
      input = step.input ? decodePayload(codecFor(step.input), run.input) : undefined
    } catch (e) {
      return failure(run.id, { message: `${registered.id}/${run.step}: the input could not be decoded: ${messageOf(e)}`, code: ErrorCode.BadRequest })
    }
    let effect: StepEffect<unknown>
    fresh._bindCommand(this.state, commandContext(registered.id, this.entityId, 0n, {}), ctx.client)
    try {
      effect = (await step.run(fresh, input)) as StepEffect<unknown>
    } catch (e) {
      // A thrown step is a *fault*: the engine applies the declared recovery. A `thenFail` is a decision.
      ctx.log(`ankka: ${registered.id}/${this.entityId} step ${run.step} threw: ${messageOf(e)}`)
      return failure(run.id, { message: messageOf(e), code: ErrorCode.Internal })
    } finally {
      fresh._unbindCommand()
    }
    try {
      const m = materialiseStep(effect, this.state)
      let next: StepOutcomeInit
      switch (m.next.kind) {
        case "transition":
          next = { outcome: { case: "transitionTo", value: stepRefInit(registered, m.next.ref) } }
          break
        case "pause":
          next = {
            outcome: {
              case: "pause",
              value: {
                ...(m.next.after ? { afterMillis: BigInt(m.next.after.toMillis()) } : {}),
                ...(m.next.onTimeout ? { onTimeout: stepRefInit(registered, m.next.onTimeout) } : {}),
              },
            },
          }
          break
        case "end":
          next = { outcome: { case: "end", value: {} } }
          break
        case "fail":
          next = { outcome: { case: "fail", value: { message: m.next.error.message, code: errorCodeToProto(m.next.error.code) } } }
          break
      }
      const out = create(WorkflowOutSchema, {
        message: { case: "stepReply", value: { commandId: run.id, newState: m.changed ? encodePayload(registered.stateCodec, m.newState) : undefined, next } },
      })
      this.state = m.newState
      return out
    } catch (e) {
      ctx.log(`ankka: ${registered.id}/${this.entityId} step ${run.step}: the effect could not be applied: ${messageOf(e)}`)
      return failure(run.id, { message: messageOf(e), code: ErrorCode.Internal })
    }
  }
}

export async function* handleWorkflow(requests: AsyncIterable<WorkflowIn>, ctx: ServerContext): AsyncIterable<WorkflowOut> {
  const out = new AsyncQueue<WorkflowOut>()
  let stream: WorkflowStream | undefined

  const read = async (): Promise<void> => {
    try {
      for await (const m of requests) {
        switch (m.message.case) {
          case "init": {
            const init = m.message.value
            const found = ctx.registry.of("workflow", init.componentId)
            if (!found) {
              out.push(failure(0n, { message: `no workflow ${JSON.stringify(init.componentId)} is registered`, code: ErrorCode.NotFound }))
              return
            }
            try {
              const instance = new found.cls() as Workflow<unknown>
              instance._bindInstance(init.entityId)
              const state = init.state ? decodePayload(found.stateCodec, init.state) : instance.emptyState()
              stream = new WorkflowStream(found, instance, init.entityId, state)
            } catch (e) {
              ctx.log(`ankka: ${init.componentId}/${init.entityId}: recovery failed: ${messageOf(e)}`)
              out.push(failure(0n, { message: `recovery failed: ${messageOf(e)}`, code: ErrorCode.Internal }))
              return
            }
            break
          }
          case "command": {
            if (!stream) {
              out.push(failure(m.message.value.id, { message: "a command arrived before Init", code: ErrorCode.Internal }))
              return
            }
            out.push(await stream.command(m.message.value, ctx))
            break
          }
          case "runStep": {
            if (!stream) {
              out.push(failure(m.message.value.id, { message: "a step arrived before Init", code: ErrorCode.Internal }))
              return
            }
            if (stream.running) {
              out.push(failure(m.message.value.id, { message: "a step is already running", code: ErrorCode.Internal }))
              break
            }
            const s = stream
            const run = m.message.value
            s.running = s
              .step(run, ctx)
              .then((reply) => out.push(reply))
              .finally(() => {
                s.running = undefined
              })
            break
          }
          default:
            break
        }
      }
    } catch (e) {
      out.fail(e)
      return
    }
    // The sidecar closed its side: a step still running has nowhere to answer; its result is dropped with the state.
    out.close()
  }

  void read()
  try {
    yield* out
  } finally {
    out.close()
  }
}

export function workflowRoutes(router: ConnectRouter, ctx: ServerContext): void {
  router.service(WorkflowService, {
    handle: (requests) => handleWorkflow(requests, ctx),
  })
}
