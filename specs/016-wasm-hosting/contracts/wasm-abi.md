# Contract: the WebAssembly ABI, version 1

The text of `protocol/WASM-ABI.md`, which every guest library copies in beside `ENCODING.md`. The
runtime implements the host side in `sidecar/.../wasm/`; the Rust crate implements the guest side;
the spike's Go guest is the second implementation and the proof of language neutrality.

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
| `ankka1_http(ptr, len) -> i64` | `HttpRequest` | `HttpReply` | non-streaming routes only |
| `_initialize()` | | | optional; called once per instance before any other export |

`ankka1_alloc`, `ankka1_free`, `ankka1_discover` and the `memory` export are required of every
module. The rest are required by what the module declares: `ankka1_handle` for any entity or
workflow, `ankka1_fold` for an event sourced entity, `ankka1_run_step` for a workflow, `ankka1_close`
for a component declared stateful, `ankka1_view`, `ankka1_consumer` and `ankka1_timed_action` for
those kinds, `ankka1_plan` for an agent (with `ankka1_invoke_tool` when it declares tools and
`ankka1_check_guardrail` when it declares guardrails), and `ankka1_http` for an endpoint. A module
missing one it needs is refused at start, naming the export and what needs it.

The host sets two kinds of metadata entry on every request that carries `Metadata`: `ankka.now`, the
runtime's clock as epoch milliseconds, and the trace entries it sets for a process. A module has no
clock of its own; `ankka.now` is the one it reads. An entity or workflow command's metadata also
carries `ankka.sequence`, the journal sequence the state it is handed reflects.

## Imports the guest may use (module `ankka1`)

| import | in | out | semantics |
|---|---|---|---|
| `invoke(ptr, len) -> i64` | `InvokeRequest` | `InvokeReply` | `Client.Invoke`; blocks the calling instance until answered |
| `send(ptr, len) -> i64` | `InvokeRequest` | `Empty` | the same call without waiting: dispatched, and answered at once; its reply, or a refusal, is not delivered. The way to call a handler that never replies |
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
`WasmSpec.spec` with the process rules and additionally refuses: a handler with `streaming`, a
streaming endpoint route, a stateful id that is not a declared stateful-kind component, and an
`abi_version` other than the exports' prefix. Every problem is reported at once in the runtime's
log; there is no `ReportError` call into a module.

## `wasm.proto`

```proto
syntax = "proto3";
package ankka.protocol.v1;
import "ankka/protocol/v1/payload.proto";
import "ankka/protocol/v1/discovery.proto";
import "ankka/protocol/v1/event_sourced.proto";
import "ankka/protocol/v1/key_value.proto";
import "ankka/protocol/v1/workflow.proto";
import "ankka/protocol/v1/client.proto";

message WasmSpec { Spec spec = 1; repeated string stateful = 2; string abi_version = 3; }

message HandleRequest {
  Kind kind = 1; string component_id = 2; string entity_id = 3;
  optional Payload state = 4;
  oneof command { EventSourcedIn.Command event_sourced = 10; KeyValueIn.Command key_value = 11; WorkflowIn.Command workflow = 12; }
}
message HandleReply {
  oneof reply { EventSourcedOut.Reply event_sourced = 10; KeyValueOut.Reply key_value = 11; WorkflowOut.Reply workflow = 12; }
  optional Payload state = 2;
  optional Failure failure = 3;
}
message FoldRequest { string component_id = 1; string entity_id = 2; optional Payload state = 3; Payload event = 4; int64 sequence = 5; }
message FoldReply  { Payload state = 1; optional Failure failure = 2; }
message StepRequest { string component_id = 1; string entity_id = 2; optional Payload state = 3; WorkflowIn.RunStep run_step = 4; }
message StepReply   { WorkflowOut.StepReply reply = 1; optional Payload state = 2; optional Failure failure = 3; }
message Passivate   { string component_id = 1; string entity_id = 2; }
message ConfigRequest { string name = 1; }
message ConfigReply   { optional string value = 1; }
message StreamTokens  { repeated StreamToken tokens = 1; }
```

Additive: no existing message changes. The spike's `spike.proto` envelopes are superseded by these
and the spike guests are updated to them in the same change.

## Versioning

`WASM-ABI.md` is versioned with the prefix. Within `ankka1`, an added export or import is a minor
change the runtime tolerates by absence (an optional export not present is not called; an import the
guest does not use is not needed). A changed signature or memory rule is `ankka2_`.
