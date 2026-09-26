// Renders the discovery Spec from the registry: what the sidecar hosts is exactly what is registered.

import { create, type MessageInitShape } from "@bufbuild/protobuf"
import { Endpoint_Acl, SpecSchema, type Spec, type ComponentSchema, type EndpointSchema, type SourceSchema, type WorkflowDetail_SettingsSchema, type WorkflowDetail_RecoverySchema } from "./_proto/ankka/protocol/v1/discovery_pb.ts"
import type { Registry, RegisteredComponent, Source } from "./service.ts"
import type { HandlerRef } from "./handlers.ts"
import type { Recovery, WorkflowSettings } from "./effects/workflow.ts"
import { kindToProto } from "./kinds.ts"
import { Acl } from "./routes.ts"
import { toJsonSchema } from "./schema.ts"
import { isCodec } from "./codec.ts"
import { VERSION } from "./version.ts"

export const PROTOCOL_VERSION = "1.0"
export const SDK_NAME = "ankka-typescript"

export function aclToProto(acl: Acl): Endpoint_Acl {
  switch (acl) {
    case Acl.allowAll:
      return Endpoint_Acl.ALLOW_ALL
    case Acl.denyAll:
      return Endpoint_Acl.DENY_ALL
    case Acl.authenticated:
      return Endpoint_Acl.AUTHENTICATED
  }
}

type ComponentInit = MessageInitShape<typeof ComponentSchema>
type EndpointInit = MessageInitShape<typeof EndpointSchema>
type SourceInit = MessageInitShape<typeof SourceSchema>
type SettingsInit = MessageInitShape<typeof WorkflowDetail_SettingsSchema>
type RecoveryInit = MessageInitShape<typeof WorkflowDetail_RecoverySchema>

function handlerInits(handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>) {
  return [...handlers.values()]
    .map((h) => ({ name: h.name, readOnly: h.readOnly, streaming: h.streaming }))
    .sort((a, b) => a.name.localeCompare(b.name))
}

function sourceInit(source: Source): SourceInit {
  return "topic" in source
    ? { source: { case: "topic", value: source.topic } }
    : { source: { case: "component", value: { kind: kindToProto(source.component.kind), id: source.component.id } } }
}

function recoveryInit(r: Recovery): RecoveryInit {
  return { maxRetries: r.maxRetries, ...(r.failoverTo !== undefined ? { failoverTo: r.failoverTo } : {}) }
}

function settingsInit(s: WorkflowSettings): SettingsInit {
  return {
    ...(s.timeout ? { timeoutMillis: BigInt(s.timeout.toMillis()) } : {}),
    ...(s.defaultStepTimeout ? { defaultStepTimeoutMillis: BigInt(s.defaultStepTimeout.toMillis()) } : {}),
    ...(s.defaultRecovery ? { defaultRecovery: recoveryInit(s.defaultRecovery) } : {}),
    steps: Object.entries(s.steps ?? {})
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([step, st]) => ({
        step,
        ...(st.timeout ? { timeoutMillis: BigInt(st.timeout.toMillis()) } : {}),
        ...(st.recovery ? { recovery: recoveryInit(st.recovery) } : {}),
      })),
  }
}

function componentInit(c: RegisteredComponent): ComponentInit {
  const base = { kind: kindToProto(c.kind), id: c.id }
  switch (c.kind) {
    case "event-sourced":
      return { ...base, handlers: handlerInits(c.handlers), detail: { case: "eventSourced", value: { snapshotEvery: c.snapshotEvery } } }
    case "key-value":
      return { ...base, handlers: handlerInits(c.handlers), detail: { case: "keyValue", value: {} } }
    case "workflow":
      return {
        ...base,
        handlers: handlerInits(c.handlers),
        detail: { case: "workflow", value: { steps: [...c.steps.keys()].sort(), ...(c.settings ? { settings: settingsInit(c.settings) } : {}) } },
      }
    case "view":
      return { ...base, handlers: [], detail: { case: "view", value: { source: sourceInit(c.source), rowManifest: c.rowCodec.manifest, queries: [...c.queries] } } }
    case "consumer":
      return { ...base, handlers: [], detail: { case: "consumer", value: { source: sourceInit(c.source), ...(c.producesTo !== undefined ? { producesTo: c.producesTo } : {}) } } }
    case "timed-action":
      return { ...base, handlers: handlerInits(c.actions), detail: { case: "timedAction", value: {} } }
    case "agent":
      return {
        ...base,
        handlers: handlerInits(c.handlers),
        detail: {
          case: "agent",
          value: {
            role: c.role,
            maxToolCallSteps: c.maxToolCallSteps,
            tools: [...c.tools.values()]
              .sort((a, b) => a.name.localeCompare(b.name))
              .map((t) => ({ name: t.name, description: t.description, inputSchemaJson: JSON.stringify(isCodec(t.input) ? { type: "object" } : toJsonSchema(t.input)) })),
            guardrails: [...c.guardrails.keys()].sort(),
          },
        },
      }
  }
}

export function renderSpec(registry: Registry): Spec {
  const components: ComponentInit[] = [...registry.components.values()].sort((a, b) => a.id.localeCompare(b.id)).map(componentInit)
  const endpoints: EndpointInit[] = [...registry.endpoints.values()].map((e) => ({
    id: e.id,
    prefix: e.prefix,
    acl: aclToProto(e.acl),
    routes: [...e.routes.entries()].map(([id, r]) => ({
      id,
      method: r.method,
      template: r.template,
      hasBody: r.body !== undefined,
      streaming: r.streaming,
      ...(r.acl !== undefined ? { acl: aclToProto(r.acl) } : {}),
    })),
  }))
  return create(SpecSchema, {
    protocolVersion: PROTOCOL_VERSION,
    sdk: { name: SDK_NAME, version: VERSION },
    components,
    endpoints,
  })
}
