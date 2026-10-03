# The WebAssembly ABI, version 1

How a WebAssembly module and the ankka runtime talk when a service is hosted as a module rather
than as a process. A process speaks the protocol over gRPC; a module speaks the same messages across
its own linear memory, through the exports and imports below. Read it with `ENCODING.md`, which says
what the bytes inside every `Payload` mean — that part is the same in both modes.

The envelopes this mode adds are in `src/main/protobuf/ankka/protocol/v1/wasm.proto`. Every other
message named here is one a process already speaks.

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
carries `ankka.sequence`, the journal sequence the state it is handed reflects. A consumer's request
carries `ankka.sequence` for the change it is handed and `ankka.protocol`, the protocol version the
host speaks: a guest answers `produce_all` only when that entry is `1.3` or later, and fails the
call otherwise, because an earlier host reads a reply it does not know as no effect.

## Imports the guest may use (module `ankka1`)

| import | in | out | semantics |
|---|---|---|---|
| `invoke(ptr, len) -> i64` | `InvokeRequest` | `InvokeReply` | `Client.Invoke`; blocks the calling instance until answered |
| `send(ptr, len) -> i64` | `InvokeRequest` | `Empty` | the same call without waiting: dispatched, and answered at once; its reply, or a refusal, is not delivered. The way to call a handler that never replies |
| `invoke_stream(ptr, len) -> i64` | `InvokeRequest` | `StreamTokens` (the tokens collected; a streaming reply is delivered whole) | |
| `query(ptr, len) -> i64` | `QueryRequest` | `QueryReply` | |
| `schedule(ptr, len) -> i64` | `ScheduleRequest` | `Empty` | |
| `cancel(ptr, len) -> i64` | `CancelRequest` | `Empty` | |
| `config(ptr, len) -> i64` | `ConfigRequest` | `ConfigReply` | a descriptor variable; reserved names answer absent, the service's secret key (`ANKKA_SECRET_KEY`) among them |
| `get_secret(ptr, len) -> i64` | `GetSecretRequest` | `GetSecretReply` | `Client.GetSecret`: the service's secret store, since 1.4; blocks the calling instance |
| `put_secret(ptr, len) -> i64` | `PutSecretRequest` | `PutSecretReply` | `Client.PutSecret`, since 1.4 |
| `delete_secret(ptr, len) -> i64` | `DeleteSecretRequest` | `DeleteSecretReply` | `Client.DeleteSecret`, since 1.4 |
| `log(level: i32, ptr, len)` | UTF-8 text | | to the runtime's log under the logger `ankka.module`; `level` is 0 trace, 1 debug, 2 info, 3 warn, 4 error (anything else is error) |

The three secret imports answer every refusal and fault in the reply's `Error`, and answer
`Error(UNAVAILABLE)` before the service has started, as `invoke` does. A module that never calls the
secret store imports none of them, and so runs on a runtime that predates them.

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
