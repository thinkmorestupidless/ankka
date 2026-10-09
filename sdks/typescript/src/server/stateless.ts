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
import { clientRows, type KeyedView } from "../keyedView.ts"
import type { KeyedViewEffect } from "../effects/keyed.ts"
import { ProtocolVersionError, requireSeveralMessages, type Consumer } from "../consumer.ts"
import { GRAPH_DELTA_SCHEMA, deltaRecords, type GraphConsumer } from "../graph.ts"
import { JSON_CONTENT } from "../codec.ts"
import type { Metadata } from "../effects/common.ts"
import type { RegisteredConsumer } from "../service.ts"
import type { TimedAction } from "../timedAction.ts"
import { errorCodeToProto } from "../kinds.ts"
import { decodePayload, encodePayload } from "./payloads.ts"
// A view's row is the one place a personal field's lookup token is written.
import { allowingLookup } from "../personal.ts"
import { PayloadSchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import type { ServerContext } from "./server.ts"

function messageOf(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e)
}

/**
 * A keyed view's change: sent with the component it came from and no row, answered with the rows the
 * source's handler names. The view reads its own rows through the client, on its own id.
 */
async function handleKeyedView(req: ViewRequest, sourceId: string, ctx: ServerContext): Promise<ProtoViewEffect> {
  const registered = ctx.registry.of("keyed-view", req.componentId)
  if (!registered) throw new ConnectError(`no keyed view ${JSON.stringify(req.componentId)} is registered`, Code.NotFound)
  const source = registered.sources.get(sourceId)
  if (!source) throw new ConnectError(`keyed view ${registered.id} reads no ${JSON.stringify(sourceId)}`, Code.NotFound)
  const metadata = metadataFromProto(req.metadata)
  const view = new registered.cls() as KeyedView<unknown>
  try {
    const client = ctx.client.withMetadata(metadata)
    view._bind(metadata, client, clientRows(client, registered.id, registered.rowCodec))
    const effect = (req.deleted
      ? source.deleted
        ? await source.deleted(view)
        : undefined
      : await source.onChange(view, decodePayload(source.eventCodec, req.event))) as KeyedViewEffect<unknown> | undefined
    if (effect !== undefined && effect?.kind !== "rows") throw new TypeError(`${registered.id}'s handler for ${sourceId} returned something that is not a keyed view effect`)
    const changes = (effect?.changes ?? []).map((c) =>
      "deleted" in c
        ? { key: c.key, change: { case: "delete" as const, value: {} } }
        : { key: c.key, change: { case: "upsert" as const, value: allowingLookup(() => encodePayload(registered.rowCodec, c.row)) } },
    )
    return create(ViewEffectSchema, { effect: { case: "rows", value: { changes } } })
  } catch (e) {
    if (e instanceof ConnectError) throw e
    ctx.log(`ankka: keyed view ${registered.id} on ${sourceId} ${metadata["ce-subject"] ?? "?"} threw: ${messageOf(e)}`)
    throw new ConnectError(messageOf(e), Code.Internal)
  }
}

export async function handleView(req: ViewRequest, ctx: ServerContext): Promise<ProtoViewEffect> {
  if (req.sourceId !== undefined) return handleKeyedView(req, req.sourceId, ctx)
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
        return create(ViewEffectSchema, { effect: { case: "updateRow", value: allowingLookup(() => encodePayload(registered.rowCodec, effect.row)) } })
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

const DONE = () => create(ConsumerEffectSchema, { effect: { case: "done", value: {} } })
const IGNORE = () => create(ConsumerEffectSchema, { effect: { case: "ignore", value: {} } })

/**
 * Several messages as the reply `produce_all`, which only a runtime at protocol 1.3 understands: the
 * request must have said so. None at all is `done`, which every runtime understands and means the same.
 */
function produceAll(request: Metadata, messages: { payload: ReturnType<typeof encodePayload>; metadata: Metadata; key?: string }[]): ProtoConsumerEffect {
  if (messages.length === 0) return DONE()
  requireSeveralMessages(request)
  return create(ConsumerEffectSchema, {
    effect: {
      case: "produceAll",
      value: { messages: messages.map((m) => ({ payload: m.payload, metadata: metadataToProto(m.metadata), ...(m.key !== undefined ? { key: m.key } : {}) })) },
    },
  })
}

async function graphEffect(registered: RegisteredConsumer, req: ConsumerRequest, metadata: Metadata, ctx: ServerContext): Promise<ProtoConsumerEffect> {
  const consumer = new registered.cls() as GraphConsumer<unknown>
  consumer._bind(metadata, ctx.client.withMetadata(metadata))
  const effect = req.deleted ? await consumer.onDelete() : await consumer.onMessage(decodePayload(registered.messageCodec, req.message))
  switch (effect.kind) {
    case "publish":
      return produceAll(
        metadata,
        deltaRecords(effect, metadata).map((r) => ({
          payload: create(PayloadSchema, { contentType: JSON_CONTENT, manifest: GRAPH_DELTA_SCHEMA, data: r.value }),
          metadata: { "ce-type": GRAPH_DELTA_SCHEMA },
          key: r.key,
        })),
      )
    case "done":
      return DONE()
    case "ignore":
      return IGNORE()
    default:
      throw new TypeError(`${registered.id}.onMessage returned something that is not a graph effect`)
  }
}

async function consumerEffect(registered: RegisteredConsumer, req: ConsumerRequest, metadata: Metadata, ctx: ServerContext): Promise<ProtoConsumerEffect> {
  const consumer = new registered.cls() as Consumer<unknown, unknown>
  consumer._bind(metadata, ctx.client.withMetadata(metadata))
  const effect = req.deleted ? await consumer.onDelete() : await consumer.onMessage(decodePayload(registered.messageCodec, req.message))
  const outCodec = () => {
    if (!registered.outCodec) throw new Error(`${registered.id} produced a message but declares no out shape`)
    return registered.outCodec
  }
  switch (effect.kind) {
    case "produce":
      return create(ConsumerEffectSchema, { effect: { case: "produce", value: { payload: encodePayload(outCodec(), effect.payload), metadata: metadataToProto(effect.metadata) } } })
    case "produceAll":
      return produceAll(
        metadata,
        effect.messages.map((m) => ({ payload: encodePayload(outCodec(), m.payload), metadata: m.metadata ?? {}, ...(m.key !== undefined ? { key: m.key } : {}) })),
      )
    case "done":
      return DONE()
    case "ignore":
      return IGNORE()
    default:
      throw new TypeError(`${registered.id}.onMessage returned something that is not a consumer effect`)
  }
}

export async function handleConsumer(req: ConsumerRequest, ctx: ServerContext): Promise<ProtoConsumerEffect> {
  const registered = ctx.registry.of("consumer", req.componentId)
  if (!registered) throw new ConnectError(`no consumer ${JSON.stringify(req.componentId)} is registered`, Code.NotFound)
  const metadata = metadataFromProto(req.metadata)
  try {
    return registered.graph ? await graphEffect(registered, req, metadata, ctx) : await consumerEffect(registered, req, metadata, ctx)
  } catch (e) {
    if (e instanceof ConnectError) throw e
    ctx.log(`ankka: consumer ${registered.id} on ${metadata["ce-subject"] ?? "?"} threw: ${messageOf(e)}`)
    // The message alone, so the runtime's log says what the SDK needs and of which runtime.
    if (e instanceof ProtocolVersionError) throw new ConnectError(e.message, Code.Internal)
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
