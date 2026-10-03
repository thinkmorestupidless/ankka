// Renders the discovery Spec from the registry: what the sidecar hosts is exactly what is registered.

import { create, type MessageInitShape } from "@bufbuild/protobuf"
import { Endpoint_Acl, SpecSchema, StartFrom_Named, type CallerMatcherSchema, type Spec, type ComponentSchema, type EndpointSchema, type SourceSchema, type WorkflowDetail_SettingsSchema, type WorkflowDetail_RecoverySchema, type AutonomousAgentDetail_AutonomousSettingsSchema } from "./_proto/ankka/protocol/v1/discovery_pb.ts"
import type { Registry, RegisteredAutonomousAgent, RegisteredComponent, Source } from "./service.ts"
import { resultSchemaJson, type AutonomousSettings } from "./autonomous.ts"
import type { HandlerRef } from "./handlers.ts"
import type { Recovery, WorkflowSettings } from "./effects/workflow.ts"
import { kindToProto } from "./kinds.ts"
import type { Acl, CallerMatcher } from "./routes.ts"
import { toJsonSchema } from "./schema.ts"
import { isCodec } from "./codec.ts"
import { VERSION } from "./version.ts"

export const PROTOCOL_VERSION = "1.4"
export const SDK_NAME = "ankka-typescript"

export function aclToProto(acl: Acl): Endpoint_Acl {
  if (typeof acl === "object") return Endpoint_Acl.CALLERS
  switch (acl) {
    case "allow-all":
      return Endpoint_Acl.ALLOW_ALL
    case "deny-all":
      return Endpoint_Acl.DENY_ALL
    case "authenticated":
      return Endpoint_Acl.AUTHENTICATED
  }
}

/** The callers a CALLERS acl names, as discovery carries them; empty for every other rule. */
export function callersToProto(acl: Acl): MessageInitShape<typeof CallerMatcherSchema>[] {
  if (typeof acl !== "object") return []
  return acl.callers.map((m: CallerMatcher): MessageInitShape<typeof CallerMatcherSchema> => {
    switch (m.kind) {
      case "internet":
        return { kind: { case: "internet", value: {} } }
      case "anyInProject":
        return { kind: { case: "anyInProject", value: {} } }
      case "self":
        return { kind: { case: "self", value: {} } }
      case "service":
        return { kind: { case: "service", value: m.project !== undefined ? { name: m.name, project: m.project } : { name: m.name } } }
    }
  })
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
  if (!("topic" in source)) return { source: { case: "component", value: { kind: kindToProto(source.component.kind), id: source.component.id } } }
  const start = source.startFrom
  if (start === undefined) return { source: { case: "topic", value: source.topic } }
  const position =
    start.kind === "at"
      ? { case: "atMillis" as const, value: BigInt(start.atMillis) }
      : { case: "named" as const, value: start.kind === "earliest" ? StartFrom_Named.EARLIEST : StartFrom_Named.LATEST }
  return { source: { case: "topic", value: source.topic }, startFrom: { position } }
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
    case "autonomous-agent":
      return autonomousInit(c)
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
      return {
        ...base,
        handlers: [],
        detail: {
          case: "view",
          value: { source: sourceInit(c.source), rowManifest: c.rowCodec.manifest, queries: [...c.queries], ...(c.version !== undefined ? { version: c.version } : {}) },
        },
      }
    case "consumer":
      return {
        ...base,
        handlers: [],
        detail: {
          case: "consumer",
          value: {
            source: sourceInit(c.source),
            ...(c.producesTo !== undefined ? { producesTo: c.producesTo } : {}),
            ...(c.version !== undefined ? { version: c.version } : {}),
          },
        },
      }
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

function settingsOf(s: AutonomousSettings): MessageInitShape<typeof AutonomousAgentDetail_AutonomousSettingsSchema> {
  return {
    ...(s.approachingBudgetAt !== undefined ? { approachingBudgetAt: s.approachingBudgetAt } : {}),
    ...(s.repeatedFailureAt !== undefined ? { repeatedFailureAt: s.repeatedFailureAt } : {}),
    ...(s.maxConsecutiveFailures !== undefined ? { maxConsecutiveFailures: s.maxConsecutiveFailures } : {}),
    ...(s.dependencyStuckAfterMillis !== undefined ? { dependencyStuckAfterMillis: BigInt(s.dependencyStuckAfterMillis) } : {}),
  }
}

function autonomousInit(c: RegisteredAutonomousAgent): ComponentInit {
  const cls = c.cls
  return {
    kind: kindToProto(c.kind),
    id: c.id,
    handlers: [],
    detail: {
      case: "autonomousAgent",
      value: {
        description: cls.description,
        ...(cls.instructions !== undefined ? { instructions: cls.instructions } : {}),
        ...(cls.model !== undefined ? { model: cls.model } : {}),
        tools: [...c.tools.values()]
          .sort((a, b) => a.name.localeCompare(b.name))
          .map((t) => ({ name: t.name, description: t.description, inputSchemaJson: JSON.stringify(isCodec(t.input) ? { type: "object" } : toJsonSchema(t.input)) })),
        guardrails: [...c.guardrails.keys()].sort(),
        taskTypes: [...c.taskTypes.values()].map((t) => {
          const schema = resultSchemaJson(t)
          return { name: t.name, description: t.description, rules: t.rules.map((r) => r.name), ...(schema !== undefined ? { resultSchemaJson: schema } : {}) }
        }),
        accepts: cls.accepts.map((a) => ({ taskType: a.taskType.name, maxIterations: a.maxIterations })),
        ...(cls.settings ? { settings: settingsOf(cls.settings) } : {}),
      },
    },
  }
}

export function renderSpec(registry: Registry): Spec {
  const components: ComponentInit[] = [...registry.components.values()].sort((a, b) => a.id.localeCompare(b.id)).map(componentInit)
  const endpoints: EndpointInit[] = [...registry.endpoints.values()].map((e) => ({
    id: e.id,
    prefix: e.prefix,
    acl: aclToProto(e.acl),
    allowCallers: callersToProto(e.acl),
    routes: [...e.routes.entries()].map(([id, r]) => ({
      id,
      method: r.method,
      template: r.template,
      hasBody: r.body !== undefined,
      streaming: r.streaming,
      ...(r.acl !== undefined ? { acl: aclToProto(r.acl), allowCallers: callersToProto(r.acl) } : {}),
    })),
  }))
  return create(SpecSchema, {
    protocolVersion: PROTOCOL_VERSION,
    sdk: { name: SDK_NAME, version: VERSION },
    components,
    endpoints,
  })
}
