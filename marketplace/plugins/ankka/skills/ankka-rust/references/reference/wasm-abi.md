# WebAssembly ABI

> How the ankka runtime hosts a service built to a WebAssembly module — the exports a module provides, the imports it may call, the memory convention, the two guest shapes, faults, discovery, the runtime's settings, and versioning.

Source: https://docs.ankka.cloud/reference/wasm-abi/
A service can be built to a WebAssembly module that the ankka runtime loads into its own process. The
module owns decisions — given this command and this state, what should happen — and the runtime owns
everything stateful and distributed: sharding, the journal, snapshots, projections, timers, HTTP, cluster
formation, observability and the agent loop. It is the same division as a service in another language
running as a process beside the runtime, and the runtime hosts both with the same code; what differs is
how the two sides reach each other. A process speaks the protocol over gRPC; a module speaks the same
messages across its own linear memory, through the exports and imports this page lists.

This page is for people writing or debugging a guest library. A service's own code never sees the ABI;
the library does. The Rust crate is one. The message definitions are in
[`protocol/src/main/protobuf/ankka/protocol/v1`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/src/main/protobuf/ankka/protocol/v1),
with this mode's envelopes in `wasm.proto`; the encoding of the bytes inside a payload is in
[`protocol/ENCODING.md`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md), and
is the same in both modes.

## Shape

A module is a WebAssembly **core module** (not a component). It imports functions only from the
module named `ankka1`, exports the functions below under the prefix `ankka1_`, and exports its
linear memory as `memory`. The `1` is the ABI's major version: the runtime refuses a module whose
`ankka` exports carry another number, naming both; a new major is a new prefix, and a runtime may
speak several.

Every value crossing the boundary is a protobuf message from `protocol/src/main/protobuf`, encoded
with the standard binary encoding. The `Payload`s inside them follow `ENCODING.md` unchanged.

## Memory convention

Bytes cross as a pointer and a length into the guest's linear memory.

- **Requests** (host → guest): the host calls `ankka1_alloc(len) -> ptr`, writes the request there,
  and calls the export with `(ptr, len)`. The guest **owns** that buffer from then on and frees it.
- **Replies** (guest → host): the export returns a `u64` packing `ptr << 32 | len`. The host reads
  `len` bytes at `ptr` and then calls `ankka1_free(ptr, len)`. A zero `u64` is an empty reply.
- **Host imports that return bytes** allocate through the guest's `ankka1_alloc` from inside the
  call, write, and return the same packed `u64`; the guest owns and frees the buffer.
- The guest must not keep a pointer into a buffer it has freed, and the host never reads a buffer
  after freeing it. Alignment is not required.

## Exports the guest provides

| export | in | out | notes |
|---|---|---|---|
| `ankka1_alloc(len: i32) -> i32` | | | see memory |
| `ankka1_free(ptr: i32, len: i32)` | | | |
| `ankka1_discover(ptr, len) -> i64` | `SidecarInfo` | `WasmSpec` | once, at start |
| `ankka1_handle(ptr, len) -> i64` | `HandleRequest` | `HandleReply` | entity and workflow commands |
| `ankka1_fold(ptr, len) -> i64` | `FoldRequest` | `FoldReply` | replay: one journaled event applied to the state |
| `ankka1_run_step(ptr, len) -> i64` | `StepRequest` | `StepReply` | workflow steps |
| `ankka1_close(ptr, len)` | `Passivate` | | stateful only; the guest drops the instance's state |
| `ankka1_view(ptr, len) -> i64` | `ViewRequest` | `ViewEffect` | |
| `ankka1_consumer(ptr, len) -> i64` | `ConsumerRequest` | `ConsumerEffect` | |
| `ankka1_timed_action(ptr, len) -> i64` | `TimedActionRequest` | `TimedActionEffect` | |
| `ankka1_plan(ptr, len) -> i64` | `PlanRequest` | `PlanReply` | |
| `ankka1_invoke_tool(ptr, len) -> i64` | `ToolRequest` | `ToolResult` | |
| `ankka1_check_guardrail(ptr, len) -> i64` | `GuardrailRequest` | `GuardrailResult` | |
| `ankka1_check_task_result(ptr, len) -> i64` | `TaskResultRequest` | `TaskResultVerdict` | an autonomous agent's result: decoded as its task type's, then held to the type's rules |
| `ankka1_http(ptr, len) -> i64` | `HttpRequest` | `HttpReply` | non-streaming routes only |
| `_initialize()` | | | optional; called once per instance before any other export |

`ankka1_alloc`, `ankka1_free`, `ankka1_discover` and the `memory` export are required of every
module. The rest are required by what the module declares: `ankka1_handle` for any entity or
workflow, `ankka1_fold` for an event sourced entity, `ankka1_run_step` for a workflow, `ankka1_close`
for a component declared stateful, `ankka1_view`, `ankka1_consumer` and `ankka1_timed_action` for
those kinds, `ankka1_plan` for an agent (with `ankka1_invoke_tool` when it declares tools and
`ankka1_check_guardrail` when it declares guardrails), `ankka1_check_task_result` for an autonomous
agent (with the same two when it declares tools or guardrails), and `ankka1_http` for an endpoint. A module
missing one it needs is refused at start, naming the export and what needs it.

The host sets two kinds of metadata entry on every request that carries `Metadata`: `ankka.now`, the
runtime's clock as epoch milliseconds when it made the call, and the trace entries it sets for a
process. A module has no clock of its own: it asks for the time through the `now` import. `ankka.now`
is what a guest library from before protocol 1.10 reads, and the host goes on setting it on every such
request, so a module built with one still reads the time. A timed action's request also carries
`ankka.timer`, `ankka.attempts` and `ankka.due`, the due time the run is for, in epoch milliseconds as
`ankka.now` is. An entity or workflow command's metadata also
carries `ankka.sequence`, the journal sequence the state it is handed reflects. A consumer's request
carries `ankka.sequence` for the change it is handed and `ankka.protocol`, the protocol version the host
speaks. A guest answers `produce_all`, several messages for one change, only when that entry is `1.3` or
later, and fails the call otherwise, because an earlier host reads a reply it does not know as no effect;
see [the sidecar protocol](sidecar-protocol.md#stateless-conversations) for the reply.

## Imports the guest may use (module `ankka1`)

| import | in | out | semantics |
|---|---|---|---|
| `invoke(ptr, len) -> i64` | `InvokeRequest` | `InvokeReply` | `Client.Invoke`; blocks the calling instance until answered |
| `invoke_stream(ptr, len) -> i64` | `InvokeRequest` | `StreamTokens` (the tokens collected; a streaming reply is delivered whole) | |
| `query(ptr, len) -> i64` | `QueryRequest` | `QueryReply` | |
| `schedule(ptr, len) -> i64` | `ScheduleRequest` | `Empty` | |
| `cancel(ptr, len) -> i64` | `CancelRequest` | `Empty` | cancels a timer of either kind |
| `schedule_recurring(ptr, len) -> i64` | `ScheduleRecurringRequest` | `ScheduleRecurringReply` | a recurring timer, since protocol 1.12; a refusal is the reply's `Error`, not a trap |
| `config(ptr, len) -> i64` | `ConfigRequest` | `ConfigReply` | a descriptor variable; reserved names answer absent, the service's secret key (`ANKKA_SECRET_KEY`) among them |
| `get_secret(ptr, len) -> i64` | `GetSecretRequest` | `GetSecretReply` | the service's secret store, since protocol 1.6; blocks the calling instance |
| `put_secret(ptr, len) -> i64` | `PutSecretRequest` | `PutSecretReply` | since 1.6 |
| `delete_secret(ptr, len) -> i64` | `DeleteSecretRequest` | `DeleteSecretReply` | since 1.6 |
| `request(ptr, len) -> i64` | `ServiceRequest` | `ServiceReply` | a call to another service, made by the runtime as this service, since protocol 1.10. Served only to the exports listed under [Where `request` may be called](#where-request-may-be-called); from any other, the call into the module ends there. Blocks the calling instance until the service answers or the runtime's wait for it ends |
| `now() -> i64` | | | the runtime's clock as milliseconds since the Unix epoch, when it is asked; from any export, since 1.10 |
| `random(ptr, len)` | | | fills the `len` bytes at `ptr`, which the guest owns, from the runtime's secure source; `len` is at most 65,536; from any export, since 1.10 |
| `log(level: i32, ptr, len)` | UTF-8 text | | to the runtime's log under the logger `ankka.module`; `level` is 0 trace, 1 debug, 2 info, 3 warn, 4 error (anything else is error) |

The secret imports and `schedule_recurring` answer every refusal in the reply's `Error`. A module that
never calls the secret store imports none of them, and one that never sets a recurring timer does not
import `schedule_recurring`, so it runs on a runtime that predates them; the Rust crate calls them through
a function of their own for exactly that reason.

An import runs on the thread that called the export, which in the runtime is a virtual thread; a
blocking import parks it and no other instance is affected. The guest may call an import only from
inside an export.

## Where `request` may be called

A call to another service waits for as long as that service takes, and a call into a module cannot be
interrupted. So the runtime serves `request` only to an export it runs on an instance of its own, where
the wait holds nothing another call needs. It decides from the export it called: it does not read the
request to decide, and it does not depend on what the guest library checks.

| Export | A `request` made while the runtime is running it |
|---|---|
| `ankka1_run_step` | proceeds |
| `ankka1_consumer` | proceeds |
| `ankka1_timed_action` | proceeds |
| `ankka1_plan` | proceeds |
| `ankka1_invoke_tool` | proceeds |
| `ankka1_check_guardrail` | proceeds |
| `ankka1_check_task_result` | proceeds |
| `ankka1_http` | proceeds |
| `ankka1_handle` | refused: a command of an entity or of a workflow, which every other command to the same instance would wait behind |
| `ankka1_fold` | refused: an event applied to an entity's state, which must give the same state every time |
| `ankka1_view` | refused: a view's handler, for the same reason |
| `ankka1_close`, `ankka1_discover` | refused |

A refused `request` does not return. The call into the module ends there, as it does for a trap: nothing
was sent, the instance is discarded and replaced, the state the runtime holds is untouched, and the
caller is answered with a fault that names the import and what was running, such as `request may not be
called from the command cart/add-item`. An export the ABI gains is refused until it is listed here.

A `request` is refused in the same way when the call it is made from has been abandoned. The module ran
past the runtime's deadline for the export, its caller has already been answered with a fault, and what
the module does after that makes no further call to another service.

`request` answers a `ServiceReply` with exactly one case set: `response` whenever the service answered,
whatever its status, a refusal included; `failure` when no answer came, with the reason `UNRESOLVABLE`,
`IDENTITY_MISMATCH` or `UNANSWERED`; and `error` when the runtime refused the request itself, such as a
name that is not one or a body over 4,000,000 bytes, or `error` with `UNAVAILABLE` before the service
has started. The runtime sends the metadata it gave the export being run, whatever
`ServiceRequest.metadata` holds, so the call is counted from that handler and traced under it. The request
carries no timeout: the runtime waits as long as the service's `ankka.service-client.timeout` says and
answers `UNANSWERED` when that passes. A redirect is the reply; it is not followed.

`now` is the time when it is asked. Two reads in one call may differ, and when an event is applied again
it is still the present, so an event's time is read from the event. `random` is never seeded and has no
setting; bytes used to make an id in a command belong in the event the command records, since applying
the event again does not run the command again. Neither waits, and both are served to every export.

A module that calls none of the three imports none of them, and runs on a runtime that predates them. A
module that imports one is refused at start by an earlier runtime, naming the import.

## Guest shapes

Declared per component in `WasmSpec.stateful`.

- **Stateless** (default): every `HandleRequest`, `FoldRequest` and `StepRequest` carries `state`
  (absent for a fresh, deleted or expired instance). The guest decodes it, acts, and returns the new
  state in the reply. It keeps nothing between calls.
- **Stateful**: the host sends `state` on the first request after `open` and omits it thereafter;
  the guest keeps the decoded state for `(component_id, entity_id)` until `ankka1_close`. Every
  reply still carries the encoded state, so the host is never behind. A stateful component's
  instance is pinned to one guest instance for its loaded life.

## Faults and refusals

- A **refusal** is a value: `Outcome.error` in a reply, `ToolResult.error`, `GuardrailResult.block`,
  `HttpResponse` with an error status. Nothing is persisted; the caller sees the code.
- A **fault** is a `failure` field set in a reply, or a trap (unreachable, out of bounds, out of
  memory, a panic under `panic = "abort"`), or an import the host refused to serve: a `request` from
  an export that may not make one, a `random` for more than it fills or into memory that is not the
  module's. The host discards the instance, keeps its held state, and answers the caller with a
  fault, as a process's `Failure` does.
- The guest should send a panic's message through `log` before trapping, so the fault names itself.

## Discovery

`ankka1_discover` receives `SidecarInfo` and answers `WasmSpec`. The runtime validates
`WasmSpec.spec` with the rules a process's `Spec` is held to and additionally refuses: a handler with `streaming`, a
streaming endpoint route, a socket route, a stateful id that is not a declared stateful-kind component, and an
`abi_version` other than the exports' prefix. Every problem is reported at once in the runtime's
log; there is no `ReportError` call into a module.

## The envelopes (`wasm.proto`)

| message | carries |
|---|---|
| `WasmSpec` | the process's `Spec`, the ids of the components declared stateful, and `abi_version` (`"1"`) |
| `HandleRequest` / `HandleReply` | an entity or workflow command with the state the runtime holds, one `oneof` case per kind; the reply's `state` is the state after the command, and `failure` a fault |
| `FoldRequest` / `FoldReply` | one event applied to an event sourced entity's state, on replay and after a command's events persist |
| `StepRequest` / `StepReply` | a workflow step, with the state, answered with the step's effect and the state after it |
| `Passivate` | `ankka1_close`: the instance a stateful guest may drop |
| `ConfigRequest` / `ConfigReply` | the `config` import |
| `StreamTokens` | the `invoke_stream` import's reply, the tokens collected |

## Versioning

`WASM-ABI.md` is versioned with the prefix. Within `ankka1`, an added export or import is a minor
change the runtime tolerates by absence (an optional export not present is not called; an import the
guest does not use is not needed). A changed signature or memory rule is `ankka2_`.

## How the runtime hosts a module

The runtime reads the module once at start, compiles it once, and builds instances of it as it needs
them.

![How the runtime hosts a WebAssembly module: one container runs one JVM, the runtime, with the module read once and compiled once. The entity and workflow hosts hold each loaded entity's encoded state and call ankka1_handle and ankka1_fold on a command pool of reused instances: a stateless command takes any free instance, a stateful entity is pinned to one, and a trapped instance is discarded and replaced while the held state survives for the next call. Workflow steps, views, consumers, timed actions, HTTP routes, an agent's plan, tools and guardrails, and an autonomous agent's result check each run on a fresh instance built for the call and discarded after it. From inside an export the module calls back through the ankka1 imports — invoke, send and query for the component client, invoke_stream, schedule, schedule_recurring and cancel, config with reserved names answered absent, and log — which reach the rest of the runtime while the calling virtual thread parks. Every call crosses the instance's linear memory as protobuf: the runtime writes the request through ankka1_alloc and calls the export with its pointer and length, and the guest returns the reply's pointer and length packed into one i64.](../assets/diagrams/wasm-hosting.svg)

Two pools serve calls:

- **Commands** — entity and workflow commands, and replay — run on a fixed number of reused instances.
  A stateless component's command takes any free one; a stateful component's instance is pinned to one by
  its entity id, since that guest instance holds its state.
- **Everything that may wait on the runtime** — a workflow step, a view, a consumer, a timed action, an
  agent's plan, tool or guardrail, an autonomous agent's result check, an HTTP route — runs on a fresh instance built for the call and
  discarded after it, so a call blocked inside an import never holds an instance a command needs.

Every call runs on a virtual thread, so a guest blocked in an import parks that thread and nothing else.
A call that does not return within the runtime's command timeout is abandoned: its instance is discarded
and replaced, and the caller is answered with a fault.

The runtime holds each loaded instance's encoded state in both shapes, updated from every reply. That is
what makes a fault cost nothing but the call that faulted: the instance is replaced, and the next call
hands the state to whichever instance serves it.

## Settings

The runtime image runs in this mode when `ANKKA_WASM_MODULE` names a file; on the platform the operator
sets it, and a descriptor may not set any of these.

| Variable | Default | Meaning |
|---|---|---|
| `ANKKA_WASM_MODULE` | none | The module to load. Absent, the image runs as a sidecar beside a process instead. |
| `ANKKA_WASM_INSTANCES` | the number of processors | How many reused instances serve commands; at least one. |
| `ANKKA_WASM_MAX_MEMORY_PAGES` | `4096` | The most linear memory an instance may grow to, in 64 KiB pages (256 MiB). |

## Environment

A module has no environment of its own. The `config` import answers the variables the service's
descriptor set, read from the runtime's environment, and answers absent for every name the platform
reserves: model keys and settings (`ANTHROPIC_*`, `ANKKA_MODEL_*`), the database's (`ANKKA_DB_*`), the
cluster's and the runtime's own (`ANKKA_CLUSTER_*`, `ANKKA_WASM_*`, `ANKKA_SIDECAR_*`, `ANKKA_PROCESS_*`,
`ANKKA_AUTH_*`), `POD_IP`, `ANKKA_HTTP_PORT`, `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`.
