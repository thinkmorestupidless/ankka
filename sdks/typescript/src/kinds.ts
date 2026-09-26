// The component kinds, and the mappings between the SDK's words and the protocol's numbers.

import { Kind as ProtoKind } from "./_proto/ankka/protocol/v1/discovery_pb.ts"
import { ErrorCode as ProtoErrorCode } from "./_proto/ankka/protocol/v1/payload_pb.ts"
import { ErrorCode } from "./effects/common.ts"

export type ComponentKind = "event-sourced" | "key-value" | "workflow" | "view" | "consumer" | "timed-action" | "agent"

const KIND_TO_PROTO: Readonly<Record<ComponentKind, ProtoKind>> = Object.freeze({
  "event-sourced": ProtoKind.EVENT_SOURCED_ENTITY,
  "key-value": ProtoKind.KEY_VALUE_ENTITY,
  workflow: ProtoKind.WORKFLOW,
  view: ProtoKind.VIEW,
  consumer: ProtoKind.CONSUMER,
  "timed-action": ProtoKind.TIMED_ACTION,
  agent: ProtoKind.AGENT,
})

export function kindToProto(kind: ComponentKind): ProtoKind {
  return KIND_TO_PROTO[kind]
}

export function kindFromProto(kind: ProtoKind): ComponentKind {
  for (const [k, v] of Object.entries(KIND_TO_PROTO)) if (v === kind) return k as ComponentKind
  throw new RangeError(`unknown component kind ${kind}`)
}

const CODE_TO_PROTO: Readonly<Record<ErrorCode, ProtoErrorCode>> = Object.freeze({
  INTERNAL: ProtoErrorCode.INTERNAL,
  BAD_REQUEST: ProtoErrorCode.BAD_REQUEST,
  UNAUTHORIZED: ProtoErrorCode.UNAUTHORIZED,
  FORBIDDEN: ProtoErrorCode.FORBIDDEN,
  NOT_FOUND: ProtoErrorCode.NOT_FOUND,
  CONFLICT: ProtoErrorCode.CONFLICT,
  TIMEOUT: ProtoErrorCode.TIMEOUT,
  UNAVAILABLE: ProtoErrorCode.UNAVAILABLE,
})

export function errorCodeToProto(code: ErrorCode): ProtoErrorCode {
  return CODE_TO_PROTO[code]
}

export function errorCodeFromProto(code: ProtoErrorCode): ErrorCode {
  for (const [k, v] of Object.entries(CODE_TO_PROTO)) if (v === code) return k as ErrorCode
  return ErrorCode.Internal
}
