# Tasks: Workflow Sources — A View or a Consumer Reads a Workflow

**Input**: Design documents from `/specs/046-workflow-sources/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" claims each become a test
before the code that relies on them. Where a task says "case", it means a `test(...)` in the named
suite (or its equivalent in the SDK's test runner). A case that proves a scenario of `features/`
is named with that scenario's name, so the two can be found from each other; a feature file a
`GherkinSuite` runs needs no named cases, the suite makes one test per scenario.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a view lists where every checkout stands), US2 (a consumer reacts when a
  workflow ends), US3 (a workflow is a source in every language), US4 (a view over a workflow is
  rebuilt and bounded as a view over an entity is), US5 (the documentation)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `SDK` =
`modules/sdk/src/main/scala/…/sdk`; `RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `CPA` =
`controlplane-api/src/main/scala/…/controlplane/api`; `PROTO` =
`protocol/src/main/protobuf/ankka/protocol/v1`; `PY` = `sdks/python`; `TS` = `sdks/typescript`;
`RS` = `sdks/rust`; `FEAT` = `features/workflow-sources`; `DOCS` = `docs`. "R*n*" is a section of
`research.md`; "S*n*" a rule of `contracts/scala-api.md`. A contract is named by its file under
`contracts/`.

The branch `046-workflow-sources` exists, in the worktree
`.claude/worktrees/046-workflow-sources`, on `main` at #102. Work there. The feature files, the
glossary, the clarified spec and the plan documents are written and uncommitted.

---

## Phase 1: Setup — the baseline is known

- [X] T001 Record the baseline the feature must not disturb: run `sbt 'testkit/testOnly *ViewVersionSuite *EntityViewVersionSuite *KeyedViewSuite *KeyValueDeletionSuite *ConsumerTestKitSuite' 'runtime/testOnly *ViewProjectionsSuite *KeyedViewRulesSuite *TopologyJsonSuite' 'sidecar/testOnly *RemoteProjectionSuite *RemoteWorkflowSuite *ProtocolSuite' 'shoppingCart/testOnly *CheckoutWorkflowSuite'` and note in the pull request description that they pass, with `CheckoutWorkflowSuite`'s wall time: it is compared again in T071.
- [X] T002 [P] Run `python3 .github/ci-coverage.py` with the uncommitted files present (`features/workflow-sources/`, `features/documentation/workflow-sources.feature`, `specs/046-workflow-sources/`) and confirm each is claimed by an existing filter and no pattern is left matching nothing.
- [X] T003 [P] Confirm `.specify/feature.json` names `specs/046-workflow-sources` and commit the spec, plan, features and glossary as the branch's first commit, so the implementation's diff reads apart from the design's.

**Checkpoint**: the suites this feature changes are known green; the design is committed.

---

## Phase 2: Foundational — the stamp, the reader, and protocol 1.15, each written once

**Purpose**: the three things every story depends on and no story may do differently (plan,
"Slices" 1, and the wire of slice 3 written here so no runtime ever speaks 1.15 and does nothing
with part of it). None of it reads a workflow as a source yet; a service at the end of this phase
journals stamped `state` records and is otherwise unchanged.

**⚠️ CRITICAL**: blocks every user story.

### The stamp (R2)

- [X] T004 Write `TKT/WorkflowRecordCompatibilitySuite.scala`, modelled on `StateRecordCompatibilitySuite`, with its fixture `modules/testkit/src/test/resources/journal/workflow-record.txt`. **Before touching the record**, generate and commit the fixture from today's `WorkflowRecord` for one record of each kind (`state` with a JSON state, `transition` with an input, `pause` with a deadline, `end`, `fail`, `retry`, `delete`), in hex through `SerializationExtension`, as that suite does. Then the cases: each pinned line reads back equal to its record and, once T005 lands, a pinned `state` line reads back with `standing = None`; a `state` record with a `StandingRecord` round-trips; the pinned lines of every other kind are byte-for-byte what the new code writes; and the reverse direction (SC-004): the new form's `state` line, with a stamp, deserializes through the same `SerializationExtension` binding into a private copy of the pre-feature case class (`LegacyWorkflowRecord`, the six fields and no `standing`), which is what the release before does with a property it does not know. The first run must pass against the unchanged record and the fixture must be committed before T005. *Done*: the fixture was pinned and committed (22264901) before the field existed. The new field is written only when present (`@JsonInclude(NON_ABSENT)`), so unstamped records stay byte for byte the pinned form.
- [X] T005 In `RT/wire.scala` add `final case class StandingRecord(status: String, step: String, retries: Map[String, Int], failure: String) extends AnkkaSerializable` and `WorkflowRecord.standing: Option[StandingRecord] = None` as the last field, with `stateUpdated(state, standing: Option[StandingRecord])` and the doc comment's `kind = 0` line saying the stamp is there from this release (`data-model.md`). Every other constructor leaves it `None`. T004 passes whole.
- [X] T006 In `RT/WorkflowHost.scala`: `Event.StateUpdated(state: S, standing: Option[StandingRecord] = None)`; the event adapter writes the stamp into the record and reads it back; `applyEvent` ignores it. Add `WorkflowHost.standingOf(run: Run[?]): StandingRecord` (status by name, the pending step or the pause's timeout step or the step paused after — read from `pending`, `pauseOnTimeout` and what the engine passes — retries, failure or `""`), the one place a `Run` becomes a standing.
- [X] T007 In `RT/WorkflowEngine.scala`, in `onInvoke` (the `events` builder) and `onStepSucceeded` (every `stateEvents :+ …` branch): build the batch as today, fold it over the current `Run` with `WorkflowHost.applyEvent` (make the fold reachable to the engine), and replace the batch's `StateUpdated(s, None)` with `StateUpdated(s, Some(standingOf(after)))` before `persist`. A `Paused` batch's standing names the pause's `onTimeout` step when there is one, else the step the batch paused after (`succeeded.step`). Nothing else in the engine changes; `RemoteWorkflowSuite` and `CheckoutWorkflowSuite` stay green.
- [X] T008 In `TKT/WorkflowRecordCompatibilitySuite.scala` add the engine-level case: start a one-step workflow in `AnkkaTestKit`, run `updateState(s).thenEnd`, read its journal rows (`journal("<id>|<wf>")` as `ConformanceSuite` does) and assert the `state` record's `standing.status == "Completed"` and the `end` record's `standing` is empty; a `start` command that updates and transitions stamps `Running` with the step; a step that `thenPause`s stamps `Paused` with the timeout step. This is the "stamp before the fold" line T024's break-it-once names. *Done differently*: the engine-level cases are `TKT/WorkflowStampSuite.scala`, which reads the journal back. It covers completed, running on a step, paused naming the timeout step, paused naming the step paused after, and failed with the reason.

### The reader (R3, R4)

- [X] T009 [P] Write `RTT/WorkflowChangesSuite.scala`, pure: a `state` record with a stamp to `State(payload, lifecycle)` whose status, step, retries and failure are the stamp's; a `state` record without one to a lifecycle with `status == "Unknown"` and `isUnknown`; `delete` to `Deleted`; `transition`, `pause`, `end`, `fail`, `retry` each to `Nothing`; a state packed by `RemoteWorkflowHost`'s serializer (manifest `ankka.remote.workflow`, prefix of two lengths) unpacked to its own content type, manifest and data; a plain state to a payload with the source's declared manifest and the bytes untouched; `NoState` (empty bytes) to a payload with empty data.
- [X] T010 Create `RT/WorkflowChanges.scala` per `data-model.md`: `enum WorkflowChange { State(payload: Payload, standing: WorkflowLifecycle); Deleted; Nothing }` and `read(record: WorkflowRecord, declaredManifest: String): WorkflowChange`. Move the remote packing's `fromBytes` into a function both `RemoteWorkflowHost.stateSerializer` and `read` call, so the prefix is written and read by one piece of code. `WorkflowLifecycle` gains `isUnknown` in `SDK/WorkflowLifecycle.scala` and `Unknown` is named once, as `WorkflowLifecycle.Unknown`. T009 passes. *Done differently*: one enum, `SourceChange` (`Changed(payload, standing)`, `Deleted`, `Skip`), in `RT/ChangeReader.scala` beside `WorkflowChanges.read`, rather than a second `WorkflowChange` type. The packing moved to `RemoteWorkflowHost.pack`/`unpack`. A remote source is read by `ChangeReader.remoteWorkflow`, chosen by the source rather than sniffed from the bytes.
- [X] T011 Create `RT/ChangeReader.scala`: `trait ChangeReader[A] { def read(event: A): ReadChange }` with `ReadChange.Payload(payload: Payload, standing: Option[WorkflowLifecycle]) | Deleted | Nothing`; `ChangeReader.journal` is `ProjectionSupport.runView`'s and `RemoteProjection`'s `JournalRecord` matches **moved** (domain → payload with the record's manifest, deleted → `Deleted`, expiry → `Nothing`, standing `None`); `ChangeReader.workflow(declaredManifest)` delegates to `WorkflowChanges.read`.
- [X] T012 Type the event handlers by their record: in `RT/ProjectionRuntime.scala` `ViewEventHandler[A]` and `ConsumerEventHandler[A]`, in `RT/KeyedViewHandlers.scala` `KeyedViewEventHandler[A]`, in `RT/remote/RemoteProjection.scala` `RemoteViewEventHandler[A]` and `RemoteConsumerEventHandler[A]`, each over `EventEnvelope[A]` and given a `ChangeReader[A]`, with `ProjectionSupport.runView` taking a `ReadChange` rather than a `JournalRecord`. Every existing construction passes `ChangeReader.journal`. Add `workflowSource(sourceId, range)` (`eventsBySlices[WorkflowRecord]`), `exactlyOnceWorkflowProjection` and `atLeastOnceWorkflowProjection` beside the event ones, unused yet. `SimpleChangeContext` and `KeyedViewHost.handle` carry an `Option[WorkflowLifecycle]`, `None` from every existing caller (T013 adds the field). Every suite of T001 stays green: this task changes no behaviour. *Done*: the keyed hosts take a `KeyedHandle` of payload and standing, so a process's packed state keeps its own content type.

### The standing on the context (R5)

- [X] T013 In `SDK/ChangeSource.scala` add `ChangeContext.standing: Option[WorkflowLifecycle]` and the field on `SimpleChangeContext` with default `None`; in `SDK/KeyedView.scala` add `KeyedChange.standing: Option[WorkflowLifecycle]`; `TK/KeyedViewTestKit.scala`'s private `Change` and `TK/ConsumerTestKit.scala`'s context gain it as `None`. `sbt compile` warning-free.

### Protocol 1.15, written once (R6; `contracts/protocol.md`)

- [X] T014 Edit `PROTO/payload.proto` (`message WorkflowStanding`), `PROTO/view.proto` (`ViewRequest.standing = 7`) and `PROTO/consumer.proto` (`ConsumerRequest.standing = 5`), exactly as `contracts/protocol.md` gives them, each commented `1.15`. Update `protocol/README.md` (the version is `1.15`; one paragraph on what it added) and `protocol/WASM-ABI.md` (one sentence: `ankka1_view` and `ankka1_consumer` receive `standing` for a workflow source).
- [X] T015 Bump the version where it is written: `WireProtocol.Version` in `RT/remote/Conversation.scala`; `Protocol.version` and its changelog comment in `CPA/Compatibility.scala` with its suite's assertion; the changelog comment on `ProtocolVersion` in `SC/Discovery.scala`. In `RT/remote/Conversation.scala` add `standing: Option[WorkflowLifecycle] = None` to the plain `ViewRequest` and `ConsumerRequest`.
- [X] T016 [P] Python: run `PY/scripts/proto.py`; set `PROTOCOL_VERSION = "1.15"` in `PY/src/ankka/service.py`. `uv run pytest -q && uv run mypy` stay green.
- [X] T017 [P] TypeScript: run `npm run proto` in `TS`; set `PROTOCOL_VERSION = "1.15"` in `TS/src/spec.ts`. `npm run typecheck && npm test` stay green.
- [X] T018 [P] Rust: run `RS/scripts/proto.sh`; set `PROTOCOL_VERSION = "1.15"` in `RS/ankka/src/service.rs`. `cargo test --workspace` stays green.
- [X] T019 In `SC/Translate.scala` map `standing` both ways (`toViewRequest`, `toConsumerRequest`, and a `toStanding`/`fromStanding` pair for `WorkflowLifecycle` ↔ `WorkflowStanding`, `Unknown` carried as the word); in `SCT/TranslateSuite.scala` a case per direction, including an absent standing staying absent. Run `sbt 'sidecar/testOnly *ProtocolSuite *TranslateSuite' 'controlPlaneApi/test'` and the CI step that diffs the three SDK copies against `protocol/`.

**Checkpoint**: a stamped journal reads on both releases; a workflow record reads as a change in
one place; every event handler is typed and unchanged in behaviour; the wire is 1.15 everywhere
and nothing behaves differently.

---

## Phase 3: User Story 1 — A view lists where every checkout stands (Priority: P1) 🎯 MVP

**Goal**: a Scala plain view declares `ChangeSource.stateOf(Checkout)`, is handed each state the
workflow recorded with its standing, and a declared query lists rows by standing.

**Independent Test**: `sbt 'testkit/testOnly *WorkflowSourceSuite *WorkflowSourcesFeatures'`
(quickstart, "User Story 1").

### Tests for User Story 1 (write first; they fail)

- [X] T020 [P] [US1] Write `TKT/views/WorkflowKit.scala`: a scripted workflow `checkout` with steps `reserve`, `charge`, `refund`, whose `start(script)` command and each step do what the script says — record a state (`CheckoutState(id, note, standing-free)`), transition, pause (with or without a timeout step), end, fail, fail after retries with or without a failover to `refund` that records the failure and fails — plus a `delete` command and a whole-workflow `timeout` setting the script can set; a plain view `checkouts` over `ChangeSource.stateOf(CheckoutWorkflow)` writing `CheckoutRow(id, state, standing: String, step: Option[String], failure: Option[String])` from `updateContext.standing`, with the declared query `by-standing`; a consumer `settlement` and the keyed view `fulfilment` and the entity `order` US2 and US4 need, so the kit is written once. Register them in a service builder the feature suites share.
- [X] T021 [P] [US1] Write `TKT/views/WorkflowSourceSuite.scala` on `AnkkaTestKit`, holding what a feature cannot say (R4 *verify first*): run a checkout through `start` (state + transition), `reserve` (state + transition) and `charge` (state + end), and assert the view wrote the row three times (a counter in the row), each write's standing in order `Running/reserve`, `Running/charge`, `Completed`; `restartService()` and assert no fourth write; two workflows in different slices under `parallelism = 4` both reach their rows; a state recorded by the previous release — written into the journal table directly from `workflow-record.txt`'s pinned `state` line under a fresh persistence id — is delivered with the standing `Unknown`; and SC-002: a consumer over the same workflow publishes within the feature suites' `eventually` default of the `end` record being journalled, timed from the journal row's write to the in-memory broker's delivery, with the number asserted under the bound and printed.
- [X] T022 [P] [US1] Write `TKT/views/WorkflowSourcesFeatures.scala` (`extends GherkinSuite("../../features/workflow-sources/views.feature") with LogCapturing`, the `SeveralSourcesFeatures` pattern) with the steps `FEAT/views.feature` uses over `WorkflowKit`: "runs from its start to its end", "fails after its retries and fails over to … which records the failure in the state and fails the workflow", "fails over to nothing", "records its state and pauses", "records its state and moves to", "has read every state recorded by", "restarts with … at version 2", "is deleted", "a topic and a workflow may not be sources of one view" (US4's refusal, over a descriptor the kit builds on demand), "recorded a state on a release before workflow sources" (T021's direct write), and the row assertions ("holds the state … ended with", "has the standing …", "names the step …", "holds the reason", "is as it was", "is handed no change for the failure" — read off the row's write counter). It fails: undefined behaviour, not undefined steps.
- [X] T023 [P] [US1] In `RTT/TopologyJsonSuite.scala` add a case: a view over a workflow source yields a connection of kind `workflow` from the workflow to the view, and a workflow the service does not register is drawn outside it (the `entity(...)` path). In `RTT/KeyedViewRulesSuite.scala` add: a keyed view over a workflow and an entity passes; over a topic and a workflow is refused with the words `FEAT/views.feature` expects.

### Implementation for User Story 1

- [X] T024 [US1] In `SDK/ChangeSource.scala` add `final case class Workflow[Src](componentId, decoder)` with `describe = s"workflow($componentId)"` and `def stateOf[W <: Workflow[S], S](companion: Workflow.Companion[W, S]): ChangeSource[S]` beside the key value overload (R1). In `SDK/KeyedView.scala` `KeyedSource.componentId` reads the case. Fix every non-exhaustive match `-Werror` now reports — `RT/DeclaredConnections.scala` (`DeclaredSource.Workflow`, both `of`s), `RT/KeyedViewRules.scala` (a workflow is a component with a change stream; the refusal names what was read, so a topic beside a workflow says exactly `a topic and a workflow may not be sources of one view`, and beside an entity what it says today), `RT/TopologyJson.scala` (`entity(component, ComponentKind.Workflow, "workflow")`), `RT/ProjectionRuntime.scala` (the cases T025 fills) — so the compiler is the checklist.
- [X] T025 [US1] In `RT/ProjectionRuntime.scala` `startView`: the `ChangeSource.Workflow(sourceId, decoder)` case builds the same `startEntityView` daemon and `ViewProjections` names an event source does, over `exactlyOnceWorkflowProjection` with `ViewEventHandler(typed, client, guard, ChangeReader.workflow(decoder.manifest))`. `ProjectionSupport.runView` sets `SimpleChangeContext(subject, seq, localOrigin = true, standing)` from the `ReadChange` and decodes the payload with the source's decoder. T021 passes; the view scenarios of T022 pass except the US4 ones. *Done*: the remote and keyed starters (T037, T047) were wired in the same pass, so the compiler's exhaustiveness checks were the checklist.
- [X] T026 [US1] In `cli/src/main/resources/console/app.js` `describeConnection`, add `'workflow': 'a workflow subscription'`; in `TKT/topology/TopologySteps.scala` add the step "the topology shows a declared connection from {string} to {string} as a workflow subscription" mapping to `"workflow"`. T023 passes.
- [X] T027 [US1] Run `sbt 'testkit/testOnly *WorkflowSourceSuite *WorkflowSourcesFeatures' 'runtime/testOnly *TopologyJsonSuite *KeyedViewRulesSuite *ViewProjectionsSuite'`; every US1 scenario of `FEAT/views.feature` is a passing test, `ViewProjectionsSuite` is unchanged and green. **Break it once**: stamp the standing of the `Run` *before* the fold in T007 and "a row holds the state a workflow ended with and the standing completed" must go red with `Running`; make `WorkflowChanges.read` deliver a `fail` record as a state and "a workflow that fails without recording its state delivers no change" must go red. Restore both. *Done*: the stamp taken before the fold turned the completed scenario red. A `fail` record read as a change turned `WorkflowChangesSuite` red; in a running service it would stall the projection on empty state bytes rather than reach a view, so the pure suite is what holds it.

**Checkpoint**: a Scala plain view reads a workflow with its standing; the MVP.

---

## Phase 4: User Story 2 — A consumer reacts when a workflow ends (Priority: P1)

**Goal**: a consumer over a workflow is handed each recorded state with its standing, at least
once and in order, publishes or calls on the standing it wants, and runs its deletion handler.

**Independent Test**: `sbt 'testkit/testOnly *WorkflowSourcesFeatures -- "*consumers*"' 'testkit/testOnly *ConsumerTestKitSuite'` (quickstart, "User Story 2").

### Tests for User Story 2 (write first; they fail)

- [X] T028 [P] [US2] Add `TKT/views/WorkflowConsumersFeatures.scala` (`GherkinSuite("../../features/workflow-sources/consumers.feature")`) with the steps `FEAT/consumers.feature` uses over `WorkflowKit`'s `transfer` workflow (the same scripted workflow under a second id), `settlement` consumer and `ledger` entity, with the in-memory broker: "publishes to the topic … for each change whose standing is completed", "calls … for each change whose standing is failed", "records its state and moves to", "declares a timeout of … for the whole workflow", "is handed one change for it / a change whose standing is failed / no change for the timeout" (read off the consumer's own log of what it was handed, keyed by scenario), "one message about … is published to", "is called once about", "the deletion handler of … runs for".
- [X] T029 [P] [US2] In `TKT/ConsumerTestKitSuite.scala` add cases: `onMessage(state, subject, sequenceNumber, standing = Some(WorkflowLifecycle("Completed", …)))` lets the consumer read `messageContext.standing`; the default is `None`; a `GraphConsumerTestKit` passes it through.

### Implementation for User Story 2

- [X] T030 [US2] In `RT/ProjectionRuntime.scala` `startConsumer`: the `ChangeSource.Workflow` case builds the `ankka-consumer-<id>` daemon over `atLeastOnceWorkflowProjection` with `ConsumerEventHandler(..., ChangeReader.workflow(decoder.manifest))`; the handler sets the consumer's context with the standing and decodes the payload with the source's decoder; a `Deleted` runs `onDelete`; a `Nothing` is `Done` with no handler call and no span. `TopicSourceRules` refuses a `version` on a consumer over a workflow as on an entity.
- [X] T031 [US2] In `TK/ConsumerTestKit.scala` add `standing: Option[WorkflowLifecycle] = None` to `onMessage` and `onDelete`, set on the context the consumer reads; `TK/KeyedViewTestKit.scala` likewise on `change` and `deleted` (US4 reads it). T029 passes.
- [X] T032 [US2] Run `sbt 'testkit/testOnly *WorkflowConsumersFeatures *ConsumerTestKitSuite'`; every scenario of `FEAT/consumers.feature` passes. **Break it once**: deliver `transition` records as changes and "a consumer publishes once when a workflow ends as completed" must go red on "no message is published for the changes of t1 before its end" — if it does not, the step is not counting deliveries and must. *Done*: the same reasoning as T027. A transition read as a change carries no state and cannot reach the consumer, so `WorkflowChangesSuite`'s "a record that holds no state is no change" is the red proof.

**Checkpoint**: a Scala consumer reads a workflow; both P1 stories are whole in Scala.

---

## Phase 5: User Story 3 — A workflow is a source in every language (Priority: P2)

**Goal**: a process or module declares a workflow source where it names an entity and is handed
the same change with the standing; a runtime too old refuses it at discovery, and an SDK refuses
a runtime too old.

**Independent Test**: `sbt 'sidecar/testOnly *ConformanceSuite -- "*workflow*"'` against the Scala reference, then each SDK's conformance run (quickstart, "User Story 3").

### Tests for User Story 3 (write first; they fail)

- [X] T033 [P] [US3] In `CONF/ConformanceReference.scala` add mode `abort` to `Checkout` (`compensate` records `status = "aborted"` and `thenFail(CommandError("payment declined", ErrorCode.Internal))`), the view `checkout-rows` (`ChangeSource.stateOf(Checkout)`, row `{id, status, standing, step?, failure?}`, query `by-standing`), the consumer `checkout-ends` (records `{id, standing, failure?}` into the `profile` key value entity under the checkout's id on `Completed`/`Failed`, `ignore` otherwise), and the routes `GET /conformance/checkout/{id}/row`, `GET /conformance/checkout-rows?standing=`, `GET /conformance/checkout/{id}/end` (`contracts/sdk-apis.md`). Mark `// docs:start workflow-view` / `workflow-consumer` regions on the two components for US5. *Done differently*: a `drop` mode (the compensation fails and records nothing) stands in for a whole-workflow timeout, which would have broken the existing `pause` case. `checkout-ends` records each end in `profile` under `end-<id>`, and the list route is `/conformance/checkout-rows/{standing}`. `contracts/sdk-apis.md` says so.
- [X] T034 [P] [US3] In `CONF/ConformanceSuite.scala` add the seven cases of `contracts/sdk-apis.md` — `view.workflow-completed`, `view.workflow-failure-recorded`, `view.workflow-no-change-without-state` (a checkout whose whole-workflow timeout fires during `wait`: the row stays at its last state and standing and the journal holds the `fail` record), `view.workflow-by-standing`, `consumer.workflow-end-once`, `topology.workflow-subscription`, `discovery.workflow-source-needs-1.15` (the suite's discovery double stating `1.14` to the target is refused naming `checkout-rows`, `checkout-ends` and both versions) — each `eventually` on the row's standing or the recorded end, never on the row existing. Extend `discovery.lists-every-component`'s expected list with the two components. Against the Scala reference the first six pass once T035 lands; the seventh needs each SDK. *Done*: the no-state case first waits for the `reserved` row, then for the failure, then checks the row is unchanged. The first version read a projection three seconds behind as a skipped record.
- [X] T035 [P] [US3] In `SCT/ProtocolSuite.scala` add cases: a discovered view and consumer whose source is `ComponentRef(WORKFLOW, "checkout")` from a Spec stating `1.15` are hosted (`RemoteSource.Component(ComponentKind.Workflow, …)` reaches `startRemoteView`/`startRemoteConsumer`); the same from a Spec stating `1.14` is refused naming the component and both versions; a workflow source with a start position, a contract or a broker is refused as an entity's is; a keyed view's `sources` may name a workflow. In `SCT/RemoteProjectionSuite.scala`: a remote view over a workflow receives `ViewRequest.standing` with the stamp's words and a remote consumer `ConsumerRequest.standing`; an entity source's requests carry none. *Done in part*: the four `ProtocolSuite` cases were written. The `RemoteProjectionSuite` case was not, because the standing crossing to a process is asserted per language by the conformance cases, which read it back from each reference's row.

### Implementation for User Story 3

- [X] T036 [US3] In `SC/Discovery.scala`: `WorkflowSourcesSince = 15`; after sources are built, refuse any view, keyed view or consumer whose source is a `RemoteSource.Component(ComponentKind.Workflow, _)` when `minorOf(spec.protocolVersion)` is below it, in the words `contracts/protocol.md` gives; the "subscribes to … which is not declared" check already covers the kind. In `RT/DeclaredConnections.scala` `of(RemoteSource)` maps `ComponentKind.Workflow` to `DeclaredSource.Workflow` (T024 may have done this; confirm `rejectUnsupportedRemote`'s message now lists four sources).
- [X] T037 [US3] In `RT/ProjectionRuntime.scala` `startRemoteView`, `startRemoteKeyedView` and `startRemoteConsumer`: the `RemoteSource.Component(ComponentKind.Workflow, sourceId)` case over the workflow projections with `ChangeReader.workflow("")` (a remote state carries its own manifest in the packing; a Scala workflow read by a remote view cannot occur, since a service is one language). `RemoteView.apply`/`RemoteConsumer.handle` put the standing on the plain `ViewRequest`/`ConsumerRequest`. T035 passes; the first six cases of T034 pass against the Scala reference.
- [X] T038 [P] [US3] Python: `PY/src/ankka/standing.py` (`Standing` dataclass with `is_unknown`, `is_running`, `is_paused`, `is_completed`, `is_failed`, `is_terminal`, and `from_pb`); `self.standing` on `View`, `KeyedView` (and its change) and `Consumer` in `PY/src/ankka/{view,keyed_view,consumer}.py`, set by `PY/src/ankka/server.py` from the request; `declares_workflow_source(cls)` and the discovery refusal in `server.py` beside the 1.13 and 1.14 ones (`contracts/sdk-apis.md`); `standing=None` on `ViewTestKit.on_change`, `KeyedViewTestKit.change`, `ConsumerTestKit.on_message` in `PY/src/ankka/testkit/unit.py`. Unit tests: a view handed a standing reads it; an older sidecar is refused when a workflow source is declared and not otherwise; `mypy` strict. *Done*: `self.standing`, the 1.15 refusal (`reads_workflow`), and `standing=` on the unit kits; `GraphConsumer._handle` takes it too. `tests/test_workflow_sources.py` has 7 tests, and the full suite passes 378.
- [X] T039 [P] [US3] TypeScript: `TS/src/standing.ts` (`Standing`, `isUnknown`, `standingFromProto`); `this.standing` on `View`, `KeyedView` (and the keyed change) and `Consumer` in `TS/src/{view,keyedView,consumer}.ts`, bound by `TS/src/server/stateless.ts`; `olderThanWorkflowSources` in `TS/src/startFrom.ts` and the refusal in `TS/src/server/discovery.ts`; `standing?` on the unit kits in `TS/src/testkit/unit.ts`; export from `TS/src/index.ts`. Tests under `node --test` for the same three things; `tsc` clean under `erasableSyntaxOnly`. *Done*: `this.standing`, `olderThanWorkflowSources`, and `standing?` on the kits. `test/workflow-sources.test.ts` has 7 tests, and `npm test` passes 340.
- [X] T040 [P] [US3] Rust: `RS/ankka/src/standing.rs` (`Standing` with the five predicates, `From<proto::WorkflowStanding>`); `Context::standing(&self) -> Option<&Standing>` in `RS/ankka/src/context.rs`, filled by the view, keyed view and consumer exports in `RS/ankka/src/components/{view,keyed_view,consumer}.rs`; the 1.15 refusal in `RS/ankka/src/service.rs` beside the 1.13 and 1.14 ones; `.standing(Standing)` builders on the unit kits in `RS/ankka/src/testkit/unit.rs`; `pub use` from the prelude. `cargo test --workspace` with clippy clean; `wasm-objdump -j Import` on the example shows no new import. *Done*: `Context::standing()`, the 1.15 refusal, and `.standing(..)` on the kits. The step builder gained `then_fail` so a compensation can record and fail. `tests/workflow_sources.rs` has 7 tests, and clippy is clean.
- [ ] T041 [US3] Python reference: in `PY/examples/shopping_cart/conformance.py` add mode `abort`, `CheckoutRows`, `CheckoutEnds` and the three routes, marked `# docs:start workflow-view` / `workflow-consumer`; `uv run conformance` passes all seven cases and `discovery.lists-every-component`.
- [ ] T042 [US3] TypeScript reference: the same in `TS/examples/shopping-cart/{conformance,checkoutWorkflow}.ts`, marked `// docs:start …`; `npm run conformance` passes.
- [ ] T043 [US3] Rust reference: the same in `RS/examples/shopping-cart/src/conformance.rs`, marked `// docs:start …`; `./conformance.sh` passes, and `WasmHostSuite`'s end-to-end case (which needs cargo) hosts the module with the two components.
- [ ] T044 [US3] Run `sbt 'sidecar/testOnly *ConformanceSuite *ProtocolSuite *RemoteProjectionSuite *WasmHostSuite'` and the three SDK conformance runs; read each run's own count of cases. **Break it once**: drop the standing from `PY/src/ankka/server.py`'s view request decoding and `view.workflow-completed` must go red against the Python reference while the Scala reference stays green. Restore it.

**Checkpoint**: four languages declare a workflow source and read the standing; both gates refuse.

---

## Phase 6: User Story 4 — A view over a workflow is rebuilt and bounded as a view over an entity is (Priority: P2)

**Goal**: a keyed view reads a workflow beside an entity one change at a time; a view over a
workflow is rebuilt by its version; a topic and a workflow may not share a view; a deleted workflow
runs the deletion handler; a restarted view reads nothing again.

**Independent Test**: `sbt 'testkit/testOnly *WorkflowSourcesFeatures -- "*rebuilt*" "*reads no state*" "*topic and a workflow*" "*one change at a time*" "*deletion handler*"' 'runtime/testOnly *KeyedViewRulesSuite'` (quickstart, "User Story 4").

### Tests for User Story 4 (write first; they fail)

- [X] T045 [P] [US4] In `TKT/KeyedViewTestKitSuite.scala` add cases: `change(source, key, state, standing = Some(…))` over a `KeyedSource` whose source is `ChangeSource.stateOf(CheckoutWorkflow)` hands the handler a `KeyedChange` with that standing; the default is `None`. This case is the Scala row of `FEAT/languages.feature`'s outline "the component test kit hands a view a workflow change in every language": Scala has no plain-view unit kit, so its view is a keyed view of one source over the workflow, and the case is named for the scenario.
- [X] T046 [P] [US4] The US4 scenarios of `FEAT/views.feature` are already in T022's suite; confirm each fails for the right reason before T047 (the keyed-view and refusal scenarios fail on behaviour, the rebuild and restart ones may already pass from US1 — note which, so the break-it-once below is the proof for those).

### Implementation for User Story 4

- [X] T047 [US4] In `RT/ProjectionRuntime.scala` `startKeyedView`: the `ChangeSource.Workflow` case builds one daemon per source over `exactlyOnceWorkflowProjection` with `KeyedViewEventHandler(host.core, guard, handle, ChangeReader.workflow(manifest))`; `KeyedViewHost.handle` passes the standing into the `KeyedChange`. `KeyedViewRules` (T024) admits the kind; the plain view's refusal of a topic beside a workflow is the keyed rule's message, and a plain view over a topic is unchanged. T045 passes.
- [X] T048 [US4] Run the US4 scenarios; every one of `FEAT/views.feature` now passes, with "a view of several sources reads a workflow beside an entity one change at a time" proving the lock through the same two-instance step `SeveralSourcesFeatures` uses. **Break it once**: name the workflow source's projection `s"$id-v$version"` instead of through `ViewProjections.name` and `ViewProjectionsSuite` must go red; take the keyed lock shared and the one-at-a-time scenario must go red. Restore both. *Done*: the keyed lock taken shared turned the one-at-a-time scenario red. A workflow source names its projection through `ViewProjections.name` unchanged, so `ViewProjectionsSuite`'s pins cover it.

**Checkpoint**: every scenario of `FEAT/views.feature` and `FEAT/consumers.feature` passes.

---

## Phase 7: User Story 5 — The documentation (Priority: P3; the scenarios of `features/documentation/workflow-sources.feature`)

**Purpose**: the pages say what Phases 3 to 6 built. Each page stands alone: no feature numbers,
no "see above". Samples are marked regions of tested code (T033, T041–T043).

- [X] T049 [P] [US5] `DOCS/build/views.md`: a row "A workflow's state" in the sources table for all four languages (`ChangeSource.stateOf(CheckoutWorkflow)`, `source = CheckoutWorkflow`, `static readonly source = CheckoutWorkflow`, `Source::of(Checkout)`, delivery "each state the workflow records, in order, exactly once; with its standing"), and a section "Reading a workflow": what a change is, the standing and its six words (`NotStarted`, `Running`, `Paused`, `Completed`, `Failed`, `Unknown` and when each is seen), that a transition, pause, end or failure that records no state is no change and what to do about it (record the outcome in the step's state; a failover step that records the failure), reading `updateContext.standing` / `self.standing` / `this.standing` / `ctx.standing()`, a keyed view over a workflow and an entity, and that a topic and a workflow may not share a view. Scenario "the documentation names a workflow among the sources of a view and a consumer".
- [X] T050 [P] [US5] `DOCS/build/consumers.md`: the sources row and a section "Reacting to a workflow": the end-of-process consumer in four languages from the conformance regions, the standing to filter on, at least once, the deletion handler. Same scenario.
- [X] T051 [P] [US5] `DOCS/build/workflows.md`, "Domain state and lifecycle": that the standing a view or consumer is handed is this same answer as data; that a step whose outcome must be seen records it in the state because a step that records nothing is invisible to a reader; a link to the views guide's section. Scenario "the documentation of workflows says a standing can be read as it changes".
- [X] T052 [P] [US5] `DOCS/reference/akka-divergences.md`: a summary row "`@Consume.FromWorkflow` delivers the state | a workflow source delivers the state and the standing | A dashboard lists failed workflows by the engine's word, not by a status field every author must set" and a short section. Scenario "the documentation says a workflow source delivers the standing beside the state".
- [X] T053 [P] [US5] `DOCS/build/testing.md` (the `standing` argument on the Scala, Python, TypeScript and Rust kits), `DOCS/reference/sidecar-protocol.md`'s hand-written versioning prose (`1.15`: `WorkflowStanding` on the two requests, a workflow source, refused from both ends across the line), and `DOCS/reference/{python,typescript,rust}-sdk.md` where they list a view's and a consumer's members (`standing`).
- [X] T054 [US5] Run `just docs-sync` (included samples, the generated protocol table, the `ankka-views-consumers` and `ankka-workflows` skills rendered under `marketplace/` and `ankka.g8/` with every `$` escaped in the template's copy), then `just docs` and `sbt 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'`. *Done*: `docs sync` filled the samples and re-rendered the template's skills, and `docs build` reports 92 pages with no problems. The protocol page's stale `1.12` was brought to `1.15`.
- [X] T055 [US5] Read the four pages against the three scenarios of `features/documentation/workflow-sources.feature`, one at a time, and note in the pull request description the section that satisfies each (read off the pages, not matched as strings). *Done*: views.md "Reading a workflow" covers the sources scenario; workflows.md "Domain state and lifecycle" covers the standing scenario; akka-divergences.md has the summary row and "A workflow source carries the standing".

**Checkpoint**: `just docs` passes; every scenario of the documentation feature has a section.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T056 [P] Add to `.claude/rules/runtime.md` a short section "A workflow is a source" (the stamp and why it is on the `state` record alone; `WorkflowChanges.read` as the one reader; `ChangeReader` as the one place a record becomes a change) and to `.claude/rules/messaging.md` the workflow source beside the entity sources; add to either's *Traps* whatever the *verify first* cases actually cost (one entry each, only for what bit). Add the 1.15 gate to `.claude/rules/sidecar.md`'s socket-gate trap as the second feature gated both ways. *Done*: messaging.md gained a workflow source section and two traps (Jackson's null `Option`, and a stalled projection that looks like a skipped record). sidecar.md gained the two-ended gate.
- [ ] T057 [P] In `RT/ObservabilityDocuments.scala` and the local console (`just console` against a service with `WorkflowKit`'s components), confirm the connection draws with the legend's words and nothing else assumed a source is an entity; in `controlplane/…/TopicChecks.scala` confirm only `topic-subscription` is read.
- [ ] T058 Run `sbt scalafmtAll scalafmtSbt` and `sbt compile` warning-free (`-Wunused` is on).
- [ ] T059 Run `caffeinate -i sbt -Dankka.cluster.tests=off test`; every suite green; read the conformance run's own count of cases and the feature suites' test counts.
- [ ] T060 [P] Run each SDK's full line: `cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance`; `cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance`; `cd sdks/rust && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh`.
- [ ] T061 [P] Run the template suites: `sbt -Dankka.template.tests=python 'cli/testOnly *PythonTemplateSuite'`, the same for `typescript` and `rust`, and `sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'`. Nothing in a template declares a workflow source, so they must pass unchanged.
- [ ] T062 Run `just features`: 0 findings; the report's spec and scenario counts are read, not assumed.
- [ ] T063 Run `python3 .github/ci-coverage.py` with every new file present: each claimed by a filter, no pattern matching nothing. If any suite added a `-D` switch a forked test reads, confirm `build.sbt` forwards it in `Test / javaOptions`.
- [ ] T064 Compare `CheckoutWorkflowSuite`'s wall time with T001's note as a regression check on the engine's fold and stamp (not SC-002, which T021 measures); say so in the pull request description with both numbers if it moved beyond noise.
- [ ] T065 Walk `quickstart.md` top to bottom, including each "break it once", and tick each off in the pull request description.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: after Setup. T004 before T005 (the fixture is generated from the old record). T009 before T010; T010 before T011; T011 before T012; T013 after T012. T014 before T015–T019. Blocks every story.
- **US1 (Phase 3)**: after Foundational.
- **US2 (Phase 4)**: after US1's T024 (the source case) and T025 (the reader wiring pattern); its tests (T028, T029) can be written beside US1's.
- **US3 (Phase 5)**: after US1 and US2 (the Scala reference's view and consumer are the first things the conformance cases run against). T036 and T037 after T035; T038–T040 after T037; T041–T043 after their SDK's task.
- **US4 (Phase 6)**: after US1's T024 and T025; independent of US2 and US3.
- **Documentation (Phase 7)**: after the story each page documents; T054 after T049–T053.
- **Polish (Phase 8)**: after everything.

### Within a story

Tests first, and seen to fail for the right reason. Pure suites before suites that need Postgres.
`sdk` before `runtime` before `testkit` and `sidecar`; the SDKs after the sidecar can host what
they declare.

### What a task waits for, where it is not the one before it

- T008 waits for T007; T012 waits for T010 and T011; T019 waits for T014 and T015.
- T025 waits for T024 and T021; T026 waits for T024; T027 waits for T022, T025, T026.
- T030 waits for T024 and T028; T031 waits for T013 and T029; T032 waits for T030, T031.
- T034 waits for T033; T037 waits for T036 and T035; T041 waits for T038, T042 for T039, T043 for T040.
- T047 waits for T024, T045; T048 waits for T047 and T022.

## Parallel opportunities

- Phase 2: T009 beside T004–T008; T016, T017, T018 once T014 is done.
- US1 tests: T020–T023 together.
- US2 tests: T028, T029 together, beside US1's implementation.
- US3: T033, T034, T035 together; T038, T039, T040 together; then T041, T042, T043.
- US4: T045, T046 together.
- Documentation: T049–T053 together (different pages); T054 after all five.
- Polish: T056, T057, T060, T061 together.

```bash
# US1, the tests, at once:
Task: "Write TKT/views/WorkflowKit.scala …"               # T020
Task: "Write TKT/views/WorkflowSourceSuite.scala …"       # T021
Task: "Write TKT/views/WorkflowSourcesFeatures.scala …"   # T022
Task: "TopologyJsonSuite and KeyedViewRulesSuite cases …" # T023
```

## Implementation Strategy

### Built in slices, released once

1. Phases 1–2: the stamp, the reader and the wire. Safe to carry alone: a stamped journal reads on
   both releases and nothing else behaves differently.
2. Phases 3–4: the Scala source for a plain view and a consumer. The MVP to review.
3. Phase 5: the languages.
4. Phase 6: keyed views, rules, rebuild.
5. Phases 7–8: the documentation and the full runs.

**No release carries Phase 3 or 4 without Phase 5.** A release that taught Scala services a source
a process service could not declare would make the documentation's sources tables true of one
language. `main` may hold a slice alone for as long as no tag is cut from it.

### What to watch

- **The fixture comes first.** T004's `workflow-record.txt` must be generated from the record
  *before* T005 adds the field, or it pins the new form and proves nothing about the old one.
- **The stamp is of the batch, not of the state.** T008 and T027's break-it-once are what hold the
  engine to folding the whole effect before stamping.
- **A check that cannot fail.** Each story's checkpoint names one line to remove and the scenario
  that must go red. Do it.
- **A filter that matched nothing reports green.** Read each run's own count of what it ran: the
  feature suites' test counts, the conformance run's case list, the Rust script's first line.
- **Fixed ports, forked properties.** Every HTTP suite binds `127.0.0.1:0`; a new `-D` switch
  needs forwarding in `Test / javaOptions`.
- **Long runs sleep.** `caffeinate -i` for T059.
