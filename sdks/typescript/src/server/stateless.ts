// The stateless conversations: a view's change, a consumer's message, a timed action's call. A new
// instance per request; nothing is held between requests.

import { Code, ConnectError, type ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { View as ViewService, ViewEffectSchema, type ViewEffect as ProtoViewEffect, type ViewRequest } from "../_proto/ankka/protocol/v1/view_pb.ts"
import { Consumer as ConsumerService, ConsumerEffectSchema, type ConsumerEffect as ProtoConsumerEffect, type ConsumerRequest } from "../_proto/ankka/protocol/v1/consumer_pb.ts"
import { TimedAction as TimedActionService, TimedActionEffectSchema, type TimedActionEffect as ProtoTimedActionEffect, type TimedActionRequest } from "../_proto/ankka/protocol/v1/timed_action_pb.ts"
import { codecFor } from "../codec.ts"
import { metadataFromProto, metadataToProto } from "../context.ts"
import { ErrorCode } from "../effects/common.ts"
import type { View } from "../view.ts"
import type { Consumer } from "../consumer.ts"
import type { TimedAction } from "../timedAction.ts"
import { errorCodeToProto } from "../kinds.ts"
import { decodePayload, encodePayload } from "./payloads.ts"
import type { ServerContext } from "./server.ts"

function messageOf(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e)
}

export async function handleView(req: ViewRequest, ctx: ServerContext): Promise<ProtoViewEffect> {
  const registered = ctx.registry.of("view", req.componentId)
  if (!registered) throw new ConnectError(`no view ${JSON.stringify(req.componentId)} is registered`, Code.NotFound)
  const metadata = metadataFromProto(req.metadata)
  const view = new registered.cls() as View<unknown, unknown>
  try {
    const row = req.row ? decodePayload(registered.rowCodec, req.row) : null
    view._bind(row, metadata, ctx.client.withMetadata(metadata))
    const effect = req.deleted ? await view.onDelete() : await view.onChange(decodePayload(registered.eventCodec, req.event))
    switch (effect.kind) {
      case "update-row":
        return create(ViewEffectSchema, { effect: { case: "updateRow", value: encodePayload(registered.rowCodec, effect.row) } })
      case "delete-row":
        return create(ViewEffectSchema, { effect: { case: "deleteRow", value: {} } })
      case "ignore":
        return create(ViewEffectSchema, { effect: { case: "ignore", value: {} } })
      default:
        throw new TypeError(`${registered.id}.onChange returned something that is not a view effect`)
    }
  } catch (e) {
    if (e instanceof ConnectError) throw e
    ctx.log(`ankka: view ${registered.id} on ${metadata["ce-subject"] ?? "?"} threw: ${messageOf(e)}`)
    throw new ConnectError(messageOf(e), Code.Internal)
  }
}

export async function handleConsumer(req: ConsumerRequest, ctx: ServerContext): Promise<ProtoConsumerEffect> {
  const registered = ctx.registry.of("consumer", req.componentId)
  if (!registered) throw new ConnectError(`no consumer ${JSON.stringify(req.componentId)} is registered`, Code.NotFound)
  const metadata = metadataFromProto(req.metadata)
  const consumer = new registered.cls() as Consumer<unknown, unknown>
  try {
    consumer._bind(metadata, ctx.client.withMetadata(metadata))
    const effect = req.deleted ? await consumer.onDelete() : await consumer.onMessage(decodePayload(registered.messageCodec, req.message))
    switch (effect.kind) {
      case "produce": {
        if (!registered.outCodec) throw new Error(`${registered.id} produced a message but declares no out shape`)
        return create(ConsumerEffectSchema, { effect: { case: "produce", value: { payload: encodePayload(registered.outCodec, effect.payload), metadata: metadataToProto(effect.metadata) } } })
      }
      case "done":
        return create(ConsumerEffectSchema, { effect: { case: "done", value: {} } })
      case "ignore":
        return create(ConsumerEffectSchema, { effect: { case: "ignore", value: {} } })
      default:
        throw new TypeError(`${registered.id}.onMessage returned something that is not a consumer effect`)
    }
  } catch (e) {
    if (e instanceof ConnectError) throw e
    ctx.log(`ankka: consumer ${registered.id} on ${metadata["ce-subject"] ?? "?"} threw: ${messageOf(e)}`)
    throw new ConnectError(messageOf(e), Code.Internal)
  }
}

export async function handleTimedAction(req: TimedActionRequest, ctx: ServerContext): Promise<ProtoTimedActionEffect> {
  const fail = (message: string, code: ErrorCode = ErrorCode.Internal) =>
    create(TimedActionEffectSchema, { effect: { case: "fail", value: { message, code: errorCodeToProto(code) } } })
  const registered = ctx.registry.of("timed-action", req.componentId)
  if (!registered) return fail(`no timed action ${JSON.stringify(req.componentId)} is registered`, ErrorCode.NotFound)
  const action = registered.actions.get(req.name)
  if (!action) return fail(`no action ${JSON.stringify(req.name)} on ${registered.id}`, ErrorCode.NotFound)
  const metadata = metadataFromProto(req.metadata)
  const instance = new registered.cls() as TimedAction
  try {
    const input = action.input ? decodePayload(codecFor(action.input), req.payload) : undefined
    instance._bind(metadata, ctx.client.withMetadata(metadata))
    const effect = await action.run(instance, input)
    switch (effect.kind) {
      case "done":
        return create(TimedActionEffectSchema, { effect: { case: "done", value: {} } })
      case "fail": {
        const f = effect as { error: { message: string; code: ErrorCode } }
        return fail(f.error.message, f.error.code)
      }
      default:
        return fail(`${registered.id}/${req.name} returned something that is not a timed action effect`)
    }
  } catch (e) {
    ctx.log(`ankka: timed action ${registered.id}/${req.name} threw: ${messageOf(e)}`)
    return fail(messageOf(e))
  }
}

export function statelessRoutes(router: ConnectRouter, ctx: ServerContext): void {
  router.service(ViewService, { handle: (req) => handleView(req, ctx) })
  router.service(ConsumerService, { handle: (req) => handleConsumer(req, ctx) })
  router.service(TimedActionService, { invoke: (req) => handleTimedAction(req, ctx) })
}
