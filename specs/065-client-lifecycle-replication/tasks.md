# Tasks: Every client reads a workflow's standing and how a copy stands

**Input**: Design documents from `specs/065-client-lifecycle-replication/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/client-reads.md, quickstart.md

**Tests**: required. Every acceptance scenario ends as a test that fails without the feature (plan,
"Scenario to test"). Within a task, write the test first and see it fail.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story the task serves (US1 to US5)

---

## Phase 1: Setup (the protocol and its version)

- [ ] T001 Add `rpc Lifecycle` and `rpc Replication` with `LifecycleRequest`, `WorkflowStatus`, `WorkflowLifecycle`, `LifecycleReply`, `ReplicationRequest`, `ReplicationStatus` and `ReplicationReply` to `protocol/src/main/protobuf/ankka/protocol/v1/client.proto`, exactly as `contracts/client-reads.md` gives them, with the comment beside the rpcs; add the `lifecycle` and `replication` imports to `protocol/WASM-ABI.md`; set `protocol/README.md` to 1.24 (it says 1.22)
- [ ] T002 Set `ProtocolVersion(1, 24)` in `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/Compatibility.scala:89` with a 1.24 sentence in its history comment; update the "now 1.23" text in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/replication/ReplicationSteps.scala` and any other Scala test that pins 1.23 (`git grep -n '"1.23"\|1\.23' -- '*.scala'`)
- [ ] T003 [P] Python: regenerate `sdks/python/proto` (`cd sdks/python && uv run python scripts/proto.py`), set `PROTOCOL_VERSION = "1.24"` in `sdks/python/src/ankka/service.py:32`, update the pin in `sdks/python/tests/test_attribution.py:84`
- [ ] T004 [P] TypeScript: regenerate `sdks/typescript/proto` (`npm run proto`), set 1.24 in `sdks/typescript/src/spec.ts:28`, update the pins in `sdks/typescript/test/attribution.test.ts:69` and `test/contracts.test.ts:124`
- [ ] T005 [P] Go: regenerate `sdks/go/protocol` (`sdks/go/scripts/proto.sh`) and `sdks/go/internal/pb` (`buf generate`), set 1.24 in `sdks/go/ankka.go:13`, update the pins in `sdks/go/service_test.go:73,87,120`
- [ ] T006 [P] Rust: regenerate `sdks/rust/ankka/protocol` (`sdks/rust/scripts/proto.sh`), set 1.24 in `sdks/rust/ankka/src/service.rs:26`, update any test pinning 1.23 under `sdks/rust/ankka/tests`

**Checkpoint**: `sbt protocol/compile sidecar/compile`, each SDK builds, and CI's proto `diff -r` steps would pass.

---

## Phase 2: Foundational (the sidecar answers both reads)

- [ ] T007 Write `sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/ClientReadsSuite.scala` (pattern of `ClientAwaitSuite.scala`): through `ClientLogic`, the `Lifecycle` rpc answers a never-started, running (with its pending step), paused, completed and failed workflow (failure and retries), each equal to the Scala client's `lifecycle` of the same id, mapped as the contract's table says; the `Replication` rpc answers a replicated copy's home, sorted set and position equal to the Scala client's `replication`; a copy read of an unreplicated entity and a standing of an entity are refused with the same `ErrorCode` the Scala client is refused with; an unparseable id is `BAD_REQUEST`
- [ ] T008 Implement `lifecycle` and `replication` in `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/ClientLogic.scala` (beside `awaitEnd`, through `transport.askQuery` with `WorkflowLifecycle.Method` and `ReplicationStatus.Method`, decoding with their serializers, mapping `CommandError` as `invoke` does, and an unexpected status to `INTERNAL` naming it) and serve them from `ClientService.scala`; T007 passes
- [ ] T009 Add `lifecycle` and `replication` to `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/wasm/HostImports.scala` (`bytes(...)` over the two `ClientLogic` calls, beside `await_end`) and to `ModuleLoader.Imports` with its comment ("arrived with 1.24"); update the set `WasmHostSuite.scala:446` holds

**Checkpoint**: `sbt "sidecar/testOnly *ClientReadsSuite *WasmHostSuite"` is green.

---

## Phase 3: User Story 1 - A service in any language reads a workflow's standing (Priority: P1) 🎯 MVP

**Goal**: Python, TypeScript, Rust and Go handlers read a workflow's standing as Scala's do.

**Independent Test**: conformance `wf.lifecycle-running` and `wf.lifecycle-failed` pass for all seven references.

- [ ] T010 [P] [US1] Python: `Lifecycle` (fields `status: Status`, `pending_step`, `retries`, `failure`; `is_running`, `is_paused`, `is_completed`, `is_failed`, `is_terminal`) and `Status` in `sdks/python/src/ankka/client.py`, `Calls.lifecycle()` calling the `Lifecycle` rpc, `READS_SINCE = "1.24"` with a too-old error like `_await_unimplemented`, exported from `sdks/python/src/ankka/__init__.py`; test each status and the too-old error against a fake sidecar in `sdks/python/tests/test_client_reads.py`
- [ ] T011 [P] [US1] TypeScript: `Lifecycle` and its `status` union in `sdks/typescript/src/client.ts`, `Calls.lifecycle()` and `TypedCalls.lifecycle()`, `READS_SINCE` with a too-old error like `awaitTooOld`, exported from `src/index.ts`; test each status and the too-old error in `sdks/typescript/test/client-reads.test.ts`
- [ ] T012 [P] [US1] Go: `WorkflowLifecycle`, `WorkflowStatus` and `Lifecycle(ctx, component, id)` in `sdks/go/reads.go` (pattern of `clientkeys.go`), mapping `Unimplemented` through `tooOld("reading a workflow's standing", "1.24", err)`; test each status and `*ProtocolTooOldError` against `fakeSidecar` in `sdks/go/reads_test.go`
- [ ] T013 [P] [US1] Rust: `Import::Lifecycle`, `call_lifecycle` and its own extern block in `sdks/rust/ankka/src/abi/imports.rs` (as `await_end` is, so only a caller imports it), the native host dispatch and `NativeHost` method; `Lifecycle` and `WorkflowStatus` in a new `sdks/rust/ankka/src/reads.rs` exported from `lib.rs` and `prelude.rs`; `ComponentClient::lifecycle` in `sdks/rust/ankka/src/client.rs`; test the decode of each status in `sdks/rust/ankka/tests/reads.rs` with a native host answering bytes
- [ ] T014 [US1] Add conformance cases `wf.lifecycle-running` (a workflow held in its step answers running and the step) and `wf.lifecycle-failed` (a step failing past its retries answers failed, the reason and the retries) to `sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/conformance/ConformanceSuite.scala`, each comparing the route's JSON with the Scala client's read of the same id, and their route in `ConformanceReference.scala`
- [ ] T015 [P] [US1] The two lifecycle routes in `modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/ConformanceEndpoint.java`
- [ ] T016 [P] [US1] The two lifecycle routes in `sdks/kotlin/samples/reference/src/main/kotlin/com/thinkmorestupidless/ankka/kotlin/reference/ConformanceEndpoint.kt`
- [ ] T017 [P] [US1] The two lifecycle routes in `sdks/python/examples/shopping_cart/conformance.py`
- [ ] T018 [P] [US1] The two lifecycle routes in `sdks/typescript/examples/shopping-cart/conformance.ts`
- [ ] T019 [P] [US1] The two lifecycle routes in `sdks/rust/examples/shopping-cart/src/conformance.rs`
- [ ] T020 [P] [US1] The two lifecycle routes in `sdks/go/examples/shopping-cart/conformance/endpoints.go`

**Checkpoint**: each language's conformance runner passes the two cases.

---

## Phase 4: User Story 2 - A service in any language reads how a copy stands (Priority: P2)

**Goal**: Python, TypeScript, Rust and Go handlers read a replicated copy's status as Scala's do.

**Independent Test**: conformance `es.replication-status` and `es.replication-refused` pass for all seven references.

- [ ] T021 [P] [US2] Python: `ReplicationStatus` (`home`, `replication_set: frozenset`, `pending_handover`, `conflict`, `position`, `in_conflict`) and `Calls.replication()` in `sdks/python/src/ankka/client.py`, with the same too-old error; tests in `sdks/python/tests/test_client_reads.py`
- [ ] T022 [P] [US2] TypeScript: `ReplicationStatus` and `Calls.replication()` / `TypedCalls.replication()` in `sdks/typescript/src/client.ts`; tests in `sdks/typescript/test/client-reads.test.ts`
- [ ] T023 [P] [US2] Go: `ReplicationStatus` and `Replication(ctx, component, id)` in `sdks/go/reads.go`; tests in `sdks/go/reads_test.go`
- [ ] T024 [P] [US2] Rust: `Import::Replication`, its extern block and dispatch in `sdks/rust/ankka/src/abi/imports.rs`; `ReplicationStatus` in `src/reads.rs`; `ComponentClient::replication` in `src/client.rs`; tests in `sdks/rust/ankka/tests/reads.rs`
- [ ] T025 [US2] Add conformance cases `es.replication-status` (the `replicated-vehicle` copy at installation `a` after events: home, set and position equal to the Scala client's read) and `es.replication-refused` (an unreplicated entity's copy read refused with the Scala client's code) to `ConformanceSuite.scala`, with their routes in `ConformanceReference.scala`
- [ ] T026 [P] [US2] The two replication routes in `modules/sdk-java/src/test/java/com/thinkmorestupidless/ankka/javasdk/reference/ConformanceEndpoint.java`
- [ ] T027 [P] [US2] The two replication routes in `sdks/kotlin/samples/reference/src/main/kotlin/com/thinkmorestupidless/ankka/kotlin/reference/ConformanceEndpoint.kt`
- [ ] T028 [P] [US2] The two replication routes in `sdks/python/examples/shopping_cart/conformance.py`
- [ ] T029 [P] [US2] The two replication routes in `sdks/typescript/examples/shopping-cart/conformance.ts`
- [ ] T030 [P] [US2] The two replication routes in `sdks/rust/examples/shopping-cart/src/conformance.rs`
- [ ] T031 [P] [US2] The two replication routes in `sdks/go/examples/shopping-cart/conformance/endpoints.go` (components in `components.go`)
- [ ] T032 [US2] Bind the Scala scenarios of `features/replication/status.feature` (a handover waiting, a conflict, an installation outside the replication set) to the two-installation replication steps in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/replication/` (extend `ReplicationSteps.scala` and its suite), reading through the Scala client's `replication`; bind the per-language rows as `ReplicationSteps` binds `languages.feature`'s

**Checkpoint**: the four conformance cases pass for all seven; the Scala replication suite binds the status feature.

---

## Phase 5: User Story 3 - A developer tests these reads without a cluster (Priority: P2)

**Goal**: each component test kit answers the reads it was told, refuses one it was not, and Rust's also answers from what it runs; each test kit reads through the running service.

**Independent Test**: the kit scenarios of both features pass in each of the four languages.

- [ ] T033 [P] [US3] Python: `ClientStub` in `sdks/python/src/ankka/testkit/unit.py` (`.lifecycle(component, id, Lifecycle)`, `.replication(component, id, ReplicationStatus)`; refuses an unstubbed read naming its id, and every other call as `_NoClient` does), accepted by every unit kit that takes a client, exported from `testkit/__init__.py`; tests in `sdks/python/tests/test_testkit_reads.py`, including an endpoint reading a failed standing and a handover and a conflict; an integration test reading a real workflow's standing and copy through `kit.client` in `sdks/python/tests/test_integration_reads.py`
- [ ] T034 [P] [US3] TypeScript: `stubClient({ lifecycle, replication })` in `sdks/typescript/src/testkit/unit.ts`, exported from `testkit/index.ts`; tests in `sdks/typescript/test/testkit-reads.test.ts`; an integration test through `service.client` in `sdks/typescript/test/integration-reads.test.ts`
- [ ] T035 [P] [US3] Go: `AnswerLifecycle` and `AnswerReplication` options and `standIn`'s `Lifecycle` and `Replication` methods in `sdks/go/testkit/kinds.go`; tests in `sdks/go/testkit/reads_test.go`; an integration test through `Kit.Client()` in `sdks/go/testkit/integration_reads_test.go`
- [ ] T036 [US3] Rust: `InMemory` in `sdks/rust/ankka/src/testkit/unit.rs` records each workflow's standing as the kit runs it (data-model.md) and answers `lifecycle` from it, answers `replication` from an event sourced entity's history, and keeps `with_lifecycle` / `with_replication` answers that win; tests in `sdks/rust/ankka/tests/unit_testkit.rs` for computed, told-wins and unstubbed; an integration test through the sample's routes in `sdks/rust/examples/shopping-cart/tests/`

**Checkpoint**: each SDK's kit tests are green.

---

## Phase 6: User Story 4 - A service on an older runtime is told why (Priority: P3)

**Goal**: the too-old refusals of FR-007.

**Independent Test**: the four "runtime from before the read" scenarios.

- [ ] T037 [P] [US4] Python, TypeScript and Go: confirm the too-old tests of T010 to T012 and T021 to T023 cover both reads and name `1.24`; add any missing to the three test files
- [ ] T038 [US4] Rust: in `sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/wasm/WasmHostSuite.scala`, a module that calls `lifecycle` imports it and one that does not, does not (as `await_end` at :554); a loader whose `Imports` lacks `lifecycle` refuses the first with the message naming the import

**Checkpoint**: the four scenarios have their tests.

---

## Phase 7: User Story 5 - The pages show every language (Priority: P3)

**Goal**: the three sections show seven tabs from tested samples.

**Independent Test**: `just docs` passes with the exclusions removed.

Samples, each a `docs:start`/`docs:end` (or the language's region marker) region in a file a test runs:

- [ ] T039 [P] [US5] workflows.md, Python: a `#lifecycle` region reading a workflow's standing in `sdks/python/examples/shopping_cart/endpoint.py`, with a test
- [ ] T040 [P] [US5] workflows.md, TypeScript: a `#lifecycle` region in `sdks/typescript/examples/shopping-cart/endpoint.ts`, with a test
- [ ] T041 [P] [US5] workflows.md, Rust: a `#lifecycle` region in `sdks/rust/examples/shopping-cart/src/transfer.rs`, with a test
- [ ] T042 [P] [US5] workflows.md, Go: a `#lifecycle` region in `sdks/go/examples/shopping-cart/samples/awaiting.go`, with a test
- [ ] T043 [P] [US5] multi-agent-orchestration.md, Python: an `#observe` region in the file its other Python tabs include from, with a test
- [ ] T044 [P] [US5] multi-agent-orchestration.md, TypeScript: an `#observe` region in the file its other TypeScript tabs include from, with a test
- [ ] T045 [P] [US5] multi-agent-orchestration.md, Rust: an `#observe` region in `sdks/rust/examples/shopping-cart/src/planner.rs`, with a test
- [ ] T046 [P] [US5] multi-agent-orchestration.md, Go: an `#observe` region in `sdks/go/examples/shopping-cart/samples/planner.go`, with a test
- [ ] T047 [P] [US5] event-sourced-entities.md, Python: a `#replication-status` region beside its replicated-vehicle sample, with a test
- [ ] T048 [P] [US5] event-sourced-entities.md, TypeScript: a `#replication-status` region beside its replicated-vehicle sample, with a test
- [ ] T049 [P] [US5] event-sourced-entities.md, Rust: a `#replication-status` region beside its replicated-vehicle sample, with a test
- [ ] T050 [P] [US5] event-sourced-entities.md, Go: a `#replication-status` region in `sdks/go/examples/shopping-cart/conformance/components.go` or a sample beside it, with a test

Pages:

- [ ] T051 [US5] `docs/build/workflows.md` "Domain state and lifecycle": Python, TypeScript, Rust and Go tabs including T039 to T042; remove the `<!-- languages: ... -->` comment at :1500; the prose keeps "standing"
- [ ] T052 [US5] `docs/build/multi-agent-orchestration.md`: the four tabs including T043 to T046; remove the comment at :1689
- [ ] T053 [US5] `docs/build/event-sourced-entities.md` "Reading how a copy stands": the four tabs including T047 to T050; remove the comment at :1988
- [ ] T054 [US5] Remove the "Only a Scala, Java or Kotlin client reads a workflow's lifecycle…" bullet from `docs/reference/limitations.md`; update the lifecycle/replication row of `docs/build/component-client.md:168`
- [ ] T055 [US5] `docs/reference/sidecar-protocol.md`: the two rpcs in the table, a 1.24 history line, and "currently 1.24" (it says 1.22); `docs/reference/wasm-abi.md`: the two imports
- [ ] T056 [US5] `just docs-sync && just docs`: no problems, and the skill copies under `marketplace/` and `ankka.g8/` refreshed

**Checkpoint**: `just docs` reports no problems; the three sections carry seven tabs.

---

## Phase 8: Polish & cross-cutting

- [ ] T057 Write any trap this work cost time on, dated, in the `.claude/rules/` file for its area (`sidecar.md`, `wasm.md`, `sdk-go.md`, `sdk-typescript.md`, …)
- [ ] T058 Run `quickstart.md` end to end and `just features` (0 findings)

---

## Dependencies & Execution Order

- Phase 1, then Phase 2: every later phase needs the regenerated protocol and the sidecar's answers.
- US1 (Phase 3) and US2 (Phase 4) are independent of each other once Phase 2 is done. T014 needs
  T010 to T013 only for each language's own route; T015 to T020 need T014's route shape.
- US3 (Phase 5) needs US1's and US2's types in each SDK (T010 to T013, T021 to T024).
- US4 (Phase 6) needs T009 and the SDK calls.
- US5 (Phase 7) needs the SDK calls; the page tasks need their samples.
- Polish last.

## Parallel Examples

- Phase 1: T003, T004, T005 and T006 together after T001.
- US1: T010 to T013 together; then T015 to T020 together after T014.
- US5: T039 to T050 together; then T051 to T053.

## Implementation Strategy

- **MVP**: Phases 1 to 3. Every language reads a workflow's standing, proved by conformance.
- Then US2 (the copy read), US3 (the kits), US4 (the refusals), US5 (the pages), each a green
  checkpoint and its own commits.
- The docs check refuses a page with a language missing, so US5 is not optional: the feature is done
  when the three pages show all seven.
