// The key value conversation: the event sourced one without a fold. `Init` carries the stored state, if
// any; each command's effect names the new state to store, or nothing.

import type { ConnectRouter } from "@connectrpc/connect"
import { create, type MessageInitShape } from "@bufbuild/protobuf"
import { KeyValue, KeyValueOutSchema, type KeyValueIn, type KeyValueIn_Command, type KeyValueOut } from "../_proto/ankka/protocol/v1/key_value_pb.ts"
import type { OutcomeSchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import { binaryCodecs, codecFor } from "../codec.ts"
import { commandContext, metadataFromProto } from "../context.ts"
import { ErrorCode, type ErrorDetail } from "../effects/common.ts"
import type { KeyValueEffect } from "../effects/keyValue.ts"
import type { KeyValueEntity } from "../keyValueEntity.ts"
import { errorCodeToProto } from "../kinds.ts"
import { materialiseKeyValue } from "../materialise.ts"
import type { RegisteredKeyValue } from "../service.ts"
import { retentionToProto } from "./eventSourced.ts"
import { decodePayload, encodePayload } from "./payloads.ts"
import type { ServerContext } from "./server.ts"

type OutcomeInit = MessageInitShape<typeof OutcomeSchema>

function failure(commandId: bigint, error: ErrorDetail): KeyValueOut {
  return create(KeyValueOutSchema, { message: { case: "failure", value: { commandId, error: { message: error.message, code: errorCodeToProto(error.code) } } } })
}

function messageOf(e: unknown): string {
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e)
}

export function keyValueRoutes(router: ConnectRouter, ctx: ServerContext): void {
  router.service(KeyValue, {
    handle: (requests) => handleKeyValue(requests, ctx),
  })
}

export async function* handleKeyValue(requests: AsyncIterable<KeyValueIn>, ctx: ServerContext): AsyncIterable<KeyValueOut> {
  let registered: RegisteredKeyValue | undefined
  let entity: KeyValueEntity<unknown> | undefined
  let entityId = ""
  let state: unknown

  for await (const m of requests) {
    switch (m.message.case) {
      case "init": {
        const init = m.message.value
        const found = ctx.registry.of("key-value", init.componentId)
        if (!found) {
          yield failure(0n, { message: `no key value entity ${JSON.stringify(init.componentId)} is registered`, code: ErrorCode.NotFound })
          return
        }
        registered = found
        entityId = init.entityId
        try {
          entity = new registered.cls() as KeyValueEntity<unknown>
          entity._bindInstance(entityId)
          state = init.state ? decodePayload(registered.stateCodec, init.state) : entity.emptyState()
        } catch (e) {
          ctx.log(`ankka: ${init.componentId}/${entityId}: recovery failed: ${messageOf(e)}`)
          yield failure(0n, { message: `recovery failed: ${messageOf(e)}`, code: ErrorCode.Internal })
          return
        }
        break
      }
      case "command": {
        if (!registered || !entity) {
          yield failure(m.message.value.id, { message: "a command arrived before Init", code: ErrorCode.Internal })
          return
        }
        const result = await runCommand(registered, entity, entityId, state, m.message.value, ctx)
        yield result.out
        if (result.newState !== undefined) state = result.newState
        break
      }
      default:
        break
    }
  }
}

async function runCommand(
  registered: RegisteredKeyValue,
  entity: KeyValueEntity<unknown>,
  entityId: string,
  state: unknown,
  cmd: KeyValueIn_Command,
  ctx: ServerContext,
): Promise<{ out: KeyValueOut; newState?: unknown }> {
  const handler = registered.handlers.get(cmd.name)
  if (!handler) return { out: failure(cmd.id, { message: `no handler ${JSON.stringify(cmd.name)} on ${registered.id}`, code: ErrorCode.NotFound }) }

  let input: unknown
  try {
    input = handler.input ? decodePayload(codecFor(handler.input), cmd.payload) : undefined
  } catch (e) {
    return { out: failure(cmd.id, { message: `${registered.id}/${cmd.name}: the input could not be decoded: ${messageOf(e)}`, code: ErrorCode.BadRequest }) }
  }

  const metadata = metadataFromProto(cmd.metadata)
  const context = commandContext(registered.id, entityId, 0n, metadata)
  let effect: KeyValueEffect<unknown, unknown>
  entity._bindCommand(state, context, ctx.client.withMetadata(metadata))
  try {
    effect = (await handler.run(entity, input)) as KeyValueEffect<unknown, unknown>
  } catch (e) {
    ctx.log(`ankka: ${registered.id}/${entityId}/${cmd.name} threw: ${messageOf(e)}`)
    return { out: failure(cmd.id, { message: messageOf(e), code: ErrorCode.Internal }) }
  } finally {
    entity._unbindCommand()
  }

  if (handler.readOnly && effect?.kind !== "read-only") {
    return { out: failure(cmd.id, { message: `${registered.id}/${cmd.name} is a query and returned an updating effect`, code: ErrorCode.Internal }) }
  }

  try {
    const m = materialiseKeyValue(effect, state)
    const replyCodec = handler.reply ? codecFor(handler.reply) : binaryCodecs.done
    const outcome: OutcomeInit = m.error
      ? { outcome: { case: "error", value: { message: m.error.message, code: errorCodeToProto(m.error.code) } } }
      : m.noReply
        ? { outcome: { case: "noReply", value: {} } }
        : { outcome: { case: "reply", value: { payload: encodePayload(replyCodec, m.reply), metadata: { entries: [] } } } }
    const out = create(KeyValueOutSchema, {
      message: {
        case: "reply",
        value: {
          commandId: cmd.id,
          newState: m.changed && !m.error ? encodePayload(registered.stateCodec, m.newState) : undefined,
          retention: m.error ? undefined : retentionToProto(m.retention),
          outcome,
        },
      },
    })
    if (m.error) return { out }
    return { out, newState: m.newState }
  } catch (e) {
    ctx.log(`ankka: ${registered.id}/${entityId}/${cmd.name}: the effect could not be applied: ${messageOf(e)}`)
    return { out: failure(cmd.id, { message: messageOf(e), code: ErrorCode.Internal }) }
  }
}
