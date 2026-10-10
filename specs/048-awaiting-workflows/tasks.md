# Tasks: Awaiting Workflows — A Caller Is Answered When the Workflow Ends

**Input**: Design documents from `/specs/048-awaiting-workflows/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature. The scenarios are already written in
`features/awaiting-workflows/` (21 scenarios across five files, two of them outlines of three rows, so 25 tests when run)
and `features/documentation/awaiting-workflows.feature` (3); research R10 says where each runs.
Where a task says "bind", it means a `Given`/`When`/`Then` step in the named `GherkinSuite` so the
scenario runs; where it says "case", a `test(...)` named for the scenario or the rule it holds. A
bound scenario must be red before the implementation task that makes it green: run it once and
read the failure.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a command starts a workflow and its caller is answered with the result), US2
  (a caller waits for a workflow it did not start), US3 (the wait survives the instance moving),
  US4 (a workflow step awaits another workflow, and every language awaits), US5 (a long wait is
  served over HTTP as a stream), US6 (the documentation)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK`/`SDKT` = `modules/sdk/src/{main,test}/scala/…/sdk`;
`RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`; `HTTP`/`HTTPT` =
`modules/http/src/{main,test}/scala/…/http`; `GRPC`/`GRPCT` = `modules/grpc/src/{main,test}/scala/…/grpc`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`;
`PY` = `sdks/python`; `TS` = `sdks/typescript`; `RS` = `sdks/rust`; `API` =
`controlplane-api/src/main/scala/…/controlplane/api`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`;
`FEAT` = `features/awaiting-workflows`. "R*n*" is a section of `research.md`; a contract is named by
its file under `contracts/`.

Work on branch `048-awaiting-workflows` in the worktree `.claude/worktrees/048-awaiting-workflows`,
or in an `048-awaiting-workflows-impl` worktree off it, as the project's habit is. Every `sbt`
command takes `-Dankka.cluster.tests=off`; nothing here needs k3s. Long suites run under
`caffeinate -i`. sbt's project ids are `core`, `sdk`, `runtime`, `http`, `grpc`, `testkit`,
`sidecar`, `controlPlaneApi`, `shoppingCart`. munit's `--` filter needs a leading `*`. The protocol
minor is written as 1.15 and renumbered on rebase if 046 or 047 merges first (R8).

---

## Phase 1: Setup — the code, the fields, the names

**Purpose**: the one error code and the two words every later task spells the same way.

- [X] T001 Add `WorkflowFailed` as the ninth case of `enum ErrorCode` and `details: Map[String, String] = Map.empty` as the third field of `CommandError` in `CORE/CommandError.scala`; `retryable` stays `Unavailable | Timeout`. Add `object WorkflowEnd` with `final case class Failure(step: Option[String], reason: String, deleted: Boolean)` and `def failure(error: CommandError): Option[Failure]` reading `details("step")`, `details("reason")`, `details("deleted")` (data-model "CommandError and ErrorCode"). Run `sbt core/compile`: it fails at every exhaustive `match` over `ErrorCode`, which is the list for T002
- [X] T002 Give each exhaustive match its case: `HttpProblem.from` in `HTTP/HttpEndpoint.scala` maps `WorkflowFailed` to 424 and the problem body carries `details` (find where `HttpProblem` is written as JSON in `HTTP/HttpServer.scala` and add the field, absent when empty); `GrpcStatus` in `GRPC/GrpcStatus.scala` adds `ErrorCode.WorkflowFailed -> Status.Code.ABORTED`; `Translate.toCode` in `SC/Translate.scala` adds `WorkflowFailed -> pb.ErrorCode.WORKFLOW_FAILED` (compiles after T005). `Observability.outcomeOf` in `RT/Observability.scala` keeps its wildcard with a comment that a `WorkflowFailed` is `Refused` (R3)
- [X] T003 [P] Cases: in `HTTPT` (the suite that holds `HttpProblem.from`, or a new `HttpProblemSuite.scala`) `CommandError("x", WorkflowFailed, Map("step" -> "margin", "reason" -> "r"))` is 424 and its body has both details; in `GRPCT/GrpcStatusSuite.scala` the existing `codes == ErrorCode.values` goes red before T002 and green after, and a round trip of `WorkflowFailed` through `fromCode` is `ABORTED`; in `CORET` a `WorkflowEnd.failure` case for a failed, a deleted and an unrelated error
- [X] T004 [P] Add `private[ankka] val AwaitEnd: MethodName = MethodName("ankka:await-end")` beside `Method` in `SDK/WorkflowLifecycle.scala`, with a comment naming it a reserved method the engine answers; a case in `SDKT` that `Workflow.Companion.descriptor` refuses a user handler named `ankka:await-end` as it refuses `ankka:lifecycle`
- [X] T005 [P] Protocol: in `PROTO/payload.proto` add `WORKFLOW_FAILED = 8` to `ErrorCode` and `map<string, string> details = 3;` to `Error`; in `PROTO/client.proto` add `rpc AwaitEnd (AwaitEndRequest) returns (InvokeReply);`, `rpc AwaitEndStream (AwaitEndRequest) returns (stream StreamToken);` and `message AwaitEndRequest { string component_id = 1; string entity_id = 2; int64 timeout_millis = 3; Metadata metadata = 4; }`, with the comments in contracts/protocol.md; add `Empty heartbeat = 5;` and `Payload ended = 6;` to `StreamToken`'s oneof. Copy to the three SDKs with `PY/scripts/proto.py`, `npm run proto` in `TS`, `RS/scripts/proto.sh`; `diff -r` all three against `PROTO` (quickstart "The protocol pins")
- [X] T006 [P] Bump the version: `WireProtocol.Version = "1.15"` in `RT/remote/Conversation.scala`; `ProtocolVersion(1, 15)` and a scaladoc line for 1.15 in `API/Compatibility.scala`; `PROTOCOL_VERSION = "1.15"` in `PY/src/ankka/service.py`, `TS/src/spec.ts`, `RS/ankka/src/service.rs`; the "## Version" line in `protocol/README.md` and its SDK copies. Move the pins: `SCT/ProtocolSuite.scala` (`"1.14"` → `"1.15"`), `controlplane-api/src/test/…/CompatibilitySuite.scala` (1.15 supported, 1.16 not), `TS/test/contracts.test.ts:124`, `RS/ankka/tests/contracts_discovery.rs:118`, `PY/tests/test_topic_sources.py:175`

---

## Phase 2: Foundational — the engine answers a wait, the transport asks again, the client offers it

**Purpose**: the await itself, Scala to Scala, with the fixture every feature file drives.

**⚠️ CRITICAL**: no story phase begins until T007–T022 are green.

### The replies and the standing (`runtime`)

- [X] T007 Add `final case class NotYet(heldMillis: Long) extends Reply` and `final case class WorkflowFailed(step: Option[String], reason: String, deleted: Boolean) extends Reply` to `EntityProtocol` in `RT/wire.scala`, both `AnkkaSerializable`; give `WorkflowFailed` a `def toCommandError(message: String): CommandError` building `details` as data-model says (contracts/runtime-wire.md). `Rejected` and `Succeeded` untouched
- [X] T008 Case in `RTT` (the suite that round-trips `AnkkaSerializable` replies, or a new `ReplySerializationSuite.scala` with `pekko.actor.provider = local`): `NotYet` and `WorkflowFailed` round-trip through the system's serialization under `jackson-cbor`; a `WorkflowSnapshot` encoded without `failedStep` and `deleted` (an old shape, written as a map by hand) decodes with `""` and `false` (R3)
- [X] T009 In `RT/WorkflowHost.scala`: `Event.Failed(message: String, step: Option[String])`; `Run` gains `failedStep: Option[String]` and `deleted: Boolean`; `applyEvent` sets `failedStep` on `Failed`, sets `deleted = true` on `Deleted` (the empty run with the flag) and clears it on `StateUpdated` and `TransitionedTo`; the event adapter writes the step into `WorkflowRecord.step` on `KindFailed` and reads an empty one as `None`; `WorkflowSnapshot` gains `failedStep: String` and `deleted: Boolean`, written and read by the snapshot adapter. Every `Event.Failed(...)` construction in `RT/WorkflowEngine.scala` passes its step: the pending step at `:355`, `:416`, `:430`; the named step at `:256` and `:404` (R3's table)
- [X] T010 Case in `RTT` (the workflow host's existing suite, if one reads records; else `WorkflowRecordSuite.scala`): a `KindFailed` record with an empty `step` reads as `Failed(message, None)`; one with a step reads it; `applyEvent(Deleted)` on a `Running` run yields `deleted = true`, and a following `StateUpdated` clears it

### The engine holds waiters (`runtime`)

- [X] T011 In `RT/WorkflowEngine.scala`: `private var waiters: Vector[Waiter]` with `Waiter(replyTo, key, askedAtNanos, metadata)` (data-model "Waiter"); `onInvoke` routes `WorkflowLifecycle.AwaitEnd` to a new `onAwaitEnd(invoke)` before the handler lookup: parse the payload as remaining millis (a bad payload is `Rejected(BadRequest)`); if `state.isTerminal || state.deleted` answer at once (`Succeeded(descriptor.stateSerializer.toBytes(state.value), stateMeta)` for `Completed`, where `stateMeta` is two `MetaEntry`s, `ankka-manifest` and `content-type`, naming the state as its serializer does — for a process's workflow, as the remote host's held payload does — because the sidecar has no request payload to take them from; `WorkflowFailed(state.failedStep, state.failure.getOrElse(""), deleted = false)` for `Failed`; `WorkflowFailed(None, "the workflow was deleted", deleted = true)` when `deleted`), counting `observability.handled(metadata, componentName, AwaitEnd.value, Ok, 0, streaming = false)`; else append a waiter and `timers.startSingleTimer(WaiterKey(key), WaiterHeld(key), min(remaining, askTimeout / 2))` where the hold bound `askTimeout / 2` is read once by `WorkflowHost.behavior` from `ankka.ask-timeout` and passed to the engine's constructor, as `settings` is. Add `private[ankka] case class WaiterHeld(key: Long) extends Command` and `WaiterKey` in `RT/wire.scala`; `onCommand` handles `WaiterHeld` by answering that waiter `NotYet(held)` and removing it (R4)
- [X] T012 In `RT/WorkflowEngine.scala` add `private def answerWaiters(run: Run[S]): Unit` that answers every waiter from the run as T011 does, cancels its timer, counts `handled(…, Ok, sinceAsked)` per waiter and clears the vector; call it in the `thenRun` after every persist of `Ended` or `Failed` (`onStepSucceeded` both branches, `onStepFailure`'s final branch, `onWorkflowTimedOut`, the pause-timeout fail, the missing-step fail; the last two also gain `cancelLifecycleTimers()`), and after the `Deleted` persist in `onInvoke`. In `RT/WorkflowHost.scala` add `.receiveSignal { case (_, PostStop) => engine.onStop() }` where `onStop` answers every waiter `NotYet` and logs nothing (R4, `runtime.md` "A Pekko stash is dropped")
- [X] T013 Cases in `RTT/WorkflowEngineSuite.scala` (or the engine's existing behaviour suite, with `EventSourcedBehaviorTestKit` or the actor test kit): an `AwaitEnd` on a completed run answers `Succeeded` with the state bytes at once; on a failed run `WorkflowFailed(Some(step), reason, false)`; on a deleted run `WorkflowFailed(None, _, true)`; on a running run nothing until `Ended` is persisted, then `Succeeded`; two waiters both answered; a hold of 100ms answers `NotYet(≈100)` and leaves the run running; stopping the actor with a waiter answers `NotYet`; an `AwaitEnd` never persists (journal length unchanged) and never increments the lifecycle query's count

### The transport asks again (`sdk`, `runtime`, `testkit`)

- [X] T014 Add `def awaitEnd(componentId: ComponentId, entityId: EntityId, timeout: FiniteDuration, metadata: Metadata): Future[(Array[Byte], Metadata)]` (the shape `askWithMetadata` has, so the reply's `ankka-manifest` and `content-type` reach the sidecar) to `CallTransport` in `SDK/ComponentClient.scala` (contracts/runtime-wire.md "The transport's contract"), refusing `timeout <= 0` with `CommandError(BadRequest)`; implement it in `TK/TestTransport.scala` from the stubs (a stubbed state answered at once, a stubbed `CommandError(WorkflowFailed)` thrown, nothing stubbed `NotFound`) and in the two test-only transports `modules/testkit/src/test/…/autonomous/EntityRouter.scala` and `modules/sdk/src/test/…/QueryRoutingSuite.scala` (delegate or `NotFound`)
- [X] T015 Implement `ShardingTransport.awaitEnd` in `RT/ShardingTransport.scala`: `Call` captured once on the asking thread; `Recorder.reserve` a `Client` span at start; loop `send(Invoke(AwaitEnd, remainingMillis.toString.getBytes, carried, replyTo))` under `Timeout(askTimeout)`; `Succeeded` → the bytes with the reply's metadata, `record(Ok)`; `WorkflowFailed` → `Failure(reply.toCommandError(message))`, `record(Ok)`; `NotYet` or `TimeoutException` → loop while `deadline.hasTimeLeft`; at the deadline `observability.unanswered(call.origin, componentId, AwaitEnd, TimedOut)`, `record(TimedOut)` and `CommandError(s"… had not ended within $timeout", Timeout)`; a `Rejected(NotFound)` on this method whose message is the previous release's unknown-handler text (`WorkflowEngine.scala:108-119` today; copy it verbatim into a constant beside the loop, since the match is on what an *older* instance says) → `CommandError(s"instance hosting $componentId '$entityId' is from before awaiting; both halves must be at this release", Unavailable)`; any other `Rejected` → `toCommandError`. The message of a `WorkflowFailed` error is `s"workflow $componentId '$entityId' failed: $reason"` plus `" at step '$step'"` when there is one (R2, R5)
- [X] T016 Cases in `RTT/ShardingTransportSuite.scala` (beside `QueryResendSuite`, with a stand-in entity behaviour answering a scripted sequence): `NotYet, NotYet, Succeeded` ends with the bytes and `handled`/`unanswered` counts of one and zero on the caller's side; no reply at all for one attempt then `Succeeded` ends well within two ask timeouts; a 300ms timeout against a silent entity fails `Timeout` and counts one `unanswered(TimedOut)`, not one per attempt; `Rejected(NotFound, <the engine's unknown-handler text, asserted verbatim against the engine's own constant so a reworded message is red>)` fails `Unavailable` naming "before awaiting"; the recorder holds one `Client` span, recorded when the loop ends, with `TimedOut` in the timeout case (`Recorder.cursor` reads it)

### The client offers it (`sdk`)

- [X] T017 In `SDK/CommandHandle.scala` add `private[ankka] val stateSerializer: Option[Serializer[?]] = None` to `CommandHandle` and `NoArgHandle`; in `SDK/Workflow.scala` `Companion.command` (both overloads) passes `Some(stateSerializer)`. In `SDK/ComponentClient.scala`: `WorkflowCalls.awaitEnd[W <: Workflow[S], S](companion, timeout): S` and `awaitEndAsync` over `transport.awaitEnd`, dropping the reply's metadata and decoding the bytes with `companion.stateSerializer`; `WorkflowCalls.withMetadata`; `WorkflowCalls.call` returns new `WorkflowInvocation[W, I, O]` and `WorkflowNoArgInvocation[W, O]` with `invoke`, `invokeAsync`, `withMetadata` as `Invocation` has, and `thenAwaitEnd[S](timeout)(using W <:< Workflow[S]): AwaitingInvocation[I, S]` whose `invoke(input)` sends the command (a refusal thrown at once, the reply discarded) then awaits with the same deadline measured from the send (contracts/scala-sdk.md). `lifecycle(companion)` is unchanged
- [X] T018 Cases in `SDKT/ComponentClientSuite.scala` (or a new `WorkflowCallsSuite.scala` over a scripted `CallTransport`): `awaitEnd` decodes the bytes with the companion's serializer; a `WorkflowFailed` error surfaces with `WorkflowEnd.failure` populated; `thenAwaitEnd` sends the command first and awaits second, in order, with one deadline; a refused command throws before any await; `awaitEnd(_, 0.seconds)` is `BadRequest`; the transport receives the metadata `withMetadata` set; an entity's `Invocation` has no `thenAwaitEnd` (a compile-time check via `compileErrors`)

### The fixture and the steps (`testkit` tests)

- [X] T019 Write `TKT/awaiting/QuoteWorkflow.scala`: state `Quote(id, steps: Vector[String], instance: Option[String], total: Option[Int])`; steps `rates`, `margin`, `offer`, each appending its name and the instance's cluster address to the state and reading `StepScript` (an `object StepScript` keyed by workflow id: per-step duration, which step fails, how many times, whether `offer` pauses) before sleeping or throwing; `start` command transitions to `rates`; `pause-after-rates` mode makes `rates` `thenPause()`; `cancel` command deletes. Default settings: `RecoverStrategy.maxRetries(1)` on `margin` so "fails after its retries" is two throws. Also `OnboardingWorkflow` (`verify` awaits `KycWorkflow` within the step's own timeout, chooses `approved` or `declined` from its state) and `KycWorkflow` (`documents`, `decision`) in `TKT/awaiting/CompositionWorkflows.scala`
- [X] T020 Write `TKT/awaiting/AwaitSteps.scala`: `abstract class AwaitSteps(features: String) extends GherkinSuite(features) with LogCapturing` starting `AnkkaTestKit` with the three workflows and `QuoteEndpoint` (T022), with the shared steps every feature file uses: `Given a service "pricing" with a workflow "quote" of the steps …`, `When a caller sends the command "start" to the workflow "{id}" of "quote" and waits for its end within "{duration}"`, `When a caller waits for the end of "{id}" within "{duration}"`, `Then the caller is answered with the state "{id}" ended with`, `Then the caller is told the wait timed out`, `Then the caller is answered with a failure that names the step "{step}" and the reason`, and the script-setting `Given`s (`the step "margin" of "quote" fails after its retries`, `is running a step that takes "20 seconds"`, `is paused after its step "rates"`, `has recorded nothing`, `has ended as completed`). Every `Then` asserts on a value the `When` produced, never on a stale read (`CLAUDE.md`, "An `eventually` must wait for the thing it asserts")
- [X] T021 Write the five final classes in `TKT/awaiting/`: `AwaitingFeatures extends AwaitSteps("../../features/awaiting-workflows/awaiting.feature")`, `CompositionFeatures`, `InstancesFeatures`, `ServingFeatures` (its config overrides `pekko.http.server.idle-timeout = 3s` through `AnkkaTestKit.start(settings = …)`), `LanguagesFeatures`. Run `sbt 'testkit/testOnly *awaiting.*'`: every scenario is red with a step not yet bound or an assertion, and the count of tests equals 25 (21 scenarios; each outline contributes three)
- [X] T022 Write `TKT/awaiting/QuoteEndpoint.scala` with `post("/quotes/{id}")` (start and `thenAwaitEnd(30.seconds)`, answering the `Quote`), `get("/quotes/{id}/wait")` (`awaitEnd(QuoteWorkflow, 10.seconds)` as a whole answer), and placeholders for T050's SSE route, each region marked `// docs:start …` / `// docs:end …` for the pages (R11); register it in `AwaitSteps`

**Checkpoint**: `sbt core/test sdk/test runtime/test http/test grpc/test` green; `sbt 'testkit/testOnly *awaiting.*'` compiles with 21 red scenarios.

---

## Phase 3: User Story 1 — a command starts a workflow and its caller is answered with the result (Priority: P1) 🎯 MVP

**Goal**: `call(start).thenAwaitEnd(timeout).invoke(input)` answers the final state after the last step, a failed workflow's failure with the step and the reason, a refusal at once, and no lifecycle query is asked while waiting.

**Independent Test**: `sbt 'testkit/testOnly *AwaitingFeatures -- "*answered with the state*" "*fails is answered*" "*refused is answered*" "*not by asking*"'` green; `answerWaiters` made a no-op turns the first two red.

- [X] T023 [US1] Bind in `TKT/awaiting/AwaitSteps.scala`: "a caller sends a command and is answered with the state the workflow ended with" (`QuoteWorkflow.start` through `thenAwaitEnd`; assert `steps == Vector("rates","margin","offer")` and that the answer arrived after `offer`'s recorded time); "a caller that waits for a workflow that fails is answered with the failure, not a refusal" (`WorkflowEnd.failure(error)` is `Some(Failure(Some("margin"), reason, false))` and `error.code == WorkflowFailed`, not `BadRequest`); "a command that is refused is answered at once and no wait begins" (`start` with a bad request returns `BadRequest` within one second; the recorder shows no `ankka:await-end` call); "a caller is answered when the workflow ends and not by asking again and again" (a 5-second `offer`; the answer within one second of `Ended`'s recorded time; the recorder's count of `ankka:lifecycle` calls on `quote` is zero)
- [X] T024 [US1] Make them green: whatever T011–T017 left (the usual find is the engine's `handled` metadata or the `thenAwaitEnd` deadline); run the four scenarios, then break `answerWaiters` once and confirm two go red (quickstart row 1)

**Checkpoint**: US1 green; the MVP is a Scala endpoint answering a quote in one call.

---

## Phase 4: User Story 2 — a caller waits for a workflow it did not start (Priority: P1)

**Goal**: awaiting later, at once for an ended workflow, timing out and waiting again, paused and never-recorded workflows, deletion, two callers, and one call in the topology.

**Independent Test**: `sbt 'testkit/testOnly *AwaitingFeatures'` green, all twelve scenarios.

- [X] T025 [US2] Bind in `TKT/awaiting/AwaitSteps.scala`: "a workflow that has ended answers a wait at once" (answered within 500ms), "a wait not answered in time is told it timed out and the workflow runs on" (`Timeout`; then the lifecycle reaches `Completed` and the state has every step), "a caller whose wait timed out waits again and is answered", "a paused workflow has not ended" (`Timeout`; lifecycle still `Paused`), "a workflow that has recorded nothing is waited for until the wait times out", "two callers waiting for one workflow are each answered" (two virtual threads, both states equal); and one more case in `AwaitingFeatures`, not a scenario, that an await made from a timed action answers (a `QuoteTimedAction` in `TKT/awaiting/QuoteWorkflow.scala` that awaits `q1` and records the state it was answered), since FR-008's consumer, timed action and tool share the one client and one of them proves the path
- [X] T026 [US2] Bind "a workflow deleted while it is waited for answers a failure that says so": a caller awaits `q7`, the test sends `cancel` (which deletes), the caller's error is `WorkflowFailed` with `details("deleted") == "true"` within one second; and a second await after the deletion is answered the same at once
- [X] T027 [US2] Bind "a wait is one call in the topology": after one await made through `QuoteEndpoint`'s `GET /quotes/{id}/wait` route (so the origin is the endpoint's handler) is answered, read the topology through the kit's observability endpoint (as `TKT/topology/TopologySteps.scala` reads it) and assert exactly one observed call from `QuoteEndpoint` to `quote` with method `ankka:await-end`, `handled.ok == 1`, `unanswered.timedOut == 0`; then a timed-out wait adds `unanswered.timedOut == 1` and no `handled`
- [X] T028 [US2] Make them green; break the deadline check so a hold is counted per `NotYet` and confirm T027 goes red (quickstart "The topology scenario")

**Checkpoint**: `awaiting.feature` whole is green.

---

## Phase 5: User Story 3 — the wait survives the workflow's instance moving (Priority: P2)

**Goal**: a caller on one node awaits a workflow on another; that node stops mid-step; the workflow recovers and ends elsewhere; the caller is answered; no log line says a caller was lost.

**Independent Test**: `sbt 'testkit/testOnly *InstancesFeatures'` green; removing the re-ask on `TimeoutException` turns both red.

- [X] T029 [US3] Bind in `TKT/awaiting/AwaitSteps.scala` with `kit.startPeer(...)`: `Given a service "pricing" written in "Scala" running as 3 instances` starts two peers (three nodes); start `w1` with a 20-second `margin`; read which node hosts it from the state's `instance` after `rates` (the step records the cluster address); the caller awaits from a node that is not the host; `When the instance running "w1" stops during the step "margin"` stops that node (`Peer.stop()`, or `kit.restartService()` when it is the main node, with the caller then on a peer); `Then "w1" goes on on another instance to its end` asserts `margin` and `offer` recorded a different address; `And the caller is answered with the state "w1" ended with`
- [X] T030 [US3] Bind "no caller is lost when an instance stops": the same drive, then assert the captured log (`LogCapturing`'s held log, read through the suite's appender) has no line matching `waiting caller.*lost|lost.*waiter`, and that the answer arrived in under one ask timeout plus the step's remaining time (the `PostStop` `NotYet` worked)
- [X] T031 [US3] Make them green; this is where `ShardingTransport.awaitEnd`'s `TimeoutException` branch and the engine's `PostStop` are proved. Record the run's wall time in a comment; it should be under a minute

**Checkpoint**: `instances.feature` green.

---

## Phase 6: User Story 4 — a workflow step awaits another workflow, in every language (Priority: P2)

**Goal**: composition in Scala; the await through the sidecar for Python and TypeScript processes and Rust modules; an older runtime refusing a wait at the call; conformance.

**Independent Test**: `sbt 'testkit/testOnly *CompositionFeatures *LanguagesFeatures'` green (Python and TypeScript rows `ranElsewhere`); `uv run pytest -q examples/shopping_cart/test_await.py tests/test_await_old_runtime.py` and `npm run test:slow -- examples/shopping-cart/await.test.ts` green; `wf.start-and-await` and `wf.await-failed` green for all four references.

### Composition (Scala)

- [X] T032 [US4] Bind in `TKT/awaiting/AwaitSteps.scala` for `composition.feature`: "a step goes on with the state the other workflow ended with" (`a1` runs `verify`, which starts `kyc-a1` and awaits it within the step's timeout; the onboarding's next step is the one its handler chose from the kyc state; assert through the onboarding's final state) and "a step whose timeout passes before the other workflow ends fails as timed out" (`verify` at 2s, `decision` at 20s; the onboarding's lifecycle says `Failed` with a failure naming `verify` and "timed out"; `kyc-a2` then reaches `Completed`)
- [X] T033 [US4] Make them green: a step's `ComponentClient` is `StepScope.stepsOnly`, which must offer `forWorkflow(...).awaitEnd` (check `SDK/StepScope.scala` or wherever the step's client is narrowed); the step's own timeout cuts the await by failing the step, and the child runs on

### The sidecar (`sidecar`)

- [X] T034 [US4] In `SC/ClientLogic.scala` add `awaitEnd(request: AwaitEndRequest): Future[InvokeReply]`: parse ids and `timeout_millis` (`<= 0` → `BAD_REQUEST`), `transport.awaitEnd(componentId, entityId, timeout, metadata)` with the request's metadata carried as `invoke` carries it, wrap the bytes as `InvokeReply.Reply` under the content type and manifest read from the reply's metadata entries `ankka-manifest` and `content-type`, which the engine stamps (T011) and `awaitEnd` returns (T014), `CommandError` → `InvokeReply.Error(error(e))` with `details` copied (T035). In `SC/ClientService.scala` add `awaitEnd` delegating to it (contracts/protocol.md)
- [X] T035 [US4] In `SC/Translate.scala`: `error(e)` writes `details`; `fromCode` maps `WORKFLOW_FAILED` → `WorkflowFailed`; a `pb.Error` with details read back into `CommandError.details`. Cases in `SCT/TranslateSuite.scala` (or the suite that holds `toCode`/`fromCode`): both directions for the ninth code and a details round trip
- [X] T036 [US4] Cases in `SCT` (the client service's suite, driven over the loopback `CallbackServer`): `AwaitEnd` on a completed in-process workflow answers its state; on a failed one `error.code == WORKFLOW_FAILED` with `details["step"]`; `timeout_millis = 0` → `BAD_REQUEST`; a 300ms timeout on a running one → `TIMEOUT`

### Python (`sdks/python`)

- [X] T037 [P] [US4] In `PY/src/ankka/effects/common.py`: `ErrorCode.WORKFLOW_FAILED`, its HTTP status 424, `Error.details: Mapping[str, str] = field(default_factory=dict)` written and read in `to_pb`/`from_pb`. In `PY/src/ankka/client.py`: `Calls.await_end(timeout, *, reply, reply_codec)` calling `self._stub.AwaitEnd(...)`, decoding the reply as `Invocation._read` does, raising `CommandError` on `error`; `Invocation.then_await_end(timeout) -> AwaitingInvocation` whose `invoke(input, *, reply, codec, reply_codec)` invokes then awaits; a `grpc.StatusCode.UNIMPLEMENTED` from `AwaitEnd` raises `CommandError(Error("the runtime beside this process does not offer waiting for a workflow's end, which needs protocol 1.15 (this SDK speaks 1.15)", UNAVAILABLE))` in the words `client.py:376-385` uses (contracts/polyglot-sdks.md). Regenerate stubs if the build does not (`uv run python scripts/proto.py`)
- [X] T038 [P] [US4] Tests: `PY/tests/test_await_old_runtime.py`, a fake `Client` servicer answering `UNIMPLEMENTED` for `AwaitEnd` (as the recurring-timer unit test fakes `ScheduleRecurring`), asserting the error names 1.15; `PY/examples/shopping_cart/test_await.py` through `ankka.testkit.integration.AnkkaTestKit` against `ANKKA_SIDECAR_IMAGE` built from this branch: `kit.client.for_workflow("checkout", id).call("start").then_await_end(30.0).invoke("ok", reply=CheckoutState)` answers the final state (the "every language", Python row), and `"fail"` raises `CommandError` whose `error.code is WORKFLOW_FAILED` and `error.details["step"]` names the step. The reference's checkout workflow and its state type are in `PY/examples/shopping_cart/checkout_workflow.py`
- [X] T039 [P] [US4] `uv run mypy` clean and `uv run pytest -q` green in `PY`; add `await_end` and `then_await_end` to the module's `__all__`/docstrings where `for_workflow` is documented in `PY/src/ankka/client.py`

### TypeScript (`sdks/typescript`)

- [X] T040 [P] [US4] In `TS/src/kinds.ts` `ErrorCode.WorkflowFailed` with status 424 and `errorCodeFromProto` for `WORKFLOW_FAILED`; in `TS/src/effects/common.ts` `CommandError.details: Record<string, string>` read from `Error.details`; in `TS/src/client.ts` `Calls.awaitEnd<S>(timeoutMillis, reply?)`, `Invocation.thenAwaitEnd<S>(timeoutMillis, reply?)` returning `AwaitingInvocation<I, S>` with `invoke(input)`, the typed `of(...)` form decoding with the workflow's state schema, `RangeError` for `timeoutMillis <= 0`, and `UNIMPLEMENTED` → `tooOld("waiting for a workflow's end", "1.15")` (`client.ts:386-397`). No `enum`, no parameter properties (`sdk-typescript.md`)
- [X] T041 [P] [US4] Tests: a unit test beside the recurring-timer `tooOld` test with a fake server answering `UNIMPLEMENTED`; `TS/examples/shopping-cart/await.test.ts` under `test:slow` through the integration `AnkkaTestKit`: `client.of(CheckoutWorkflow, id).call(CheckoutWorkflow.handlers.start).thenAwaitEnd(30_000).invoke("ok")` answers the final state (the TypeScript row), `"fail"` rejects with `CommandError` whose `code === ErrorCode.WorkflowFailed` and `details.step` is set. `npm run typecheck && npm test && npm run test:slow` green

### Rust (`sdks/rust`)

- [X] T042 [P] [US4] In `RS/ankka/src/effects/common.rs`: `ErrorCode::WorkflowFailed` (424, proto both ways) and `CommandError.details: BTreeMap<String, String>` from `Error.details`. In `RS/ankka/src/abi/imports.rs`: `Import::AwaitEnd` in its own `#[link(wasm_import_module = "ankka1")] extern "C" { fn await_end(ptr: u32, len: u32) -> u64; }` block and its own `call_await` function, never in the shared `call` match (`wasm.md`). In `RS/ankka/src/client.rs`: `Client::await_end::<W: Workflow>(id, timeout) -> Result<W::State, CommandError>` and `invoke_then_await_end`. In `SC/wasm/HostImports.scala` `awaitEnd` blocking on `ClientLogic.awaitEnd` with the call site's metadata; `ModuleLoader.Imports` += `"await_end"`; `WasmHostSuite` holds the two lists equal (it already does; it goes red until both are edited)
- [X] T043 [P] [US4] Tests: a crate test that a module calling only `invoke` does not import `await_end` (`wasm-objdump -j Import` on a built fixture, as the secrets test does); a `WasmImportsSuite` case in `SCT/wasm/` with a WebAssembly-text guest calling `await_end` on a completed workflow and reading the state; `cargo test --workspace` green. Update `protocol/WASM-ABI.md` (and `RS/ankka/protocol/WASM-ABI.md` by `proto.sh`) with the `await_end` row "since protocol 1.15"

### Conformance

- [X] T044 [US4] In `specs/009-polyglot-runtimes/contracts/conformance.md` add the route `POST /conformance/checkout-await/{id}` (body `ok` or `fail`) and the behaviours `wf.start-and-await` and `wf.await-failed` (contracts/polyglot-sdks.md "Conformance"); add the route to every reference: `SCT/conformance/ConformanceReference.scala`, `PY/examples/shopping_cart/conformance.py`, `TS/examples/shopping-cart/conformance.ts`, `RS/examples/shopping-cart/src/conformance.rs` (both shapes), each sending `start` with the body and awaiting within 30 seconds, answering the state as JSON or letting the `WorkflowFailed` error become 424
- [X] T045 [US4] Add the two cases to `SCT/conformance/ConformanceSuite.scala`: `wf.start-and-await` (200; the state's steps are all there; the recorder shows no `status` or `ankka:lifecycle` call on that workflow between the start and the answer) and `wf.await-failed` (424; body `details.step` and `details.reason` present; `GET /conformance/checkout/{id}` then says compensated). Run `sbt 'sidecar/testOnly *ConformanceSuite' -- '*wf.*'`, then `uv run conformance`, `npm run conformance`, `./conformance.sh`: green for all four
- [X] T046 [US4] Bind `languages.feature` in `TKT/awaiting/AwaitSteps.scala`: the Scala rows of both outlines run against `QuoteWorkflow` (`offer` failing after its retries for the second); the Python and TypeScript rows are `ranElsewhere("sdks/python/examples/shopping_cart/test_await.py")` / `("sdks/typescript/examples/shopping-cart/await.test.ts")`; the "before waiting" scenario is `ranElsewhere("sdks/python/tests/test_await_old_runtime.py")`. `sbt 'testkit/testOnly *LanguagesFeatures'` reports two passed and five ignored with those names

**Checkpoint**: composition and every language green; protocol 1.15 pinned everywhere.

---

## Phase 7: User Story 5 — a long wait is served over HTTP as a stream (Priority: P2)

**Goal**: `EndpointClients.awaitEnd` as a `Source[SseEvent]` with heartbeats; the same stream through `AwaitEndStream` for a Python or TypeScript endpoint; a whole answer cut by the idle timeout.

**Independent Test**: `sbt 'testkit/testOnly *ServingFeatures'` green under a 3-second idle timeout; dropping the heartbeat turns the SSE scenario red while the whole-answer scenario stays green.

### Scala

- [X] T047 [US5] In `HTTP/SseEvent.scala` add `heartbeat: SseEvent` (event `heartbeat`, data `{}`), `ended(json: String)`, `failed(error: CommandError)` (data `{"code","message","step","reason","deleted"}` from `details`), `timedOut(timeout: FiniteDuration)` (data `{"timeout":"PT…"}`), all through `named` so a newline is refused. In `HTTP/HttpEndpoint.scala` add `sseEvents(template)(handler: () => Source[SseEvent, ?])` for a route with no path parameter beside the one-parameter form (`:401`)
- [X] T048 [US5] In `HTTP/EndpointClients.scala` add `awaitEnd[W <: Workflow[S], S](workflowId: EntityId, companion: Workflow.Companion[W, S], timeout: FiniteDuration): Source[SseEvent, NotUsed]`: `Source.future(componentClient.forWorkflow(workflowId).awaitEndAsync(companion, timeout))` mapped to `ended`/`failed`/`timedOut`, merged with `Source.tick(heartbeat, heartbeat, SseEvent.heartbeat)`, completing after the terminal event (`takeWhile(_ != terminal, inclusive = true)` or a `merge` with `eagerComplete`). `HttpServer` computes `heartbeat` when it builds `EndpointClients`: `system.settings.config.getString("pekko.http.server.idle-timeout")` parsed as a duration, a third of it, 20 seconds when `infinite` (R7; if 047's `ankka.http.sse.heartbeat` has merged, read that instead and say so in the comment)
- [X] T049 [US5] Cases in `HTTPT` (a `SseEventSuite` and an `EndpointClientsSuite` over a scripted transport): each new event's name and JSON; the source emits heartbeats at the interval, exactly one terminal event, then completes, for each of the three ends; a 60s idle timeout gives a 20s heartbeat, `infinite` gives 20s, `3s` gives 1s
- [X] T050 [US5] Add to `TKT/awaiting/QuoteEndpoint.scala` the SSE route `sseEvents("/reports/{id}") { id => clients.awaitEnd(EntityId(id), QuoteWorkflow, 10.minutes) }` (region `sse-await`) and the whole-answer route (`get("/reports/{id}/whole")`, region `whole-await`); bind `serving.feature` in `TKT/awaiting/AwaitSteps.scala`: "a wait served as server-sent events outlasts the service's idle timeout" (`offer` takes 8 seconds under a 3-second idle timeout; the client reads the stream with pekko-http's `EventStreamUnmarshalling`: at least two `heartbeat` events, then `ended` with the state, and the connection closed by completion, not reset) and "a wait served as one whole answer is cut by the service's idle timeout" (the request fails with the connection closed before the state; `r1` still reaches `Completed`)
- [X] T051 [US5] Make them green; drop the heartbeat once and confirm the first goes red while the second stays green (quickstart row 4). Re-check `.claude/rules/runtime.md`'s "SSE payloads must be JSON-encoded": every new event's data is JSON

### Python and TypeScript

- [X] T052 [US5] In `SC/ClientLogic.scala` add `awaitEndStream(request, emit: StreamToken => Unit)`: the same `transport.awaitEnd` future, with a scheduled `heartbeat` token every heartbeat interval (the same rule as T048, computed once in `ClientLogic` from the sidecar's config) until it completes, then one `ended(Payload)` or `failed(Error)` token; cancel the heartbeat on completion or on the client's cancel (`ClientService.awaitEndStream` with `out.onNext`/`onCompleted`, as `invokeStream` does). Case in `SCT`: a stream over a 2-second workflow with a 500ms heartbeat yields three or four heartbeats then `ended`; a failed one yields `failed` with `WORKFLOW_FAILED`
- [X] T053 [P] [US5] Python: `Calls.await_end_parts(timeout, *, reply, reply_codec) -> AsyncIterator[Heartbeat | Ended[T] | Failed | TimedOut]` over `self._stub.AwaitEndStream(...)`, the four frozen dataclasses with `to_json()` in `PY/src/ankka/client.py` (contracts/polyglot-sdks.md); an `@sse` route in `PY/examples/shopping_cart/endpoint.py` region `sse-await` yielding `part.to_json()`; a case in `PY/examples/shopping_cart/test_await.py` reading the route through `kit.http` with `stream=True` and asserting a heartbeat line then an ended line
- [X] T054 [P] [US5] TypeScript: `Calls.awaitEndParts<S>(timeoutMillis, reply?): AsyncIterable<AwaitPart<S>>` and the `AwaitPart` type in `TS/src/client.ts`; an `sse` route in `TS/examples/shopping-cart/endpoint.ts` region `sse-await` mapping parts to JSON lines; a case in `TS/examples/shopping-cart/await.test.ts` reading it. `npm run typecheck && npm run test:slow` green

**Checkpoint**: `serving.feature` green; a process can serve a wait as SSE.

---

## Phase 8: User Story 6 — the documentation says how to await and what to expect (Priority: P3)

**Goal**: the pages, the reference tables, the divergence, the limitation, the skills; the three documentation scenarios bound.

**Independent Test**: `sbt 'testkit/testOnly *AwaitingWorkflowsDocumentationSuite'` green; `just docs` green.

- [X] T055 [US6] Write `TKT/AwaitingWorkflowsDocumentationSuite.scala` after `TimersDocumentationSuite.scala`, one test per scenario of `features/documentation/awaiting-workflows.feature`: "the documentation describes starting a workflow and waiting for its end" (`build/workflows.md` has `## Waiting for the end`, the include lines for `QuoteEndpoint.scala#start-and-await`, `endpoint.py#start-and-await`, `endpoint.ts#start-and-await`, and `awaitEnd(`), "the documentation says what each ending answers and when to serve a wait as a stream" (the words "completed", "failed", "deleted", "paused", "no default", "server-sent events" in that section), "the documentation says Akka offers no wait for a workflow's end" (`reference/akka-divergences.md` has the summary row and the section). Run it: red
- [X] T056 [US6] `DOCS/build/workflows.md`: a section `## Waiting for the end` after "Domain state and lifecycle" with three language tabs (Scala from `QuoteEndpoint.scala`, Python from `endpoint.py`, TypeScript from `endpoint.ts`, regions `start-and-await` and `await-later`), a table of what a completed, failed, deleted and paused workflow answer, the sentence that the timeout is the caller's and required, that `thenAwaitEnd` discards the command's reply, that a wait holds the workflow in memory, that a workflow that never transitions never ends, that a consumer should start a workflow and an endpoint await it (a wait holds that slice of the consumer's projection), when to serve the wait as a stream (link to streaming.md), and that both halves must be at this release. Rust: a sentence pointing to the crate's `await_end` in `rust-sdk.md`. No feature numbers, no "see above" (`docs.md`)
- [X] T057 [P] [US6] `DOCS/build/component-client.md`: the table row for `awaitEnd(Companion, timeout)` and `thenAwaitEnd(timeout)` under "Addressing a component"; a paragraph under "Timeouts" that an await's timeout is the caller's and is not `ankka.ask-timeout`; `DOCS/build/streaming.md`: `## Serving a wait` with the SSE route in three tabs (region `sse-await`), the heartbeat rule, the whole-answer caveat, and "Only agents stream" reworded; `DOCS/build/http-endpoints.md`: the `sseEvents` rows
- [X] T058 [P] [US6] `DOCS/reference/error-codes.md`: nine codes (the description, the table row `WorkflowFailed | WORKFLOW_FAILED | 424 | ABORTED | a workflow a caller waited for failed or was deleted | no`), a `details` paragraph with the three keys, the protocol enum line `WORKFLOW_FAILED = 8`, and "the same nine values"; `DOCS/reference/akka-divergences.md`: a Summary row and a section `## A caller can wait for a workflow's end` in the Autonomous-agents style (what ankka does, that Akka's handlers reply on return and a client polls or subscribes to notifications it may not get); `DOCS/reference/limitations.md`: three entries — the gateway's HTTP route request timeout is fifteen seconds and applies to a wait served through it, as to any stream (new; cite `networking.md`); a module gets the plain wait and cannot serve it as a stream; a wait across a rolling update needs both halves at this release
- [X] T059 [P] [US6] `DOCS/reference/sidecar-protocol.md`: Versioning gains the 1.15 paragraph (two rpcs, `Error.details`, the heartbeat and ended tokens, `UNIMPLEMENTED` from an older runtime), the two sentences still saying `1.12` corrected to the current version, prose beside the generated rpc table naming `AwaitEnd` and `AwaitEndStream` (the coverage check); `DOCS/reference/wasm-abi.md`: the `await_end` import row and the diagram's alt text; `DOCS/reference/scala-sdk.md`, `python-sdk.md`, `typescript-sdk.md`, `rust-sdk.md`: the Workflow and Calling-components rows for the await (and `then_await_end`/`thenAwaitEnd`/`invoke_then_await_end`), with `rust-sdk.md` also saying a module's long wait holds its instance, so a module awaits from a step, a route or a tool, not a command; `DOCS/reference/glossary.md`: "Wait" and "Heartbeat" entries in its own words
- [X] T060 [P] [US6] Skills under `SKILL/`: `ankka-workflows/SKILL.md` a rule ("a caller that needs a workflow's result awaits its end with a timeout of its own; it does not poll the lifecycle") and a mistake ("an `eventually` over the lifecycle where `awaitEnd` would do"); `ankka-endpoints/SKILL.md` serving a wait as SSE and the 424; `ankka-python`, `ankka-typescript`, `ankka-rust` the client call lists; `ankka/SKILL.md`'s routing row for `ankka-workflows` mentions waiting. Then `just docs-sync` (renders into `marketplace/` and `ankka.g8/`, refreshes the includes and the protocol table) and `just docs`
- [X] T061 [US6] Run `sbt 'testkit/testOnly *AwaitingWorkflowsDocumentationSuite'` green; `sbt 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'` still green; `uv run --project tools/docs pytest tools/docs` green

**Checkpoint**: the documentation describes the feature and the checks say so.

---

## Phase 9: Polish — the sample, the rules, the whole run

- [ ] T062 Rewrite `samples/shopping-cart/src/test/scala/shoppingcart/CheckoutWorkflowSuite.scala` over `componentClient.forWorkflow(id).awaitEnd(CheckoutWorkflow, 40.seconds)`: delete the private `eventually` and `awaitStatus`; assert the state `awaitEnd` returns says `charged` and, in the compensation case, `compensated` (the workflow ends completed with that status; it does not fail). Add `post("/carts/{id}/checkout-and-wait")` to the sample's endpoint (region `checkout-and-wait`) for quickstart's "Running it by hand". `sbt shoppingCart/test` green (SC-004)
- [ ] T063 [P] Add to `.claude/rules/runtime.md` a short section "An await is a reserved method with a hold and a re-ask" (the method, the two replies, hold = ask-timeout/2, `PostStop` answers `NotYet`, nothing journalled) and the trap that a `handled` counted per `NotYet` grows with the wait; to `.claude/rules/sidecar.md` that `AwaitEnd`/`AwaitEndStream` are the model for a long call a process makes, and that `Error.details` is read by every SDK; to `.claude/rules/wasm.md` that `await_end` is in its own link block. Only what a later session needs
- [ ] T064 [P] `sbt scalafmtAll scalafmtSbt`; `cd PY && uv run ruff check . && uv run ruff format .` (or the project's formatter); `cd TS && npm run lint` if present; `cargo fmt --all` in `RS`
- [ ] T065 Run the quickstart top to bottom: `caffeinate -i sbt -Dankka.cluster.tests=off test` (every module, including `testkit/testOnly *awaiting.*` with 25 tests of which 5 ignored by name), the four conformance runs, the protocol pins, `just docs-sync && just docs && just features`; fix what is red; record in this file's Notes the wall time of `InstancesFeatures` and `ServingFeatures`
- [ ] T066 `.github/ci-coverage.py`: confirm the new test files are claimed by the filters that run them (`modules/testkit/**` by `build (testkit)`, `sdks/python/**` by `sdk-python`, `sidecar/**` by `build (sidecar)`); nothing new at the top level, so nothing to add — verify with the script, do not assume
- [ ] T067 Open the pull request against `main` from `048-awaiting-workflows` with a description that names the protocol minor as provisional (R8) and the 047 heartbeat overlap (R7); squash-merge is the project's habit

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 first (it breaks the build on purpose), then T002; T003–T006 in parallel with T002.
- **Foundational (Phase 2)**: T007 → T009 → T011 → T012 (the engine); T014 → T015 (the transport), T017 (the client) in parallel with the engine after T007; T019–T022 (fixtures) in parallel with everything after T017's signatures exist; T008, T010, T013, T016, T018 are each written before the task they test is finished.
- **US1 (Phase 3)** and **US2 (Phase 4)**: after Phase 2; US2 after US1 only because they share `AwaitSteps`.
- **US3 (Phase 5)**: after Phase 2; independent of US1/US2 but shares the steps file, so sequence it.
- **US4 (Phase 6)**: composition (T032–T033) after Phase 2; the sidecar (T034–T036) after T005–T007 and T014–T015; the three SDKs (T037–T043) in parallel after T005–T006 and T034; conformance (T044–T045) after all three SDKs; T046 last.
- **US5 (Phase 7)**: the Scala half (T047–T051) after Phase 2; the process half (T052–T054) after T034 and T037/T040.
- **US6 (Phase 8)**: T055 first (red), the pages in parallel after the regions they include exist (T022, T050, T053, T054, T062's region), T061 last.
- **Polish (Phase 9)**: after every story.

### User Story Dependencies

- **US1**: Phase 2 only.
- **US2**: Phase 2; shares `AwaitSteps` with US1.
- **US3**: Phase 2; proves T015's re-ask and T012's `PostStop`.
- **US4**: Phase 2 for composition; Phase 1's protocol tasks for the languages; conformance needs all three SDKs.
- **US5**: Phase 2 for Scala; US4's sidecar `awaitEnd` for Python and TypeScript.
- **US6**: the regions the pages include; otherwise independent.

### Parallel Opportunities

- Phase 1: T003, T004, T005, T006 together after T001.
- Phase 2: the engine (T009–T013), the transport (T014–T016) and the client (T017–T018) are three files in three modules.
- Phase 6: T037–T039, T040–T041, T042–T043 are three languages in three trees.
- Phase 7: T053 and T054 together after T052.
- Phase 8: T057, T058, T059, T060 together after T056's section exists (T055 names it).

---

## Parallel Example: Phase 6, the three SDKs

```bash
# After T034 (sidecar awaitEnd) and T005–T006 (protocol, version):
Task: "T037 Python: WORKFLOW_FAILED, Error.details, Calls.await_end, then_await_end, UNIMPLEMENTED → too old"
Task: "T040 TypeScript: ErrorCode.WorkflowFailed, CommandError.details, awaitEnd, thenAwaitEnd, tooOld"
Task: "T042 Rust: ErrorCode::WorkflowFailed, details, the await_end import in its own block, HostImports, ModuleLoader"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1, then Phase 2 (the engine, the transport, the client, the fixture).
2. Phase 3: four scenarios green; break `answerWaiters` once.
3. Stop and demo: a Scala endpoint answers a quote in one request, with no query and no polling.

### Incremental Delivery

1. US2 completes `awaiting.feature`: every ending, two callers, one call in the topology.
2. US3 proves the wait on a cluster of three.
3. US4 brings the step composition, the protocol minor, the three SDKs and conformance.
4. US5 brings the stream form in Scala, then for processes.
5. US6 writes it down; Phase 9 rewrites the sample and runs everything.

### Notes

- A bound scenario is red before its implementation task; a scenario that passes before the code exists is bound to nothing.
- `sbt 'testkit/testOnly *awaiting.*'` must report 25 tests, 20 run and 5 ignored by name (`ranElsewhere`); a green run with fewer tests matched nothing.
- The protocol minor is 1.15 until 046 or 047 merges first; then every pin in T006 moves.
- Commit after each phase at least; never `git stash` bare in this worktree.
