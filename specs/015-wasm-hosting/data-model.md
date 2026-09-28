# Data Model: WebAssembly Hosting

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Nothing here is stored. The journal, the snapshots, the durable state and the rows are exactly what
the runtime writes for a process-hosted service, in the same tables and the same encoding. What
follows is the in-memory model of the host and the wire model of the ABI.

## The module and its discovery

**Module** — one WebAssembly file, parsed and compiled once at start.

| field | meaning | validation |
|---|---|---|
| `path` | where `ANKKA_WASM_MODULE` points | must exist and parse; a compile failure refuses start |
| `exports` | the `ankka1_*` functions | the required set present (contract); any other `ankka<N>_` prefix refused naming N and 1 |
| `imports` | the `ankka1` module's functions, and nothing else | an import from any other module refused naming it |
| `initialize` | whether `_initialize` is exported | called once per instance after build |

**WasmSpec** — the discovery envelope (`wasm.proto`), answered by `ankka1_discover`.

| field | type | meaning |
|---|---|---|
| `spec` | `Spec` | the protocol's own discovery answer, validated by `Discovery.validate` unchanged |
| `stateful` | repeated string | component ids whose guest shape is stateful; absent means stateless |
| `abi_version` | string | `"1"`; must equal the prefix the exports carry |

Validation adds to the process's rules: a component in `stateful` must be a stateful kind (event
sourced, key value, workflow) and must be declared in `spec`; a handler with `streaming = true` and an
endpoint route declared streaming are refused, naming the handler or route.

**Shape** — per component: `Stateless` or `Stateful`. Derived from `WasmSpec.stateful` at discovery,
immutable for the runtime's life.

## Instances and pools

**GuestInstance** — one loaded copy of the module.

| field | meaning |
|---|---|
| `instance` | Chicory's, built from the compiled factory with the host imports and the memory limit |
| `pool` | `Command` or `Blocking` |
| `pinned` | for the command pool: the set of `(componentId, entityId)` stateful instances resident in this guest's memory |
| `broken` | set when a call trapped; a broken instance is discarded, never reused |

State transitions: `built → idle ⇄ busy`, `busy → broken` on a trap, `broken → discarded`. An
instance serves one call at a time; the pool enforces it.

**InstancePools**

| pool | size | assignment | after a call |
|---|---|---|---|
| command | `ANKKA_WASM_INSTANCES`, default the carrier count, minimum 1 | stateless: any idle; stateful: `hash(entityId) mod size`, waiting if busy | returned to idle |
| blocking | unbounded | a fresh instance per call | discarded |

A stateful instance whose pinned guest breaks is re-opened on the replacement guest from `HeldState`
on its next command.

## Held state

**HeldState** — what the host keeps for every loaded instance of a stateful-kind component, in both
shapes. Keyed by `(componentId, entityId)`.

| field | meaning |
|---|---|
| `shape` | the component's shape |
| `state` | the encoded state `Payload` after the last reply, or absent for fresh, deleted or expired |
| `sequence` | the journal sequence the state reflects |
| `guest` | stateful only: the pinned `GuestInstance` and whether it currently holds this entry |

Transitions, driven by `InstanceSession`:

- `open(init)` — creates the entry from `init.snapshot`; stateful: hands the snapshot to the pinned
  guest.
- `event(seq, payload)` — replay: stateless: `state ← fold(state, event)` through the command pool;
  stateful: the event is handed to the pinned guest, and `state ← the returned state` (a stateful
  guest also returns its state after a fold and a command, so the host is never behind).
- `command(cmd)` — `HandleRequest(state?, cmd)` to a guest; on `HandleReply`, `state ← reply.state`,
  `sequence` advances by the events' count once the runtime has persisted them (the host updates
  `state` only after the runtime's own fold callback confirms persistence, so a refused persist does
  not advance the held state).
- `runStep(id, step, input)` — `StepRequest(state?, step, input)` on the blocking pool; the reply's
  `new_state` updates the entry as a command's does.
- `close()` — removes the entry; stateful: tells the pinned guest to drop it.

Invariant: `HeldState.state` equals the state the journal would fold to at `sequence`. It is what
makes a trap cost nothing (the next call re-sends it) and a stateful guest replaceable.

## The ABI's envelopes (`wasm.proto`)

| message | fields | used by |
|---|---|---|
| `WasmSpec` | `spec: Spec`, `stateful: repeated string`, `abi_version: string` | `ankka1_discover` |
| `HandleRequest` | `kind: Kind`, `component_id`, `entity_id`, `state: optional Payload`, `command: oneof {EventSourcedIn.Command, KeyValueIn.Command, WorkflowIn.Command}` | `ankka1_handle` |
| `HandleReply` | `reply: oneof {EventSourcedOut.Reply, KeyValueOut.Reply, WorkflowOut.Reply}`, `state: optional Payload`, `failure: optional Failure` | `ankka1_handle` |
| `FoldRequest` | `component_id`, `entity_id`, `state: optional Payload`, `event: Payload`, `sequence: int64` | `ankka1_fold` |
| `FoldReply` | `state: Payload`, `failure: optional Failure` | `ankka1_fold` |
| `StepRequest` | `component_id`, `entity_id`, `state: optional Payload`, `run_step: WorkflowIn.RunStep` | `ankka1_run_step` |
| `StepReply` | `reply: WorkflowOut.StepReply`, `state: optional Payload`, `failure: optional Failure` | `ankka1_run_step` |
| `Passivate` | `component_id`, `entity_id` | `ankka1_close` (stateful only) |
| `ConfigRequest` / `ConfigReply` | `name: string` / `value: optional string` | the `config` import |

In the stateful shape `state` is present on `open`'s first `HandleRequest`/`FoldRequest` for an
instance and absent thereafter; the guest keeps it. In both shapes every reply carries `state`.

## The descriptor and the pod

**ServiceSpec.hosting** — `"embedded" | "process" | "wasm"`. With `wasm`: `protocol` required;
the reserved variables, the runtime's image and an HTTP port refused as with `process`.

**The wasm pod** (`contracts/descriptor-and-rendering.md`)

| part | value |
|---|---|
| init container `module` | the descriptor's image, no command override, `ankka-module` mounted at `/ankka/module` read-write |
| container (the node) | the sidecar image; every port, probe, cluster variable, credential and `preStop` a Scala service's has; `ANKKA_WASM_MODULE=/ankka/module/service.wasm`; the descriptor's variables unsplit; `ankka-module` mounted read-only |
| volume `ankka-module` | `emptyDir`, size limit 64 MiB |
| `imagePullSecrets` | the project's registry Secret, covering both images |

**The module image contract**: run with `/ankka/module` mounted, it writes exactly one file,
`service.wasm`, there and exits 0; any other exit fails the pod's init and the service reports it.

## The crate

| item | value |
|---|---|
| name, version in tree | `ankka`, `0.0.0` (rewritten by the release job) |
| features | `testkit` (adds the Docker integration kit and its dependencies) |
| targets | builds for `wasm32-unknown-unknown` (the service) and the host target (tests, the unit kit) |
| carries | its copy of `protocol/` (messages, `ENCODING.md`, `WASM-ABI.md`, fixtures), generated at build |
| reports | `SdkInfo { name: "ankka-rust", version }` and `protocol_version: "1.0"` in discovery |
