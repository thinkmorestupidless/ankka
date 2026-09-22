# Data Model: Polyglot Runtimes

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Four places hold data in this feature: the protocol (messages that cross the loopback), the
runtime's remote descriptors and hosts (what the sidecar keeps per instance), the resource and
descriptor (what the platform records), and the SDK's own values (what a developer writes). The
protocol messages are the contract; the rest are derived from them. Field-level detail of the
messages is in [contracts/protocol.md](./contracts/protocol.md).

## Protocol (package `ankka.protocol.v1`)

### Shared

| message | fields | notes |
|---|---|---|
| `Payload` | `content_type: string`, `manifest: string`, `data: bytes` | opaque to the sidecar; `manifest` is the serializer's manifest and is what lands in `JournalRecord` (R4) |
| `Metadata` | `entries: repeated {key, value}` | ankka's `Metadata`; carries trace ids across the boundary so a handler's span parents correctly |
| `Failure` | `command_id: int64`, `message: string`, `code: ErrorCode` | a *fault* in the process, distinct from an `Outcome.error`, which is a refusal |
| `ErrorCode` | enum mirroring `core.ErrorCode`: `BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, TIMEOUT, UNAVAILABLE, INTERNAL` | |
| `Outcome` | `oneof: reply{payload, metadata} \| no_reply{} \| error{message, code}` | the three cases of `core.effect.Outcome`, as data |
| `Retention` | `oneof: delete_now{} \| expire_after{millis}` | `core.effect.Retention` |

### Discovery

| message | fields |
|---|---|
| `SidecarInfo` | `protocol_version: string`, `runtime_version: string` |
| `Spec` | `protocol_version: string`, `sdk: {name, version}`, `components: repeated Component` |
| `Component` | `kind: Kind`, `id: string`, `handlers: repeated Handler`, `oneof detail: event_sourced \| key_value \| workflow \| view \| consumer \| timed_action \| agent` |
| `Handler` | `name: string` (the wire name), `read_only: bool`, `streaming: bool` |
| `EventSourcedDetail` | `snapshot_every: int32` (0 = never) |
| `KeyValueDetail` | *(empty)* |
| `WorkflowDetail` | `steps: repeated string` |
| `ViewDetail` | `source: Source`, `row_manifest: string` |
| `ConsumerDetail` | `source: Source`, `produces_to: optional string` (topic) |
| `Source` | `oneof: component{kind, id} \| topic{name}` |
| `TimedActionDetail` | *(empty; handlers carry the names)* |
| `AgentDetail` | `role: string`, `max_tool_call_steps: int32`, `tools: repeated Tool`, `guardrails: repeated string` |
| `Tool` | `name: string`, `description: string`, `input_schema_json: string` |
| `Problem` | `message: string` — sent by `ReportError` so the refusal appears in the process's log |

Validation on the sidecar (FR-007, S1.7): duplicate `(kind, id)`, duplicate handler name within a
component, a `read_only` handler that is also `streaming`, a `Kind` the sidecar cannot host, an
unknown `Source` component, a `protocol_version` outside the supported range (FR-008) — every
problem reported at once, through `ReportError` and the sidecar's own log, and the sidecar exits.

### Per-instance conversations (stateful kinds)

`EventSourced.Handle`, `KeyValue.Handle`, `Workflow.Handle`: one bidirectional stream per loaded
instance. The sidecar sends `In`, the process answers `Out`.

| `EventSourcedIn` | `oneof: init{component_id, entity_id, snapshot?{sequence, payload}} \| event{sequence, payload} \| command{id, name, payload, metadata, snapshot_requested}` |
|---|---|
| `EventSourcedOut` | `oneof: reply{command_id, events: repeated Payload, retention?, outcome, snapshot?} \| failure` |
| `KeyValueIn` | `oneof: init{component_id, entity_id, state?} \| command{id, name, payload, metadata}` |
| `KeyValueOut` | `oneof: reply{command_id, new_state?, retention?, outcome} \| failure` |
| `WorkflowIn` | `oneof: init{component_id, entity_id, state?} \| command{id, name, payload, metadata} \| run_step{id, step, input?}` |
| `WorkflowOut` | `oneof: reply{command_id, new_state?, transition?, outcome} \| step_reply{command_id, new_state?, next: StepOutcome} \| failure` |
| `StepOutcome` | `oneof: transition_to{step, input?} \| pause{after_millis?, on_timeout?{step, input?}} \| end{} \| fail{message, code}` — `core.effect.StepOutcome` |

Invariants the sidecar enforces: one command in flight per stream; `command_id` on an `Out` must
be the id in flight or the message is a violation; a `reply.events` non-empty from a `read_only`
handler is a violation; a violation fails the command, closes the stream and restarts the
instance (edge cases in the spec). Ids are per stream, monotonic, starting at 1.

### Stateless conversations

| rpc | request | response |
|---|---|---|
| `View.Handle` | `{component_id, source_event: Payload, metadata, row?: Payload}` | `oneof: update_row{row} \| delete_row{} \| ignore{}` — `core.effect.ViewEffect` |
| `Consumer.Handle` | `{component_id, message: Payload, metadata}` | `oneof: produce{payload, metadata} \| done{} \| ignore{}` — `ConsumerEffect` |
| `TimedAction.Invoke` | `{component_id, name, payload, metadata}` | `oneof: done{} \| fail{message, code}` — `TimedActionEffect` |
| `Agent.Plan` | `{component_id, session_id, name, payload, metadata}` | `AgentReply` or `failure` |
| `Agent.InvokeTool` | `{component_id, session_id, tool, arguments_json}` | `oneof: ok{result: string} \| error{message: string}` — `FunctionTool.invoke`'s `Either` |
| `Agent.CheckGuardrail` | `{component_id, session_id, guardrail, stage: INPUT \| OUTPUT, text}` | `oneof: pass{} \| block{reason}` |

| `AgentReply` | `model?: string`, `system?: string`, `user?: string`, `context: repeated string`, `memory: SESSION \| NONE`, `tools: repeated string` (names from discovery), `response_shape: oneof text{} \| json{schema_hint}`, `guardrails: repeated string`, `failure?: {message, code}` |
|---|---|

Everything in `AgentReply` is a field of `AgentEffect` today; nothing is added.

### The callback service (`Client`, served by the sidecar)

| rpc | request | response |
|---|---|---|
| `Client.Invoke` | `{kind, component_id, entity_id, name, payload, metadata}` | `oneof: reply{payload, metadata} \| error{message, code}` — `CallTransport.ask` |
| `Client.InvokeStream` | same | stream of `oneof: token{text} \| completed{} \| failed{message, code}` — `EntityProtocol.StreamToken` |
| `Client.Query` | `{view_id, name, payload}` | `oneof: rows{payload} \| error` — `ViewClient` |
| `Client.Schedule` | `{timer_id, delay_millis, kind, component_id, entity_id?, name, payload}` | `{}` or `error` — the timed-action scheduling surface |
| `Client.Cancel` | `{timer_id}` | `{}` |

`metadata` on `Invoke` carries the calling handler's trace and span ids, which the SDK copies
from the metadata it received, so a nested call is a child span (S3.5).

## Runtime: remote descriptors and hosts (`modules/runtime`, package `remote`)

| type | fields | derived from |
|---|---|---|
| `RemoteEventSourcedDescriptor` | `componentId`, `handlers: Map[MethodName, RemoteHandler]`, `snapshotEvery: Option[Int]` | `Component` with `event_sourced` |
| `RemoteKeyValueDescriptor` | `componentId`, `handlers` | |
| `RemoteWorkflowDescriptor` | `componentId`, `handlers`, `steps: Set[String]` | |
| `RemoteViewDescriptor` | `componentId`, `source: RemoteSource`, `rowManifest: String` | |
| `RemoteConsumerDescriptor` | `componentId`, `source`, `producesTo: Option[String]` | |
| `RemoteTimedActionDescriptor` | `componentId`, `handlers` | |
| `RemoteHandler` | `name: MethodName`, `readOnly: Boolean`, `streaming: Boolean` | `Handler` |
| `RemoteSource` | `Component(kind, id) \| Topic(name)` | `Source` |

All extend `ComponentDescriptor` (so `ComponentRegistry.from` validates them) and carry no
functions. The agent's remote descriptor is an `AgentDescriptor` built in `sidecar` (it needs
`agent`), not here.

`Conversation` (trait, `runtime`; implemented in `sidecar`):

```
open(component: ComponentId, entity: EntityId, init: Init): InstanceSession
InstanceSession.command(cmd: Command): Future[Reply]      // exactly one in flight
InstanceSession.runStep(step, input): Future[StepReply]    // workflows
InstanceSession.close(): Unit                              // passivation, or a violation
handleView(...)  handleConsumer(...)  invokeTimedAction(...)   // stateless
reachable: () => Boolean                                   // for readiness
```

Per-instance state kept by the sidecar's remote hosts:

| host | persisted state | journal | in-memory only |
|---|---|---|---|
| `RemoteEventSourcedHost` | `RemoteStored(snapshot: Option[Payload], sinceSnapshot: Int, deleted, expiryMillis)` | `Journaled.Domain(Payload) \| Deleted \| Expiry` — same manifest and record shape as today | the open `InstanceSession`, the in-flight command id, the stash |
| `RemoteKeyValueHost` | `RemoteState(value: Option[Payload], deleted, expiryMillis)` | durable state, same `StateRecord` | session, in-flight id |
| `RemoteWorkflowHost` | `WorkflowEngine.Run[Payload]` — the engine is unchanged, `S = Payload` | the engine's own events | session; the running step's `Future` |

Replay to the process on `open`: snapshot (if any), then every `Journaled.Domain` after it, in
sequence order, read from the journal with the same query the in-process recovery uses; the host
does not hold the events in memory, and a recovery of ten thousand events streams them.

`RemoteEffect.materialise(reply, handler)`: the one reduction, returning `(events to persist,
retention, reply-or-refusal, snapshot to store)`, refusing events from a `readOnly` handler and a
`command_id` mismatch as `ProtocolViolation`.

## Sidecar (`sidecar`)

| type | fields |
|---|---|
| `Settings` | `processAddress` (`ANKKA_PROCESS_ADDRESS`, default `127.0.0.1:9010`), `callbackPort` (`ANKKA_SIDECAR_PORT`, default `9011`, bound to `127.0.0.1` only), `discoveryTimeout`, `commandTimeout` (defaults to `ankka.ask-timeout`), `stepTimeout` per workflow settings |
| `Discovered` | `spec: Spec`, `descriptors: Vector[ComponentDescriptor]`, `agents: Vector[AgentDescriptor]` |
| `SidecarExtension` | `readiness = discovered && conversation.reachable()`; `boundAddress` = the HTTP address; `routes` = the generic invoke routes |

The sidecar is the platform's `runtime`, so its cluster formation, observability, projections and
timers are the existing ones with no new state.

## Descriptor and resource

`controlplane-api.ServiceSpec` (wire, validated at both ends):

| field | type | default | rule |
|---|---|---|---|
| `hosting` | `"embedded" \| "process"` | `"embedded"` | any other value refused |
| `protocol` | `Option[String]` | `None` | parsed as `Version`; only meaningful with `hosting = "process"`; `Compatibility.supportsProtocol(platformProtocol, declared)` — same major — otherwise the projector refuses as it does an unsupported `runtime` |
| `env` | unchanged | | additionally refuses `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS`, `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS` (FR-016) |
| `http`, `port` | unchanged | | with `hosting = "process"` they describe the *app* container; `http = false` means the sidecar's own HTTP is the service's only HTTP |

`crd.AnkkaServiceSpec`:

| field | type | default | rendered as |
|---|---|---|---|
| `hosting` | `String` | `"embedded"` | one container, or two |
| `port` | unchanged `Option[Int]` | | the `app` container's port when `hosting = "process"` and `http` is true |

`crd.AnkkaServiceStatus` gains nothing: readiness, route and database status fold the same way,
because the pod's readiness already reflects both containers.

`ServiceRow` / `services get` output gains `hosting` and `protocol` (display only).

## The TypeScript SDK's values

Mirrors of the Scala SDK's, named for the developer rather than the wire:

| value | shape |
|---|---|
| `EventSourcedEntity<S, E>` companion | `{ id, emptyState: () => S, applyEvent: (s: S, e: E) => S, snapshotEvery?, handlers: { [wireName]: { readOnly, run: (entity, input) => EventSourcedEffect } } }` |
| `EventSourcedEffect<S, E, R>` | `{ events: E[], retention?, outcome: Reply(s => R) \| NoReply \| Error(message, code) }` — inert |
| `KeyValueEffect`, `WorkflowEffect`, `StepEffect`, `ViewEffect`, `ConsumerEffect`, `TimedActionEffect`, `AgentEffect` | the same fields as the Scala values, as plain objects |
| `Codec<A>` | `{ manifest, encode: A => Uint8Array, decode: Uint8Array => A }` — `Serializer`; JSON by default |
| `ComponentClient` | `forEventSourcedEntity(id).call(handle).invoke(input)`: a `Promise` over `Client.Invoke` |
| `Service` | `Ankka.service().register(...).listen({ port })` |

State held by the SDK per loaded instance: the decoded state `S` and the sequence number, for the
life of the stream; released when the stream closes.
