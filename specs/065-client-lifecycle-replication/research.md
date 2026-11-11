# Research: Every client reads a workflow's standing and how a copy stands

## R1: Two typed callback rpcs, not the reserved names over `Invoke`

**Decision**: `Client` gains `rpc Lifecycle (LifecycleRequest) returns (LifecycleReply)` and
`rpc Replication (ReplicationRequest) returns (ReplicationReply)` in protocol 1.24. Each reply is a
`oneof` of the answer as a protocol message and an `Error`. The sidecar answers each with the
runtime's own reserved query (`WorkflowLifecycle.Method`, `ReplicationStatus.Method`) through
`transport.askQuery`, and converts the Scala value to the message.

**Rationale**: `Invoke` takes any method name `MethodName.parse` accepts, so a process could already
send `ankka:lifecycle`. But the reply would be the runtime's internal jsoniter encoding of a Scala
case class (manifest `workflow-lifecycle`), which is not part of the protocol and changes when the
case class does. A 1.23 runtime would also answer it, so there would be nothing for FR-007 to
refuse. Typed messages give a stable wire, and a runtime older than 1.24 answers the new rpcs
`UNIMPLEMENTED`.

**Alternatives considered**: the reserved names over `Invoke` (rejected for the reasons above); one
`Standing` rpc carrying both reads as a `oneof` (rejected because the two have different targets and
answers, and every other read is its own rpc).

## R2: The standing as an enum; absent fields as proto3 `optional`

**Decision**: the answer's status is `enum WorkflowStatus { WORKFLOW_STATUS_UNSPECIFIED = 0;
NOT_STARTED = 1; RUNNING = 2; PAUSED = 3; COMPLETED = 4; FAILED = 5; }`. `pending_step` and `failure`
are `optional string`, and `retries` is `map<string, int32>`. For a copy, `home`, `pending_handover`
and `conflict` are `optional string`, `replication_set` is `repeated string` sorted, and `position`
is `int64`.

**Rationale**: an enum makes "no new status" (FR-004) checkable, where a string would let a typo
through. `UNSPECIFIED` is never sent; an SDK reads it as an error naming the value. `Unknown` is a
standing a change carries to a view (protocol 1.16), not one the read answers (`WorkflowLifecycle.isUnknown`'s
comment), so it has no value here. Sorting `replication_set` makes the answer comparable across
languages.

**Alternatives considered**: the Scala strings (`"Running"`) as a `string` field (rejected because
it is unchecked).

## R3: Refusals are the runtime's, carried in the reply

**Decision**: the sidecar runs the reserved query and maps a `CommandError` to the reply's `Error`
with the same `ErrorCode`, exactly as `ClientLogic.invoke` does. It adds no refusal of its own beyond
parsing the ids (`BAD_REQUEST`, as `invoke` does). A standing of an entity, or a copy read of a
workflow or an entity that is not replicated, therefore refuses with whatever the Scala client is
refused with for the same call (the host answers an unknown method as it answers any). The parity
tests compare the codes rather than restating them.

**Rationale**: FR-003 asks for the same refusals as Scala. Re-deciding them in the sidecar would
create a second source of truth.

## R4: Version refusal: three processes at the call; a Rust module at load

**Decision**: Python, TypeScript and Go map `UNIMPLEMENTED` from either rpc to their existing
too-old error, naming `1.24` (constant `READS_SINCE` or the language's equivalent, beside
`AWAIT_SINCE`). A Rust module imports `lifecycle` and `replication` from `ankka1`, each in its own
extern block so only a module that calls it imports it, as `await_end` is. A runtime older than 1.24
refuses to load such a module, with the message its loader gives for any import it does not provide.

**Rationale**: an older sidecar's loader rejects an unknown import before any code runs, and that
sidecar cannot name a version it predates. This is how every import since 1.6 has arrived
(`ModuleLoader.Imports`). It is a constraint, recorded in the spec's clarifications.

## R5: Component test kit stubs

**Decision**:
- **Python**: `ankka.testkit.ClientStub`, a client double whose `for_workflow(...).lifecycle()` and
  `for_event_sourced_entity(...).replication()` answer what the test set with `.lifecycle(component,
  id, Lifecycle(...))` and `.replication(component, id, ReplicationStatus(...))`. It refuses an
  unstubbed read, and every other call, with the same "use the integration test kit" error `_NoClient`
  gives. Every unit kit that takes a client accepts it.
- **TypeScript**: `stubClient({ lifecycle: [...], replication: [...] })`, returning a
  `ComponentClient` that answers those reads and refuses everything else as `noClient()` does. It is
  passed as the kits' existing `client` argument.
- **Go**: `testkit.AnswerLifecycle(component, id, ankka.Lifecycle{...})` and
  `testkit.AnswerReplication(...)`, beside `AnswerService`, served by `standIn`'s new `Lifecycle` and
  `Replication` methods.
- **Rust**: `InMemory` records each workflow's standing as the kit runs it (`start`, each step's
  outcome, pause, end, failure with its reason and retries) and answers `lifecycle` from it. It
  answers `replication` from an event sourced entity's history (position = events held; home and
  set from its replication declaration, or refused as not replicated). `with_lifecycle(component, id,
  ...)` and `with_replication(...)` set answers that win over what it runs (clarification 3).

**Rationale**: these follow each kit's existing pattern for a call it answers. Python and
TypeScript have no stub mechanism today, so a narrow one is added rather than a general mock
framework.

## R6: Each test kit reads through the running service

**Decision**: the Python, TypeScript and Go integration kits read through their existing client
(`kit.client`, `service.client`, `Kit.Client()`). Rust's integration kit has no component client and
talks HTTP, so its test reads the standing and the copy through the sample service's own endpoint
routes. The feature's step says "through the running service", which holds for all four.

## R7: Conformance at one installation

**Decision**: new conformance cases `wf.lifecycle-running`, `wf.lifecycle-failed`,
`es.replication-status` and `es.replication-refused`, each with a route in every reference (Scala,
Java, Kotlin, Python, TypeScript, Rust, Go). The cases answer JSON the suite compares with the Scala
client's own read of the same id. `es.replication-status` reads the existing `replicated-vehicle` at
installation `a`: home `a`, set `{a}`, position = events written. A handover waiting and a conflict
end to end stay with the Scala replication suites (clarification 2), which already prove them.

**Rationale**: no conformance target runs two installations for a process or a module
(`ReplicationSteps` binds the other rows of `replication/languages.feature` by `assume`), and the
clarification chose not to add that.

## R8: Documentation

**Decision**:
- **workflows.md**: Python, TypeScript, Rust and Go tabs, each from the language's transfer sample
  (`sdks/python/tests/transfer_workflow.py` or the shopping-cart endpoint, `transfer.ts`,
  `transfer.rs`, `samples/awaiting.go`), in a `#lifecycle` region.
- **multi-agent-orchestration.md**: Rust from `src/planner.rs` and Go from `samples/planner.go`, with
  Python and TypeScript from their planner or endpoint samples.
- **event-sourced-entities.md**: from each language's replicated-vehicle sample. The Python,
  TypeScript and Rust replicated blocks that are inline fences today stay as they are; only the new
  `replication-status` region is an include.
- **Elsewhere**: remove the three `<!-- languages: ... -->` comments, the limitations bullet and the
  "no lifecycle/replication" row in `component-client.md:168`. Add the two rpcs to
  `sidecar-protocol.md` (and fix its stale "currently 1.22") and the two imports to `wasm-abi.md`
  and `protocol/WASM-ABI.md`. Run `just docs-sync` for the skill copies.

## R9: Protocol version touch points

`Compatibility.scala:89` (and its doc comment) and each SDK's constant: Python `service.py:32`,
TypeScript `spec.ts:28`, Go `ankka.go:13`, Rust `service.rs:26`. The tests that pin `1.23` are
listed in the plan. Proto copies are regenerated by each SDK's script, and CI diffs them. Go's
`internal/pb` code is regenerated with `buf`.

## Found during planning

- The spec's User Story 2 first scenario named a read at "vienna" of a copy homed at "leipzig". No
  conformance target runs two installations (R7), so the per-language read is at the copy's own
  installation. The feature was changed to say so, and the two-installation reads are Scala's.

## Design finished

2026-11-10: analyze reported nothing above LOW (round 2). Round 1's seven findings (four MEDIUM,
three LOW) were remediated: FR-005 and the deleted-workflow edge case in T007, SC-001 matched to
the plan's proof, the wrong-kind edge case matched to R3, FR-014 checked in T058, the data model's
heading, and the docs sample paths named. Left: LOW, User Story 1's independent test reads as
end-to-end parity for every standing, where SC-001 says which are decoded.

## Found during implementation

- **A copy's position counts every record of its history, the platform's among them** (T032).
  `ReplicationStatus.position` is the copy's last sequence number, and a replicated entity's history
  holds the platform's record of its home (kind 3) and of its replication set (kind 4) beside its
  events, so a create with one event stands at 3. The spec's first wording ("holds 3 events … the
  position 3") read as events only; `features/replication/status.feature` now says how many records
  the history holds, which is what every language's read already answered (Scala's included, before
  this feature). Nothing in a contract changed.
- **An unstubbed read is refused with each kit's own no-client error** (T033, T034). The contract's
  stub table says `CommandError`; R5 says the stub refuses "with the same error `_NoClient` gives",
  which is a `RuntimeError` in Python and an `Error` in TypeScript. The kits follow R5, so a test
  that forgot a stub fails as one that forgot a client does, and the message names the component
  and the id that were not stubbed.
- **The Rust component kit's copy has no installation, so its position counts events alone** (T036).
  `InMemory` keeps no platform records: a replicated entity it runs answers no home, an empty set,
  and the number of events it holds, which is what "holds v1 with 3 events … position 3" says. A
  workflow kit sharing an endpoint kit's service (`EndpointTestKit::workflow`) records the standing
  it reaches there; the kit applies no recovery, so its retries are always empty.
