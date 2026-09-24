# Data Model: Polyglot Runtimes

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Five places hold data in this feature: the protocol (messages that cross the loopback), the
encoding (what the bytes inside a payload mean), the runtime's remote descriptors and hosts (what
the sidecar keeps per instance), the resource and descriptor (what the platform records), and the
SDK's own values (what a developer writes). The protocol messages are the contract; the rest are
derived from them. Field-level detail of the messages is in
[contracts/protocol.md](./contracts/protocol.md).

## Protocol (package `ankka.protocol.v1`)

### Shared

| message | fields | notes |
|---|---|---|
| `Payload` | `content_type: string`, `manifest: string`, `data: bytes` | opaque to the sidecar; `manifest` is the serializer's manifest and is what lands in `JournalRecord` (R4) |
| `Metadata` | `entries: repeated {key, value}` | ankka's `Metadata`; carries trace ids across the boundary so a handler's span parents correctly |
| `Failure` | `command_id: int64`, `error: Error` | a *fault* in the process, distinct from an `Outcome.error`, which is a refusal |
| `Error` | `message: string`, `code: ErrorCode` | |
| `ErrorCode` | enum mirroring `core.ErrorCode` | |
| `Outcome` | `oneof: reply{payload, metadata} \| no_reply \| error` | the three cases of `core.effect.Outcome`, as data |
| `Retention` | `oneof: delete_now \| expire_after{millis}` | `core.effect.Retention` |

### Discovery

| message | fields |
|---|---|
| `SidecarInfo` | `protocol_version`, `runtime_version` |
| `Spec` | `protocol_version`, `sdk{name, version}`, `components: repeated Component`, `endpoints: repeated Endpoint` |
| `Component` | `kind`, `id`, `handlers: repeated Handler`, `oneof detail: event_sourced \| key_value \| workflow \| view \| consumer \| timed_action \| agent` |
| `Handler` | `name` (the wire name), `read_only`, `streaming` |
| `EventSourcedDetail` | `snapshot_every` (0 = never) |
| `WorkflowDetail` | `steps: repeated string` |
| `ViewDetail` | `source: Source`, `row_manifest`, `queries: repeated string` |
| `ConsumerDetail` | `source: Source`, `produces_to?` (topic) |
| `Source` | `oneof: component{kind, id} \| topic{name}` |
| `AgentDetail` | `role`, `max_tool_call_steps`, `tools: repeated Tool{name, description, input_schema_json}`, `guardrails: repeated string` |
| `Endpoint` | `id`, `prefix`, `acl: ALLOW_ALL \| DENY_ALL \| AUTHENTICATED`, `routes: repeated Route` |
| `Route` | `id`, `method`, `template` (the same syntax `HttpEndpoint` accepts: `/carts/{cartId}/items`), `has_body`, `streaming` |
| `Problem` | `message` — sent by `ReportError` so the refusal appears in the process's log |

Validation on the sidecar (FR-007, S1.7): duplicate `(kind, id)`, duplicate handler name within a
component, a `read_only` handler that is also `streaming`, a `Kind` the sidecar cannot host, an
unknown `Source` component, a view query not declared, an endpoint whose routes conflict
(`HttpServer.validate`), a route template that does not parse, a `protocol_version` outside the
supported range (FR-008) — every problem reported at once, through `ReportError` and the
sidecar's own log, and the sidecar exits.

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
| `StepOutcome` | `oneof: transition_to{step, input?} \| pause{after_millis?, on_timeout?} \| end \| fail{error}` — `core.effect.StepOutcome` |

Invariants the sidecar enforces: one command in flight per stream; `command_id` on an `Out` must
be the id in flight or the message is a violation; `reply.events` non-empty from a `read_only`
handler is a violation; a violation fails the command, closes the stream and restarts the
instance. Ids are per stream, monotonic, starting at 1.

### Stateless conversations

| rpc | request | response |
|---|---|---|
| `View.Handle` | `{component_id, event: Payload, metadata, row?: Payload}` | `oneof: update_row{row} \| delete_row \| ignore` |
| `Consumer.Handle` | `{component_id, message: Payload, metadata}` | `oneof: produce{payload, metadata} \| done \| ignore` |
| `TimedAction.Invoke` | `{component_id, name, payload, metadata}` | `oneof: done \| fail{error}` |
| `Endpoint.Handle` | `HttpRequest{endpoint_id, route_id, path_args: repeated string, query: repeated {name, value}, headers: repeated {name, value}, body: bytes, content_type, principal?, metadata}` | `HttpResponse{status, content_type, body, headers} \| failure` |
| `Endpoint.HandleStream` | the same `HttpRequest` | stream of `oneof: frame{text} \| completed \| failed{error}` |
| `Agent.Plan` | `{component_id, session_id, name, payload, metadata}` | `AgentPlan \| failure` |
| `Agent.InvokeTool` | `{component_id, session_id, tool, arguments_json}` | `oneof: ok{result} \| error{message}` |
| `Agent.CheckGuardrail` | `{component_id, session_id, guardrail, stage, text}` | `oneof: pass \| block{reason}` |

`HttpRequest.principal` is the `http.Principal` fields (subject, name, email, verified, roles) when
the endpoint's ACL is `AUTHENTICATED` and an authenticator is configured, absent otherwise. A
`failure` from `Endpoint.Handle` is a 500 with the message; an `HttpResponse` with any status is
returned as-is (a handler that wants a 404 answers 404).

### The callback service (`Client`, served by the sidecar)

| rpc | request | response |
|---|---|---|
| `Client.Invoke` | `{kind, component_id, entity_id, name, payload, metadata}` | `oneof: reply{payload, metadata} \| error` |
| `Client.InvokeStream` | same | stream of `oneof: token{text} \| completed \| failed{error}` |
| `Client.Query` | `{view_id, name, payload}` | `oneof: rows{payload} \| error` |
| `Client.Schedule` | `{timer_id, delay_millis, kind, component_id, entity_id?, name, payload}` | `{}` or `error` |
| `Client.Cancel` | `{timer_id}` | `{}` |

`metadata` on `Invoke` carries the calling handler's trace and span ids, which the SDK copies
from the metadata it received, so a nested call is a child span (S3.5).

## Encoding (`protocol/ENCODING.md`, R13)

What the bytes inside a `Payload` mean, by `content_type` and `manifest`. Defined as what
`core.Codecs.make` and `core.Serializers` produce today; every SDK's default codec matches it.

| shape | content type | encoding |
|---|---|---|
| a record (case class / dataclass) | `application/json` | a JSON object, field names as declared, every field written (no omission of empty or `None`) |
| a sum type (enum with cases / union of dataclasses) | `application/json` | the case's object with `"type": "<CaseName>"` added; position of `type` not significant; a fieldless case is `{"type":"CaseName"}` |
| `Option[A]` / `A \| None` inside JSON | `application/json` | `null` for none, `A` otherwise |
| collections, maps | `application/json` | arrays; objects with string keys; empty written |
| numbers | `application/json` | JSON numbers; integers beyond 2⁵³ are still written as numbers and must round-trip without loss |
| `java.time.Instant`, `Duration`, dates | `application/json` | ISO-8601 strings (jsoniter's defaults) |
| `String` (top level) | `text/plain` | UTF-8, manifest `string` |
| `Int`, `Long`, `Boolean`, `Double`, … (top level) | `text/plain` | decimal / `true`/`false` text, manifests `int`, `long`, `boolean`, `double`, … |
| `FiniteDuration` (top level) | `text/plain` | decimal milliseconds, manifest `duration-millis` |
| `Done`, `Unit` | `application/octet-stream` | zero bytes, manifests `done`, `unit` |
| `Option[A]` (top level) | `application/octet-stream` | zero bytes for none; one byte `0x01` then `A`'s bytes, manifest `option[<A's manifest>]` |
| `Array[Byte]` | `application/octet-stream` | as-is, manifest `bytes` |

Fixtures: `protocol/fixtures/<name>.json` = `{ "manifest", "content_type", "bytes_base64",
"value" }` where `value` is the decoded value in a language-neutral JSON form (for JSON payloads,
the same JSON; for primitives, the JSON scalar; for `Done`, `null`). Generated by
`EncodingFixturesSuite` in `core` from the shopping cart's, the planner's and the control plane's
domain types and the primitive serializers; the suite fails if regeneration changes a file.

## Runtime: remote descriptors and hosts (`modules/runtime`, package `remote`)

| type | fields | derived from |
|---|---|---|
| `RemoteEventSourcedDescriptor` | `componentId`, `handlers: Map[MethodName, RemoteHandler]`, `snapshotEvery: Option[Int]` | `Component` with `event_sourced` |
| `RemoteKeyValueDescriptor` | `componentId`, `handlers` | |
| `RemoteWorkflowDescriptor` | `componentId`, `handlers`, `steps: Set[String]` | |
| `RemoteViewDescriptor` | `componentId`, `source: RemoteSource`, `rowManifest`, `queries: Set[MethodName]` | |
| `RemoteConsumerDescriptor` | `componentId`, `source`, `producesTo: Option[String]` | |
| `RemoteTimedActionDescriptor` | `componentId`, `handlers` | |
| `RemoteHandler` | `name: MethodName`, `readOnly: Boolean`, `streaming: Boolean` | `Handler` |
| `RemoteSource` | `Component(kind, id) \| Topic(name)` | `Source` |

All extend `ComponentDescriptor` (so `ComponentRegistry.from` validates them) and carry no
functions. The agent's remote descriptor is an `AgentDescriptor` built in `sidecar` (it needs
`agent`); the remote endpoint is an `HttpEndpoint` built in `sidecar` (it needs `http`).

`Conversation` (trait, `runtime`; implemented in `sidecar`):

```
open(component: ComponentId, entity: EntityId, init: Init): InstanceSession
InstanceSession.command(cmd: Command): Future[Reply]      // exactly one in flight
InstanceSession.runStep(step, input): Future[StepReply]    // workflows
InstanceSession.close(): Unit                              // passivation, or a violation
handleView(...)  handleConsumer(...)  invokeTimedAction(...)   // stateless
handleHttp(request): Future[HttpResponse]  handleHttpStream(request): Source[String, ?]
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
| `Settings` | `processAddress` (`ANKKA_PROCESS_ADDRESS`, default `127.0.0.1:9010`), `callbackPort` (`ANKKA_SIDECAR_PORT`, default `9011`, bound to `127.0.0.1` only), `discoveryTimeout`, `commandTimeout` (defaults to `ankka.ask-timeout`), `requestTimeout` for forwarded HTTP (defaults to the HTTP server's body timeout) |
| `Discovered` | `spec: Spec`, `descriptors: Vector[ComponentDescriptor]`, `agents: Vector[AgentDescriptor]`, `endpoints: Vector[RemoteEndpoint]` |
| `RemoteEndpoint` | an `HttpEndpoint(prefix)` with `acl` from discovery and one `Route`/`StreamRoute` per declared route, each `run` forwarding over `Conversation.handleHttp` |
| `SidecarExtension` | `readiness = discovered && conversation.reachable()`; `boundAddress` = the HTTP address; `routes` = the declared routes, for the console |

## Descriptor and resource

`controlplane-api.ServiceSpec` (wire, validated at both ends):

| field | type | default | rule |
|---|---|---|---|
| `hosting` | `"embedded" \| "process"` | `"embedded"` | any other value refused |
| `protocol` | `Option[String]` | `None` | required with `hosting = "process"`, refused with `"embedded"`; parsed as `Version`; `Compatibility.supportsProtocol(platformProtocol, declared)` — same major, minor not above — otherwise the projector refuses as it does an unsupported `runtime` |
| `env` | unchanged | | additionally refuses `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS`, `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS` (FR-016) |
| `http`, `port` | unchanged | | describe the sidecar's HTTP under either hosting; `http = false` on a process-hosted service means no endpoints are served and none may be declared |

`crd.AnkkaServiceSpec`:

| field | type | default | rendered as |
|---|---|---|---|
| `hosting` | `String` | `"embedded"` | one container, or two |
| `port` | unchanged `Option[Int]` | | the `runtime` container's HTTP port, under either hosting |

`crd.AnkkaServiceStatus` gains nothing. `ServiceRow` / `services get` output gains `hosting` and
`protocol` (display only).

## The Python SDK's values

Mirrors of the Scala SDK's, named for the developer rather than the wire:

| value | shape |
|---|---|
| `EventSourcedEntity[S, E]` subclass | class attributes `component_id`, `state_codec`, `event_codec`, `snapshot_every`; methods `empty_state() -> S`, `apply_event(state: S, event: E) -> S`; handlers decorated `@command("add-item")` / `@query("get-cart")` returning `EventSourcedEffect[S, E, R]` |
| `EventSourcedEffect[S, E, R]` | frozen dataclass `{ events: tuple[E, ...], retention: Retention \| None, outcome: Reply[Callable[[S], R]] \| NoReply \| Error }`; `ReadOnlyEffect` is the subclass with `events == ()` |
| `KeyValueEffect`, `WorkflowEffect`, `StepEffect`, `ViewEffect`, `ConsumerEffect`, `TimedActionEffect`, `AgentEffect` | frozen dataclasses with the Scala values' fields |
| `Endpoint` subclass | class attribute `prefix`, `acl`; methods decorated `@get("/carts/{cart_id}")`, `@post(...)`, `@sse(...)`; a handler receives path arguments by name, an optional typed body, and `self.request` (query, headers, principal) |
| `Codec[A]` | `manifest`, `content_type`, `encode(a) -> bytes`, `decode(b) -> A`; `json_codec(cls, manifest)` is the default, built to `ENCODING.md` from a dataclass or a `Union` of dataclasses |
| `ComponentClient` | `client.for_event_sourced_entity("shopping-cart", cart_id).call("add-item").invoke(item)`, awaitable; `stream(...)` an async iterator |
| `Service` | `Ankka.service().register(...).listen(port=…)` |

State held by the SDK per loaded instance: the decoded state `S` and the sequence number, for the
life of the stream; released when the stream closes.
