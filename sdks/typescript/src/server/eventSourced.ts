// The event sourced conversation: one bidirectional stream per loaded instance. The sidecar sends
// `Init` (with the recovered snapshot, if any), the events after it, then commands one at a time; we fold
// the events, run each command against the state we hold, and answer with the effect as data — the
// events to persist, the retention, and a reply computed from the state *after* those events.
//
// The generator's locals are the instance's whole state. Commands are awaited in arrival order inside
// it, which is what makes "one command in flight" true without a lock. When the stream ends — the
// sidecar passivated the instance or went away, and we cannot tell which — the locals go with it.

import type { ConnectRouter } from "@connectrpc/connect"
import { create, type MessageInitShape } from "@bufbuild/protobuf"
import {
  EventSourced,
  EventSourcedOutSchema,
  type EventSourcedIn,
  type EventSourcedIn_Command,
  type EventSourcedOut,
} from "../_proto/ankka/protocol/v1/event_sourced_pb.ts"
import type { RetentionSchema, OutcomeSchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import { codecFor } from "../codec.ts"
import { commandContext, metadataFromProto } from "../context.ts"
import { ErrorCode, type ErrorDetail, type Retention } from "../effects/common.ts"
import type { EventSourcedEffect } from "../effects/eventSourced.ts"
import type { EventSourcedEntity } from "../eventSourcedEntity.ts"
import { binaryCodecs } from "../codec.ts"
import { errorCodeToProto } from "../kinds.ts"
import { materialiseEventSourced } from "../materialise.ts"
import type { RegisteredEventSourced } from "../service.ts"
import { decodePayload, encodePayload } from "./payloads.ts"
import type { ServerContext } from "./server.ts"

type RetentionInit = MessageInitShape<typeof RetentionSchema>
type OutcomeInit = MessageInitShape<typeof OutcomeSchema>

/** Retention as the protocol carries it; `undefined` when the effect asked for none. */
export function retentionToProto(retention: Retention | null): RetentionInit | undefined {
  if (!retention) return undefined
  switch (retention.kind) {
    case "delete-now":
      return { retention: { case: "deleteNow", value: {} } }
    case "expire-after":
      return { retention: { case: "expireAfter", value: { millis: BigInt(retention.after.toMillis()) } } }
  }
}

function failure(commandId: bigint, error: ErrorDetail): EventSourcedOut {
  return create(EventSourcedOutSchema, { message: { case: "failure", value: { commandId, error: { message: error.message, code: errorCodeToProto(error.code) } } } })
}

function messageOf(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e)
}

export function eventSourcedRoutes(router: ConnectRouter, ctx: ServerContext): void {
  router.service(EventSourced, {
    handle: (requests) => handleEventSourced(requests, ctx),
  })
}

export async function* handleEventSourced(requests: AsyncIterable<EventSourcedIn>, ctx: ServerContext): AsyncIterable<EventSourcedOut> {
  let registered: RegisteredEventSourced | undefined
  let entity: EventSourcedEntity<unknown, unknown> | undefined
  let entityId = ""
  let state: unknown
  let sequence = 0n

  for await (const m of requests) {
    switch (m.message.case) {
      case "init": {
        const init = m.message.value
        const found = ctx.registry.component(init.componentId)
        if (!found || found.kind !== "event-sourced") {
          yield failure(0n, { message: `no event sourced entity ${JSON.stringify(init.componentId)} is registered`, code: ErrorCode.NotFound })
          return
        }
        registered = found
        entityId = init.entityId
        try {
          entity = new registered.cls() as EventSourcedEntity<unknown, unknown>
          entity._bindInstance(entityId)
          if (init.snapshot) {
            state = decodePayload(registered.stateCodec, init.snapshot.payload)
            sequence = init.snapshot.sequence
          } else {
            state = entity.emptyState()
            sequence = 0n
          }
        } catch (e) {
          ctx.log(`ankka: ${init.componentId}/${entityId}: recovery failed: ${messageOf(e)}`)
          yield failure(0n, { message: `recovery failed: ${messageOf(e)}`, code: ErrorCode.Internal })
          return
        }
        break
      }
      case "event": {
        if (!registered || !entity) {
          yield failure(0n, { message: "an event arrived before Init", code: ErrorCode.Internal })
          return
        }
        const ev = m.message.value
        try {
          state = entity.applyEvent(state, decodePayload(registered.eventCodec, ev.payload))
          sequence = ev.sequence
        } catch (e) {
          ctx.log(`ankka: ${registered.id}/${entityId}: replay of event ${ev.sequence} failed: ${messageOf(e)}`)
          yield failure(0n, { message: `replay of event ${ev.sequence} failed: ${messageOf(e)}`, code: ErrorCode.Internal })
          return
        }
        break
      }
      case "command": {
        if (!registered || !entity) {
          yield failure(m.message.value.id, { message: "a command arrived before Init", code: ErrorCode.Internal })
          return
        }
        const result = await runCommand(registered, entity, entityId, state, sequence, m.message.value, ctx)
        yield result.out
        if (result.newState !== undefined) {
          state = result.newState.state
          sequence = result.newState.sequence
        }
        break
      }
      default:
        break
    }
  }
}

interface CommandResult {
  out: EventSourcedOut
  /** The state to carry forward; absent when the command was refused or failed. */
  newState?: { state: unknown; sequence: bigint }
}

async function runCommand(
  registered: RegisteredEventSourced,
  entity: EventSourcedEntity<unknown, unknown>,
  entityId: string,
  state: unknown,
  sequence: bigint,
  cmd: EventSourcedIn_Command,
  ctx: ServerContext,
): Promise<CommandResult> {
  const handler = registered.handlers.get(cmd.name)
  if (!handler) return { out: failure(cmd.id, { message: `no handler ${JSON.stringify(cmd.name)} on ${registered.id}`, code: ErrorCode.NotFound }) }

  let input: unknown
  try {
    input = handler.input ? decodePayload(codecFor(handler.input), cmd.payload) : undefined
  } catch (e) {
    return { out: failure(cmd.id, { message: `${registered.id}/${cmd.name}: the input could not be decoded: ${messageOf(e)}`, code: ErrorCode.BadRequest }) }
  }

  const metadata = metadataFromProto(cmd.metadata)
  const context = commandContext(registered.id, entityId, sequence, metadata)
  let effect: EventSourcedEffect<unknown, unknown, unknown>
  entity._bindCommand(state, context, ctx.client.withMetadata(metadata))
  try {
    effect = (await handler.run(entity, input)) as EventSourcedEffect<unknown, unknown, unknown>
  } catch (e) {
    ctx.log(`ankka: ${registered.id}/${entityId}/${cmd.name} threw: ${messageOf(e)}`)
    return { out: failure(cmd.id, { message: messageOf(e), code: ErrorCode.Internal }) }
  } finally {
    entity._unbindCommand()
  }

  if (handler.readOnly && effect?.kind !== "read-only") {
    return { out: failure(cmd.id, { message: `${registered.id}/${cmd.name} is a query and returned a persisting effect`, code: ErrorCode.Internal }) }
  }

  try {
    const m = materialiseEventSourced(effect, state, (s, e) => entity.applyEvent(s, e))
    const events = m.events.map((e) => encodePayload(registered.eventCodec, e))
    const replyCodec = handler.reply ? codecFor(handler.reply) : binaryCodecs.done
    const outcome: OutcomeInit = m.error
      ? { outcome: { case: "error", value: { message: m.error.message, code: errorCodeToProto(m.error.code) } } }
      : m.noReply
        ? { outcome: { case: "noReply", value: {} } }
        : { outcome: { case: "reply", value: { payload: encodePayload(replyCodec, m.reply), metadata: { entries: [] } } } }
    const out = create(EventSourcedOutSchema, {
      message: {
        case: "reply",
        value: {
          commandId: cmd.id,
          events,
          retention: retentionToProto(m.retention),
          outcome,
          snapshot: cmd.snapshotRequested ? encodePayload(registered.stateCodec, m.newState) : undefined,
        },
      },
    })
    if (m.error) return { out }
    return { out, newState: { state: m.newState, sequence: sequence + BigInt(events.length) } }
  } catch (e) {
    ctx.log(`ankka: ${registered.id}/${entityId}/${cmd.name}: the effect could not be applied: ${messageOf(e)}`)
    return { out: failure(cmd.id, { message: messageOf(e), code: ErrorCode.Internal }) }
  }
}
