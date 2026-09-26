// The ankka SDK for TypeScript on Node.js. See https://docs.ankka.cloud/reference/typescript-sdk/.

// The Node floor, checked before anything else is evaluated, so an old Node fails with a message that
// names the version rather than a syntax error from a construct it lacks.
{
  const required = "22.22.0"
  const [major, minor, patch] = process.versions.node.split(".").map(Number) as [number, number, number]
  const [rMajor, rMinor, rPatch] = required.split(".").map(Number) as [number, number, number]
  const ok = major > rMajor || (major === rMajor && (minor > rMinor || (minor === rMinor && patch >= rPatch)))
  if (!ok) throw new Error(`ankka needs Node.js ${required} or later; this is ${process.versions.node}`)
}

export { VERSION } from "./version.ts"
export { s, Done, done, SchemaError, toJsonSchema, defaultManifest, type Schema, type Infer } from "./schema.ts"
export { jsonCodec, defaultCodecFor, codecForManifest, textCodecs, binaryCodecs, type Codec, type Shape, type ContentType } from "./codec.ts"
export { EncodingError, DecodingError, renderDouble } from "./json.ts"
export { Instant, Duration, LocalDate, LocalDateTime } from "./time.ts"
export { ErrorCode, CommandError, Outcome, Retention, httpStatusOf, type ErrorDetail, type Metadata, type EffectLike, type ReadOnlyLike } from "./effects/common.ts"
export { command, query, stream, step, action, tool, guardrail, type HandlerRef, type ToolRef, type GuardrailRef, type GuardrailStage, type HandlerTable } from "./handlers.ts"
export { PersistBuilder, EventSourcedEffects, type PersistEffect, type ReadOnlyEffect, type EventSourcedEffect } from "./effects/eventSourced.ts"
export { EventSourcedEntity, type EventSourcedEntityClass } from "./eventSourcedEntity.ts"
export { UpdateBuilder, KeyValueEffects, type UpdateEffect, type KeyValueReadOnlyEffect, type KeyValueEffect } from "./effects/keyValue.ts"
export { KeyValueEntity, type KeyValueEntityClass } from "./keyValueEntity.ts"
export {
  WorkflowEffects, StepEffects, WorkflowUpdateBuilder, StepUpdateBuilder, workflowSettings,
  type WorkflowEffect, type WorkflowReadOnlyEffect, type WorkflowCommandEffect, type StepEffect, type StepOutcome, type StepRef, type PauseOptions,
  type WorkflowSettings, type StepSettings, type Recovery,
} from "./effects/workflow.ts"
export { Workflow, type WorkflowClass } from "./workflow.ts"
export { ViewEffects, ConsumerEffects, TimedActionEffects, type ViewEffect, type ConsumerEffect, type TimedActionEffect } from "./effects/stateless.ts"
export { View, type ViewClass } from "./view.ts"
export { Consumer, type ConsumerClass } from "./consumer.ts"
export { TimedAction, type TimedActionClass } from "./timedAction.ts"
export { AgentEffects, type AgentEffect } from "./effects/agent.ts"
export { Agent, type AgentClass } from "./agent.ts"
export { Endpoint, type EndpointClass } from "./endpoint.ts"
export { Acl, HttpProblem, get, post, put, patch, del, sse, type RouteRef, type RouteOptions, type RouteTable, type Params, type ParamNames, type HttpMethod } from "./routes.ts"
export { type CommandContext, type RequestContext, type Principal, Query, Headers } from "./context.ts"
export { ComponentClient, Calls, TypedCalls, Invocation, Views, Timers, type TimerTarget, type ComponentRef } from "./client.ts"
export {
  Ankka, ServiceBuilder, Registry, RegistrationError,
  type ServiceOptions, type RegisteredComponent, type RegisteredEndpoint, type Source,
  type RegisteredEventSourced, type RegisteredKeyValue, type RegisteredWorkflow, type RegisteredView, type RegisteredConsumer, type RegisteredTimedAction, type RegisteredAgent,
} from "./service.ts"
export { Server, type ServerOptions } from "./server/server.ts"
export { problems as sidecarProblems } from "./server/discovery.ts"
export { PROTOCOL_VERSION, SDK_NAME } from "./spec.ts"
export {
  materialiseEventSourced, materialiseKeyValue, materialiseWorkflowCommand, materialiseStep,
  type Materialised, type MaterialisedKeyValue, type MaterialisedWorkflowCommand, type MaterialisedStep,
} from "./materialise.ts"
export type { ComponentKind } from "./kinds.ts"
