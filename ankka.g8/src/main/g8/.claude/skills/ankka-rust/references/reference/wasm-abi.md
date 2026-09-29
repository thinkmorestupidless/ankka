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
runtime's clock as epoch milliseconds, and the trace entries it sets for a process. A module has no
clock of its own; `ankka.now` is the one it reads. An entity or workflow command's metadata also
carries `ankka.sequence`, the journal sequence the state it is handed reflects.

## Imports the guest may use (module `ankka1`)

| import | in | out | semantics |
|---|---|---|---|
| `invoke(ptr, len) -> i64` | `InvokeRequest` | `InvokeReply` | `Client.Invoke`; blocks the calling instance until answered |
| `invoke_stream(ptr, len) -> i64` | `InvokeRequest` | `StreamTokens` (the tokens collected; a streaming reply is delivered whole) | |
| `query(ptr, len) -> i64` | `QueryRequest` | `QueryReply` | |
| `schedule(ptr, len) -> i64` | `ScheduleRequest` | `Empty` | |
| `cancel(ptr, len) -> i64` | `CancelRequest` | `Empty` | |
| `config(ptr, len) -> i64` | `ConfigRequest` | `ConfigReply` | a descriptor variable; reserved names answer absent |
| `log(level: i32, ptr, len)` | UTF-8 text | | to the runtime's log under the logger `ankka.module`; `level` is 0 trace, 1 debug, 2 info, 3 warn, 4 error (anything else is error) |

An import runs on the thread that called the export, which in the runtime is a virtual thread; a
blocking import parks it and no other instance is affected. The guest may call an import only from
inside an export.

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
  memory, a panic under `panic = "abort"`). The host discards the instance, keeps its held state, and
  answers the caller with a fault, as a process's `Failure` does.
- The guest should send a panic's message through `log` before trapping, so the fault names itself.

## Discovery

`ankka1_discover` receives `SidecarInfo` and answers `WasmSpec`. The runtime validates
`WasmSpec.spec` with the rules a process's `Spec` is held to and additionally refuses: a handler with `streaming`, a
streaming endpoint route, a stateful id that is not a declared stateful-kind component, and an
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

![How the runtime hosts a WebAssembly module: one container runs one JVM, the runtime, with the module read once and compiled once. The entity and workflow hosts hold each loaded entity's encoded state and call ankka1_handle and ankka1_fold on a command pool of reused instances: a stateless command takes any free instance, a stateful entity is pinned to one, and a trapped instance is discarded and replaced while the held state survives for the next call. Workflow steps, views, consumers, timed actions, HTTP routes, an agent's plan, tools and guardrails, and an autonomous agent's result check each run on a fresh instance built for the call and discarded after it. From inside an export the module calls back through the ankka1 imports — invoke, send and query for the component client, invoke_stream, schedule and cancel, config with reserved names answered absent, and log — which reach the rest of the runtime while the calling virtual thread parks. Every call crosses the instance's linear memory as protobuf: the runtime writes the request through ankka1_alloc and calls the export with its pointer and length, and the guest returns the reply's pointer and length packed into one i64.](../assets/diagrams/wasm-hosting.svg)

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
