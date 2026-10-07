# Sidecar protocol

> The gRPC protocol between the ankka sidecar and a service's process — transport, the discovery handshake, every service and RPC, the payload encoding, versioning, and the rules the messages do not state on their own.

Source: https://docs.ankka.cloud/reference/sidecar-protocol/
A service written in a language other than Scala runs as a process beside the ankka sidecar, which is the
ankka runtime started without a Scala service in it. The two speak a protobuf protocol over gRPC. The
process owns decisions: given this command and this state, what should happen. The sidecar owns everything
stateful and distributed: sharding, the journal, snapshots, projections, timers, HTTP, cluster formation,
observability and the agent loop.

This page is for people writing or debugging an SDK. A service's own code never sees the protocol; the SDK
does. The definitions are in
[`protocol/src/main/protobuf/ankka/protocol/v1`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/src/main/protobuf/ankka/protocol/v1),
and the encoding of the bytes inside a payload is in
[`protocol/ENCODING.md`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md).

## Transport

Both sides listen on loopback only, inside the same pod, and neither binds any other interface.

| Server | Default address | Variable | Serves |
|---|---|---|---|
| The process | `127.0.0.1:9010` | `ANKKA_PROCESS_PORT` (process), `ANKKA_PROCESS_ADDRESS` (sidecar) | Every service except `Client` |
| The sidecar | `127.0.0.1:9011` | `ANKKA_SIDECAR_PORT` (sidecar), `ANKKA_SIDECAR_ADDRESS` (process) | `Client` |

The sidecar dials the process; the process dials the sidecar only to call other components, query views and
schedule timers. On the platform both containers are given these variables and a descriptor may not set
them.

## Discovery

Discovery is the first conversation. The sidecar calls `Discovery.Discover` with its protocol version and
runtime version, retrying with backoff until the process answers or `ANKKA_SIDECAR_DISCOVERY_TIMEOUT`
(60 seconds by default) passes. The process answers with a `Spec`:

- its protocol version, `"1.12"`;
- its SDK's name and version;
- every component: its kind, its component id, and its handlers, each with a wire name and whether it is
  read-only or streaming, plus the kind's details — snapshot frequency for an event sourced entity; steps
  and settings for a workflow; the source (or, for a keyed view, the sources), row manifest, queries and
  declared queries for a view; the source and topic for a
  consumer; the tools, with descriptions and JSON Schemas, and guardrails for an agent; and for an
  autonomous agent its whole definition — description, instructions, tools, guardrails, model, task types
  with their result schemas and rule names, and the types it accepts with their iteration budgets;
- every HTTP endpoint: its prefix, ACL and routes, where a route may carry an ACL of its own that replaces
  the endpoint's for that route alone — a route that carries none is served under the endpoint's — and
  may be a socket route, always a `GET` with no body.

The sidecar validates the whole `Spec` and hosts exactly what it describes. If anything is wrong it calls
`Discovery.ReportError` once, with every problem, and refuses to start. A process should log what it is told;
this is where a developer sees why their service did not start.

## Services

The table is generated from the `.proto` files.

| Service | RPC | Request | Response | Defined in |
|---|---|---|---|---|
| `Agent` | `Plan` | `PlanRequest` | `PlanReply` | `agent.proto` |
| `Agent` | `InvokeTool` | `ToolRequest` | `ToolResult` | `agent.proto` |
| `Agent` | `CheckGuardrail` | `GuardrailRequest` | `GuardrailResult` | `agent.proto` |
| `Agent` | `CheckTaskResult` | `TaskResultRequest` | `TaskResultVerdict` | `agent.proto` |
| `Client` | `Invoke` | `InvokeRequest` | `InvokeReply` | `client.proto` |
| `Client` | `InvokeStream` | `InvokeRequest` | `stream StreamToken` | `client.proto` |
| `Client` | `Query` | `QueryRequest` | `QueryReply` | `client.proto` |
| `Client` | `Schedule` | `ScheduleRequest` | `Empty` | `client.proto` |
| `Client` | `Cancel` | `CancelRequest` | `Empty` | `client.proto` |
| `Client` | `GetSecret` | `GetSecretRequest` | `GetSecretReply` | `client.proto` |
| `Client` | `PutSecret` | `PutSecretRequest` | `PutSecretReply` | `client.proto` |
| `Client` | `DeleteSecret` | `DeleteSecretRequest` | `DeleteSecretReply` | `client.proto` |
| `Client` | `Request` | `ServiceRequest` | `ServiceReply` | `client.proto` |
| `Client` | `Decide` | `DecideRequest` | `InvokeReply` | `client.proto` |
| `Client` | `ScheduleRecurring` | `ScheduleRecurringRequest` | `ScheduleRecurringReply` | `client.proto` |
| `Consumer` | `Handle` | `ConsumerRequest` | `ConsumerEffect` | `consumer.proto` |
| `Discovery` | `Discover` | `SidecarInfo` | `Spec` | `discovery.proto` |
| `Discovery` | `ReportError` | `Problem` | `Empty` | `discovery.proto` |
| `Http` | `Handle` | `HttpRequest` | `HttpReply` | `endpoint.proto` |
| `Http` | `HandleStream` | `HttpRequest` | `stream StreamFrame` | `endpoint.proto` |
| `Http` | `HandleSocket` | `stream SocketIn` | `stream SocketOut` | `endpoint.proto` |
| `EventSourced` | `Handle` | `stream EventSourcedIn` | `stream EventSourcedOut` | `event_sourced.proto` |
| `KeyValue` | `Handle` | `stream KeyValueIn` | `stream KeyValueOut` | `key_value.proto` |
| `TimedAction` | `Invoke` | `TimedActionRequest` | `TimedActionEffect` | `timed_action.proto` |
| `View` | `Handle` | `ViewRequest` | `ViewEffect` | `view.proto` |
| `Workflow` | `Handle` | `stream WorkflowIn` | `stream WorkflowOut` | `workflow.proto` |
| File | Conversation |
|---|---|
| `payload.proto` | Shared messages: `Payload`, `Metadata`, `Outcome`, `Retention`, `Error`, `Failure`. |
| `discovery.proto` | The process describes its components and endpoints. |
| `event_sourced.proto`, `key_value.proto`, `workflow.proto` | One bidirectional stream per loaded instance. |
| `view.proto`, `consumer.proto`, `timed_action.proto` | Stateless: one request, one effect. |
| `endpoint.proto` | HTTP requests the sidecar forwards for declared routes, with streaming for server-sent events. |
| `agent.proto` | The process plans, runs tools and guardrails, and checks autonomous agents' results; the sidecar runs the loop. |
| `client.proto` | The sidecar's service for the process: component calls, streaming calls, view queries, timers. |

### Stateful conversations

An event sourced entity, key value entity or workflow instance is a bidirectional stream, opened when the
sidecar loads the instance. For an event sourced entity the sidecar sends `Init` with the latest snapshot, if
any, then the events after it, then commands one at a time. The process folds the events into state and
answers each command with a `Reply` — the events to persist, any retention, and an `Outcome` that is a reply
or a refusal — or with a `Failure` when the handler faulted. An `Init` with no snapshot means a fresh
instance, or one that was deleted or expired; start from the empty state.

When a command asks for a snapshot, the reply carries the state after its events. The sidecar stores it
beside the journal; the stored snapshot may sit at the sequence of the last event the reply persisted or at
the next one, and both are correct.

### Autonomous agents

An autonomous agent has no plan to ask for: its definition arrived in discovery, and the sidecar runs its
loop, its task records and its instance records. It asks the process for three things, each with the session
id `task:<task id>`: `Agent.InvokeTool` to run one of its tools, `Agent.CheckGuardrail` to check its
instructions or a result, and `Agent.CheckTaskResult` when the model completes a task. For the last, the
process decodes the result as the task type's and runs the type's rules in order, answering `accept`,
`malformed` with why it did not decode, or `reject` naming the first rule that refused it. A rule that throws
is answered as an error, which the sidecar treats as a failed iteration and tries again. The sidecar never
sends `complete_task` or `fail_task` to the process; they are its own.

### Stateless conversations

A view, consumer or timed action is called once per change or timer, with a payload and metadata, and
answers with one effect. An HTTP route is called once per request, or once per stream for a server-sent
events route.

A socket route is one `Http.HandleSocket` call for as long as its socket is open. The sidecar holds the
socket: it decides the ACL when the socket is opened, enforces the frame limits, pings a quiet socket and
sends the close code. It sends `open` first — the opening request, with its path arguments, query,
headers, principal, caller and metadata, whose `ankka.protocol` entry states the runtime's version — then
a `frame` for each frame the client sent, in order, and `closed` with the reason once the socket is
closed, after which it ends its side. It reads the process's side only as the client can take it, and
sends a client's frame only when the process can take one, so a process that does not read leaves the
frames to the socket's own unread bound. The process sends a `frame` for each frame for the client and
ends with `completed`, which closes the socket `1000`, or `failed`, which closes it `1011`; a process that
stops or goes away is `failed`. A message with no case set, in either direction, is a protocol violation
that closes the socket as failed, never one that is skipped.

A consumer's effect is one of four: `produce`, one message for the consumer's topic; `produce_all`,
several; `done`; or `ignore`.

```protobuf
message ConsumerEffect {
  oneof effect { Produce produce = 1; Empty done = 2; Empty ignore = 3; ProduceAll produce_all = 4; }
  message Produce { Payload payload = 1; Metadata metadata = 2; }
  message ProduceAll { repeated Message messages = 1; }
  message Message { Payload payload = 1; Metadata metadata = 2; optional string key = 3; }
}
```

| Reply | Published | The change is handled when |
|---|---|---|
| `produce` | one record, keyed by the message's subject | the broker accepts it |
| `produce_all` with messages | one record each, in the order given; a message's record key is its `key`, and without one its subject | the broker has accepted all of them |
| `produce_all` with none | nothing | at once, as `done` |
| `done`, `ignore` | nothing | at once |

Each message's `ce-subject` defaults to the source's id, whatever its key. If the broker refuses any
message of a `produce_all`, the change is delivered again and every message is published again; those
already accepted are not withdrawn. An empty `key` fails the change. A reply may be at most 4 MiB: a larger
one fails the change, naming the consumer, and none of it is published.

## Payloads

Every value crosses the protocol as a `Payload`: `content_type`, `manifest` and `data`. The sidecar never
reads `data`. It stores it under its manifest exactly as the Scala runtime stores a Scala value, which is
what lets a service in one language read a journal written by the same service in another — as long as both
languages' codecs agree. Every SDK's default codec produces and accepts exactly this:

| Content type | Used for | Encoding |
|---|---|---|
| `application/json` | Records and sum types | A JSON object with every field written, including empty collections and absent options as `null`. A sum type's case adds `"type": "<CaseName>"`. Instants are ISO-8601 in UTC with `Z`. |
| `text/plain` | Top-level primitives | The text itself, with no quotes: manifests `string`, `int`, `long`, `short`, `byte`, `double`, `float`, `boolean`, and `duration-millis` for a duration in milliseconds. |
| `application/octet-stream` | `Done`, unit, bytes, top-level options | Zero bytes for `done` and `unit`; the bytes for `bytes`; for `option[<manifest>]`, zero bytes for none, or `0x01` followed by the value's bytes. |

Readers are lenient where writers are strict: an absent optional field reads as none, field order does not
matter, and unknown fields are ignored. A missing required field or an unknown `type` is refused. Field names
are the contract, so a Python field matching a Scala one is `productId`, not `product_id`.

The fixtures in
[`protocol/fixtures`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/fixtures) are encoded
examples of every shape. An SDK decodes each fixture's bytes and compares the result with its `value`, then
re-encodes the value and compares the bytes. A fixture with no matching codec is a failure, not a skip.

## Errors

A refusal is an `Error` with a message and an `ErrorCode`, carried in an `Outcome`. A fault is a `Failure`,
which names the command it answers. The two are never interchangeable: a refusal is a decision the handler
made, and a failure is a handler that could not decide. See [Error codes](error-codes.md).

## Versioning

The protocol version is `MAJOR.MINOR`, currently `1.12`, and both sides state it in discovery. `1.1` added
the caller to forwarded requests and caller-naming ACLs; `1.2` added the autonomous agent; `1.3` added a
consumer's reply of several messages, each with an optional record key, and the `ankka.protocol` entry
on a consumer's request; `1.4` added metadata to a workflow step, a tool call, a guardrail check, a result
check and a view query, so a call or a query made from any of them carries its trace and says which handler
made it; `1.5` added every other claim of a verified token, and the name of the issuer
that verified it, to a route's principal; `1.6` added the service's secret store, `GetSecret`, `PutSecret` and
`DeleteSecret` on `Client`, each answering a refusal in its reply. A process built for `1.6` that calls the
store on an earlier runtime is answered `UNIMPLEMENTED`, which each SDK reports as the runtime being too
old for the store. `1.7` added where a view's or a consumer's topic source starts, and the version it
reads at. `1.8` added `Request` on `Client`: a call to another service, which the runtime makes
as the service, answering the service's answer, a failure naming why no answer came, or a refusal. A
process built for `1.8` that calls it on an earlier runtime is answered `UNIMPLEMENTED`, which each SDK
reports as the runtime being too old to call another service; a process that declared an earlier minor
and sends one all the same is refused, naming both versions. `1.9` added socket routes: `Route.socket`
in discovery and `Http.HandleSocket`. A socket route is refused from both ends across that line: the
sidecar refuses a `Spec` declaring one under an earlier minor, naming the route and both versions, and an
SDK refuses to answer discovery with one to a runtime that states an earlier version. `1.10` changed no message: it added three imports for a WebAssembly module, `request`, `now` and `random`, which the [WebAssembly ABI](wasm-abi.md) describes. A process is the same at `1.9` and `1.10`. `1.11` added approvals, MCP servers and result
guardrails to agents: a tool's `approval` and an agent's `mcp_servers` and `result_guardrails` in discovery,
the `RESULT` stage of `CheckGuardrail` with the MCP tool it checks, the `approval` case of `InvokeReply` and
`StreamToken` that answers a turn waiting for a person, and `Decide` on `Client`, which sends a person's
decision, and the `event` case of `StreamFrame`, which sends a server-sent event under a name of its own — an
approval request, say — from a stream route. The sidecar connects to the MCP servers and enforces approval itself: a process is never asked to
run an MCP server's tool, nor a tool that awaits a decision. A process built for `1.11` that decides on an
earlier runtime is answered `UNIMPLEMENTED`, which each SDK reports as the runtime being too old. `1.12` added recurring timers:
`ScheduleRecurring` on `Client`, with a delay and a period and a refusal in its reply, and `ankka.due` on every
timed action's request. A recurring timer is a call of its own rather than a field on `ScheduleRequest`
because an earlier runtime reads a field it does not know as absent, and would schedule a timer meant to
recur to fire once; it answers the call `UNIMPLEMENTED` instead, which each SDK reports as the runtime being
too old for recurring timers.

- Adding an optional field, a message, an RPC or a fixture is a minor change. A sidecar speaking a later minor
  accepts an SDK that declares an earlier one.
- Renaming, removing or changing the meaning of anything, or changing the encoding of a shape the fixtures
  cover, is a major change. The sidecar refuses a `Spec` with another major, naming both versions.

A process-hosted service declares its protocol in its descriptor's `protocol` field, and the platform checks
it before starting anything: the same major as the platform's, and a minor no later than its own.

## Without a process

A service built to a WebAssembly module speaks these same messages without gRPC: the runtime loads the
module into its own process and calls the functions it exports, with a few envelopes of the module mode's
own in `wasm.proto` carrying the state the runtime holds for it. Discovery, the payload encoding, the
fixtures and the conformance suite are the same. See [WebAssembly ABI](wasm-abi.md).

## Rules the messages do not state

These are part of the protocol, and an SDK that ignores one misbehaves in ways the types cannot show.

- **One command in flight per stateful stream.** The sidecar sends the next command only after the process
  has answered the last. A workflow's stream is the exception: the engine keeps answering commands while a
  step runs, so one command and one step may be in flight at once. A command arriving during a step is
  answered from the state before the step, and the step's new state applies when the step replies. An SDK
  must track the pending command and the pending step separately, and must not share per-request context
  between them.
- **A workflow declares what the sidecar enforces.** Timeouts and recovery live in `WorkflowDetail.settings`,
  because the process cannot enforce them. Absent, there is no overall limit, each step has 30 seconds, and a
  failed step fails the workflow. A `failover_to` step must be declared, and runs with no input.
- **A step's fault is a `Failure`; a step's `fail` is a decision.** A step that answers `Failure`, or times
  out, is retried and failed over as its recovery declares. A step that answers `StepOutcome` `fail` ends the
  workflow, because that is what it asked for. An SDK turns an exception in a step into a `Failure`, never
  into `fail`.
- **A view or consumer learns of its source's deletion** by a request with `deleted = true` and no event. The
  default answer is to delete the row, for a view, or ignore it, for a consumer. The source's id travels as
  the metadata entry `ce-subject`, and the change's sequence number as `ankka.sequence`: an event's sequence
  number, a key value entity's revision, and `0` for a topic's message. A deletion has a sequence number
  of its own, above every earlier change to the entity, for both kinds of entity.
- **A keyed view's change names its source and carries no row.** A view declared with `sources` rather than
  `source` is sent each change with `source_id`, the component it came from, and answers `rows`: the rows to
  write and to delete, by key, in order, a later change to a key winning; an empty list is nothing. It reads
  its own rows through `Client.Query` — `get` by key, or one of its declared queries by name with `values`.
  The runtime handles one of its changes at a time and writes all of a change's rows together or none.
- **A declared query is asked by name with its values.** `QueryRequest.name` names one of the view's
  declared queries and `values` gives each `:name` the statement holds, as text; `limit` bounds the rows,
  1000 when absent. The statement is the runtime's to check when the process is discovered: a process sends
  it as the developer wrote it and parses nothing.
- **An SDK refuses a runtime older than what it declares.** One that declares a keyed view, a declared query
  or a version on a view that reads entities refuses discovery from a runtime below 1.13, as one that
  declares a start position refuses a runtime below 1.7: an older runtime would ignore the declaration.
- **An SDK answers `produce_all` only to a runtime that says it accepts it.** A consumer's request carries
  the metadata entry `ankka.protocol`, the protocol version the runtime speaks. A runtime that does not know
  a reply reads it as no effect and records the change as handled, so an SDK about to answer `produce_all`
  to a request with no `ankka.protocol`, or one below `1.3`, fails the request instead, saying which
  version it was given and which it needs. A handler that returns one message with no key is answered as
  `produce`, and one that returns none as `done`, on a runtime of any version.
- **A timed action's payload is what the process scheduled**, carried through the timer table unread. The
  timer's name, the attempt count and the due time the run is for arrive as metadata `ankka.timer`,
  `ankka.attempts` and `ankka.due`, the last in milliseconds since the epoch. A `fail`, an exception or an
  unreachable process is retried on the sweeper's schedule with the count incremented and the same
  `ankka.due`.
- **An agent plan names; it does not carry.** `AgentPlan.model`, `tools` and `guardrails` are names the
  sidecar resolves against its configuration and against discovery. The process is called back for a tool
  with the model's arguments as JSON text, and for a guardrail with the stage and the text. The loop, the
  session memory and the model key stay in the sidecar. A tool or guardrail call arrives after the handler
  that planned it has returned, so anything it needs from the session must be captured when the plan is
  made.
- **`ankka-caller` is the runtime's, carried and never written.** Metadata the runtime sends with a command,
  a step, a tool call or a check carries `ankka-trace-id`, `ankka-span-id` and `ankka-caller`, the handler
  whose work this is. An SDK forwards a handler's metadata unchanged on the calls it makes through the
  client, which is how those calls are attributed; user code never sets `ankka-caller`, and the runtime
  ignores one that does not name a component and handler the service declared.
- **`Unavailable` means try again.** When an instance stops while callers are waiting on it, for example
  during a rolling replacement, the sidecar answers each waiting caller with `UNAVAILABLE` rather than letting
  it time out, and the sidecar's `Client` retries `UNAVAILABLE` briefly before giving up. An SDK should treat
  `UNAVAILABLE` and `TIMEOUT` as retryable.
