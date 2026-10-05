# Tasks: Agent Approvals and MCP — A Tool That Waits for a Person, Tools That Reach Other Services

**Input**: Design documents from `/specs/029-agent-approvals-and-mcp/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
code into a test before the code that relies on it. The scenarios are in `features/agents/`;
where a task says "case", it means a `test(...)` in the named suite (or its equivalent in the
SDK's test runner), named for the scenario it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a request agent waits for approval), US2 (an autonomous agent waits for
  approval), US3 (a tool calls another service as the service), US4 (an agent uses an MCP
  server's tools, with its credential and result guardrails)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `RT`/`RTT` =
`modules/runtime/src/{main,test}/scala/…/runtime`; `HTTP` = `modules/http/src/main/scala/…/http`;
`AGENT`/`AGENTT` = `modules/agent/src/{main,test}/scala/…/agent`; `AUTO` = `AGENT/autonomous`;
`MCP`/`MCPT` = `AGENT/mcp`, `AGENTT/mcp`; `TK`/`TKT` =
`modules/testkit/src/{main,test}/scala/…/testkit`; `TKA` = `TKT/autonomous`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `PROTO` =
`protocol/src/main/protobuf/ankka/protocol/v1`; `PY` = `sdks/python`; `TS` = `sdks/typescript`;
`RS` = `sdks/rust`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`. "R*n*" is a section of
`research.md`; "V*n*" an item of its *Verify first* list; a contract is named by its file under
`contracts/`. A scenario is named by its file under `features/agents/` and its name.

The branch `029-agent-approvals-and-mcp` exists, in the worktree
`.claude/worktrees/029-agent-approvals-and-mcp`; work there. Every `sbt` command below takes
`-Dankka.cluster.tests=off` unless the task names a k3s suite. When a verify-first task shows its
claim false, stop and say so before building on it; when it holds, add a line to a
`## Verified during implementation` section at the end of `research.md`.

---

## Phase 1: Setup — the ground everything stands on

**Purpose**: a green baseline, and the one outside fact the spec was amended for.

- [X] T001 Record the baseline: `sbt 'agent/test' 'testkit/testOnly *AgentSuite *AgentStreamSuite *HttpSseSuite *SessionMemoryCompatibilitySuite'` and `sbt 'testkit/testOnly *AutonomousAgentSuite *ResumePointSuite *EventCompatibilitySuite' 'sidecar/testOnly *ProtocolSuite *RemoteAgentSuite *RemoteAutonomousAgentSuite'` all pass before any change. Run `git add -N features/agents specs/029-agent-approvals-and-mcp && python3 .github/ci-coverage.py` and confirm every new path is claimed by a filter.
- [X] T002 [P] V5: establish the provider's rule for a tool's name. Read Anthropic's tool-use reference for the `name` pattern, and, when `ANTHROPIC_API_KEY` is set, add a live case to `modules/agent/src/test/scala/…/agent/AnthropicProviderSuite.scala` that offers a tool named `mcp__tickets__create` (accepted) and one named `mcp:tickets:create` (refused). If a colon is accepted, stop: the amendment to FR-012 is then a choice and not a necessity, and it is the user's to make.

**Checkpoint**: baseline green; the name form `mcp__<server>__<tool>` is confirmed or the plan is paused.

---

## Phase 2: Foundational — what both kinds of agent share

**Purpose**: the tool's declaration, the values of an approval, and one function both loops run a
tool through. Blocks US1, US2 and US4.

**⚠️ CRITICAL**: T006 changes no behaviour. Every existing agent suite must be green after it, before anything is built on it.

- [X] T003 Tests first, in `AGENTT/FunctionToolSuite.scala`: `requiresApproval` returns a tool whose `approval` is `Some(Approval(None))` and whose `spec` is unchanged; `requiresApproval(30.minutes)` carries the limit; a tool built as today has `approval == None` and `origin == ToolOrigin.Own`; a zero or negative limit is an `IllegalArgumentException`.
- [X] T004 In `AGENT/FunctionTool.scala`: add `final case class Approval(within: Option[FiniteDuration])`, `enum ToolOrigin { Own; Mcp(server: String) }`, the fields `approval: Option[Approval] = None` and `origin: ToolOrigin = ToolOrigin.Own` on `FunctionTool`, `def requiresApproval`, `def requiresApproval(within: FiniteDuration)`, and a `private[ankka]` way for `FunctionTool.raw` to set both (R1). T003 passes.
- [X] T005 [P] Create `AGENT/Approvals.scala` per `scala-api.md` and `data-model.md` "Values": `ApprovalRequest`, `Decision` (carrying its `approvalId`) with `Decision.approved(by)`, `Decision.refused(by, note)`, a `private[ankka]` `Decision.expired(at)` (`by = "ankka"`, `expired = true`) and `Decision.problem(d): Option[String]` refusing a blank `by`; `enum AgentOutcome[+O]`, `enum AgentPart`, `final class ApprovalAwaited(val requests: Vector[ApprovalRequest]) extends RuntimeException`; jsoniter codecs through `Codecs.make`. Cases in a new `AGENTT/ApprovalsSuite.scala`: each value round-trips; `expired` and `note` are absent from the JSON at their defaults; a blank `by` is a problem and `"ankka"` with `expired` is not.
- [X] T006 Create `AGENT/ToolRunner.scala`: move `PromptReplay.runTool` (`AGENT/PromptReplay.scala:53`) into `ToolRunner.run(tools: Map[String, FunctionTool], call: ToolCall): ToolResult`, unchanged in behaviour, and call it from `AgentLoop.runToolLoop`, `AgentLoop.streamToolLoop` and `IterationLoop.runTools`. T001's suites pass unchanged.
- [X] T007 [P] In `AGENT/memory.scala` add `decision: Option[Decision] = None` to `SessionMessage.ToolResultMessage`. In `TKT/SessionMemoryCompatibilitySuite.scala` add a case that a `ToolResultAdded` line pinned before this change still decodes and that a result with no decision writes no `decision` field; pin one line with a decision in `modules/testkit/src/test/resources/journal/session-memory.json`.

**Checkpoint**: a tool can say it requires approval and nothing yet acts on it; all existing suites green.

---

## Phase 3: User Story 1 — a request agent waits for approval (Priority: P1) 🎯 MVP

**Goal**: the model's call to a tool that requires approval gives the caller an approval request;
a decision, after a restart if there was one, runs the tool once or tells the model it was
refused.

**Independent Test**: `sbt 'testkit/testOnly *ApprovalSuite'` — script a tool call, `ask`, assert
the awaiting outcome and that no tool ran, `restartService()`, `decide`, assert the tool ran once
and the model's next request carried its result.

### Tests first

- [X] T008 [US1] Create the fixture `TKT/ApprovalAgent.scala`: an agent `helper` with tools `issue_refund` (`requiresApproval`), `read_order` (none) and `close_account` (`requiresApproval(2.seconds)`, used in Phase 5), each recording its runs and arguments in a queue the suite reads, as `TKT/WeatherAgent.scala` does; handlers `command("ask")` and `stream("chat")`.
- [X] T009 [US1] Create `TKT/SuspendedTurnSuite.scala` over `EventSourcedTestKit` for `SessionMemoryEntity`, one case per row of `data-model.md` "Commands": `suspend-turn` sets `suspended`; a second while a request is awaiting is `Conflict`; `decide-approval` on a session with no suspended turn is `NotFound` naming the session, with an unknown id `NotFound` naming the id, on a decided request `Conflict`, with a blank `by` `BadRequest`, and otherwise sets the decision; `end-turn` clears and is harmless when nothing is suspended; `append` with `endsTurn` persists the messages' events and `TurnEnded` together. And for a second decision (R9): after `append` with `endsTurn` has written a tool result carrying a decision, `decide-approval` for that id is `Conflict`; for an id in neither the suspended turn nor any result it is `NotFound` naming the id; on an empty session `NotFound` naming the session.
- [X] T010 [US1] In `TKT/SessionMemoryCompatibilitySuite.scala` pin `TurnSuspended`, `ApprovalDecided` and `TurnEnded`, an `Append` with `endsTurn`, and a `SessionHistory` with a suspended turn; extend the "every event case is pinned" assertion to the new last case; add the case for `approvals.feature` "a session with no approval request is read as it was recorded": every line pinned before this feature decodes to a history whose `suspended` is `None`.
- [X] T011 [US1] Create `TKT/ApprovalSuite.scala` (mixing in `LogCapturing`; `AnkkaTestKit.start(Seq(ApprovalAgent.descriptor) ++ AgentRuntime.descriptors, Seq(AgentRuntime.withDefaultModel(model)))`), one case per scenario of `approvals.feature` except the three about a time limit: gives the caller an approval request; approved runs once and the model is told its result; still awaiting after `restartService()`; refused never runs and the model is told the note; decided once (both rows); an id the session does not hold; a session no agent has used; a session awaiting takes no new request (assert `model.callCount` did not move); a tool that requires no approval runs beside one that waits; two tool calls made together (`expectParallelToolCalls`), each decided alone, with the model not asked until both are; the session shows the approval request (`approvals()` and `history`); a tool with no approval runs; compaction keeps the request (`AgentRuntime.withCompaction`, a history over the limit, then assert `suspended` is unchanged); who decided is on the tool's result; a decision that names nobody is refused and decides nothing. Also one case for R4's cut-off turn: approve with the model scripted to fail, assert `decide` answers the model's failure, the tool ran once, the session then takes a new request, and a second `decide` for the same id is `NotFound` and runs nothing.

### Implementation

- [X] T012 [US1] In `AGENT/SessionMemoryEntity.scala`: `SuspendedTurn` and `suspended: Option[SuspendedTurn] = None` on `SessionHistory`; the events `TurnSuspended`, `ApprovalDecided`, `TurnEnded` appended after `JudgmentUsageAdded` and their fold; the commands `suspend-turn`, `decide-approval`, `end-turn` and the query `suspended`, with their serializers; `endsTurn: Boolean = false` on `Append` (R2, `data-model.md`). T009 and T010 pass.
- [X] T013 [US1] In `AGENT/AgentLoop.scala`: `run` and `runStreaming` return the answer or the approval requests awaiting. Add the admission check of R5 at the start of `execute` and `executeStreaming` — `Conflict` when the session's suspended turn has a request awaiting, using the history read where there is one and the `suspended` query otherwise — and, when the turn is suspended with nothing awaiting, `end-turn` first (R4). In `runToolLoop` and `streamToolLoop`, when a response's tool calls include a tool with `approval`: run the others through `ToolRunner`, then `suspend-turn` with the user message, every produced message, the results already made, usage, the step count and one `ApprovalRequest` per waiting call (a fresh id each; `expiresAt` from the limit).
- [X] T014 [US1] In `AGENT/AgentLoop.scala` add `resume(effect, turn)` per R3 and R4: the prompt is `buildPrompt` over the history as read now, then `PromptReplay.replay(turn.messages)`, then one `ToolResults` joining the recorded results with, in call order, each approved call run through `ToolRunner` and each refused or expired call as an error result whose text says it was refused, by whom, and the note; every such result carries its `Decision`. Input guardrails are not run; the loop then continues from `turn.steps`, may suspend again, and on an answer runs the output guardrails, decodes and writes the whole turn with `endsTurn`. Any `Left` ends the turn (`end-turn`) and is returned.
- [X] T015 [US1] In `AGENT/Agent.scala` make `Agent.Companion.descriptor` refuse a handler or stream named with the prefix `ankka:`; case in `AGENTT` beside the existing duplicate-name check.
- [X] T016 [US1] In `AGENT/AgentRuntime.scala` (`AgentHost`): handle `Invoke` with method `ankka:decide` in `idle` (stashed in `busy` as any `Invoke`) — on a virtual thread, `decide-approval`; if a request is still awaiting, reply with those awaiting; otherwise look up the suspended handler's binding (missing: `end-turn`, `Internal` naming it; not the handle's: `BadRequest` naming the handler that waits), run the handler on `turn.payload` to rebuild the effect, and `loop.resume`. Reply `Succeeded` with the metadata entry `ankka-outcome: approval` and the requests as payload when awaiting, for `start` and `decide` alike (R6). Never touch the actor's context from the `Future`.
- [X] T017 [US1] In `RT/wire.scala` add `final case class StreamAwaiting(payload: Array[Byte]) extends StreamToken`; no suite round-trips a stream token today (only `TKT/AgentStreamSuite.scala` sends them, on one node), so create `RTT/WireSerializationSuite.scala` and round-trip `StreamAwaiting`, `Token`, `StreamCompleted` and `StreamFailed` through the actor system's `SerializationExtension`. In `AgentHost.startStream` send it in place of `StreamCompleted` when the turn suspends.
- [X] T018 [US1] In `AGENT/AgentRuntime.scala` (`AgentCalls`): `ask(handle)` for both handle shapes; `decide(handle)(approvalId, decision)` for `CommandHandle`, `NoArgHandle` and `StreamHandle`; `approvals()`; `streamParts(handle)(input)`; `call` throws `ApprovalAwaited` and `stream` fails with it at its end. Read the outcome from the reply's metadata.
- [X] T019 [US1] V1, then `http`: confirm with a compiling fixture that a stream route can take a second element type without breaking inference at the four overloads in `HTTP/HttpEndpoint.scala:298-324`; if it cannot, give the event route its own name. Add `SseEvent(name: Option[String], data: String)` with `SseEvent.data` and `SseEvent.json`, and marshal it in `HTTP/HttpServer.scala` beside the string route. In `TKT/ChatEndpoint.scala` add a route over `streamParts`, and in `TKT/HttpSseSuite.scala` the case for `approvals.feature` "a stream ends with the approval request as its last part": the text frames, then one event named `approval`, then the stream ends.
- [X] T020 [US1] `sbt 'testkit/testOnly *ApprovalSuite *SuspendedTurnSuite *SessionMemoryCompatibilitySuite *HttpSseSuite *AgentSuite *AgentStreamSuite'` passes. Show it can fail: remove the approval branch in the loop and watch the first case go red on "the tool has not run"; put it back.

**Checkpoint**: User Story 1 holds for a Scala agent, across a restart, with no time limit.

---

## Phase 4: User Story 2 — an autonomous agent waits for approval (Priority: P1)

**Goal**: an autonomous agent's tool call that requires approval is told to every subscriber,
uses none of the task's budget while it waits, and goes on at a decision.

**Independent Test**: `sbt 'testkit/testOnly *AutonomousApprovalSuite'` — script a call to an
approval-requiring tool, assert the notification and that the model's call count and the record's
iteration hold, `decide`, assert the tool ran once and the task completed.

### Tests first

- [X] T021 [P] [US2] In `TKA/EventCompatibilitySuite.scala` pin `InstanceEvent.ApprovalRequested` and `ApprovalDecided` in `modules/testkit/src/test/resources/journal/autonomous-events.json`, update the "every event case is pinned" assertion (its last-case check names `Terminated`), and add a case that a `Working` pinned before decodes with `approvals` empty.
- [X] T022 [P] [US2] In `TKA/InstanceEntitySuite.scala`: `ApprovalRequested` with no current task is refused; `ApprovalDecided` for an unknown id is `NotFound`, for a decided one `Conflict`, with a blank `by` `BadRequest`; `IterationStarted` and `TaskEnded` clear `approvals`. A second `ApprovalDecided` after `IterationStarted` has cleared `approvals` is `NotFound` from the record — the host's lookup in the task's session is T029's.
- [X] T023 [P] [US2] In `TKA/ResumePointSuite.scala`, cases for `SettleCalls` (R8): a call with an approval request awaiting waits and makes no model call; a decided-approved call with no result is run and its result appended with the decision; a decided-refused call gets the refusal as its result and is not run; a call with no approval request and no result is run; when every call has a result the next point is `NextIteration(n + 1)`. This holds `autonomous-approvals.feature` "a tool approved before the service stopped runs again when its result was not recorded".
- [X] T024 [P] [US2] In `AGENTT/autonomous/NotificationCodecSuite.scala` list `ApprovalRequested` and `ApprovalDecided` (its line 35 asserts every case is listed).
- [X] T025 [US2] Give `TKA/Answerer.scala` a tool `restart_service` (`requiresApproval`) and `drain_node` (`requiresApproval(2.seconds)`, Phase 5). Create `TKA/AutonomousApprovalSuite.scala`, one case per scenario of `autonomous-approvals.feature` except the one about expiry: every subscriber is told (two `notifications()` sources); waiting uses none of the budget (assert `model.callCount` first reaches 1, then holds across a wait, and `state().currentTask.iteration` is unchanged — R21's "worker never ran" trap); approved runs once, the iteration is counted once and the task completes; refused never runs and the task goes on; still awaiting after `restartService()`; cancelling the task makes `decide` `NotFound` and nothing runs; suspended then resumed leaves the request and `decide` is accepted; `state()` and the task's session show who decided. For V8 and SC-002, with `idlePassivationAfter = 1.second` and no subscriber left: wait for the `Deactivated` notification on a source opened before, close it, wait again, then `decide` — the tool runs once and the task completes; and a second case that cancels the task after passivation — the task ends cancelled and `decide` is `NotFound`. A third: a second `decide` after the task has gone on to its next iteration is `Conflict`, not `NotFound`. If a passivated waiting instance does not come back on `decide`, stop and say so.

### Implementation

- [X] T026 [US2] In `AUTO/InstanceEntity.scala`: `approvals: Vector[ApprovalRequest] = Vector.empty` on `Working`; the events `ApprovalRequested(request)` and `ApprovalDecided(approvalId, decision)` appended after `Terminated`; their fold, and the clearing in `IterationStarted` and `TaskEnded`; `problem(...)` per `data-model.md`. T021 and T022 pass.
- [X] T027 [P] [US2] In `AUTO/Notification.scala` add the two cases and `awaiting: Vector[ApprovalRequest]` to `AgentState` (from `record.current`). T024 passes.
- [X] T028 [US2] In `AUTO/IterationLoop.scala`: in `runTools`, record `ApprovalRequested` for every call to a tool with `approval` before running any tool of the response, then run the others and append their results, then emit; add `ResumePoint.SettleCalls(n, message)` and make `resumePoint` return it whenever the last model message has a call with no result in the session; `run` settles per R8 and returns a new `IterationResult.Waiting` while any request is awaiting. T023 passes.
- [X] T029 [US2] In `AUTO/AutonomousAgentHost.scala` and `AUTO/Calls.scala`: `HostProtocol.Decide` with its serializer; `Operations.run` records `ApprovalDecided`, emits `Notification.ApprovalDecided` and replies `Done`; the worker's `iterate` treats `Waiting` as idle (`idle(true)`, `pause`, no failure, no iteration) and is woken by the operation's `poke()`; `AutonomousAgentCalls.decide(approvalId, decision)` and `decideAsync`. The descriptor's declared handlers gain `decide`. Only an instance that is `Waiting` on a decision, or has no task, counts as idle for `armIdle`; a working one never does. Before recording a decision the host looks for the id in `current.approvals`, then on a tool result's decision in the current task's session (`Conflict`), then answers `NotFound`.
- [X] T030 [P] [US2] In `TKA/AutonomousFixturesSuite.scala` write `protocol/fixtures/autonomous/agent-notification-approval-requested.json` and regenerate `agent-state.json` with `awaiting`; copy to the three SDKs with their scripts (`cd sdks/python && uv run python scripts/proto.py`; `cd sdks/typescript && npm run proto`; `sdks/rust/scripts/proto.sh`); `sbt 'core/testOnly *EncodingFixturesSuite'` still passes.
- [X] T031 [US2] `sbt 'testkit/testOnly *AutonomousApprovalSuite *ResumePointSuite *InstanceEntitySuite *EventCompatibilitySuite *AutonomousAgentSuite' 'agent/testOnly *NotificationCodecSuite'` passes. Show it can fail: make `IterationStarted` keep `approvals`; the approved-continues case times out; put it back.

**Checkpoint**: User Stories 1 and 2 hold for Scala agents with no time limit.

---

## Phase 5: The time limit — User Stories 1 and 2 (FR-016)

**Purpose**: an approval request with a time limit is refused by the platform when it passes.
Depends on Phases 3 and 4.

- [X] T032 [US1] V2: in a case in `TKT/ApprovalSuite.scala`, establish how `AgentRuntime.start` learns that `TimerRuntime` is registered (the service's extensions by name, as `RT/Ankka.scala:140` does for `grpc-server`; `TimerRuntime.name` is `"timers"`). If the runtime exposes no such thing to an extension, add the smallest read-only accessor on `AnkkaService`.
- [X] T033 [US1] Tests first, in `TKT/ApprovalSuite.scala` with `TimerRuntime()` registered: the three scenarios of `approvals.feature` about a time limit, using `close_account`'s two seconds — refused when nobody decides in time (the model's next request carries the expiry, the history shows the answer and a result whose decision is `expired` by `ankka`); a request with no limit is still awaiting after the same wait; a decision after expiry is refused. And without `TimerRuntime`: the turn is `Internal` naming it and the session has no suspended turn. And `approvals.feature` "an approval request's time limit holds after the service restarts": suspend, `restartService()`, let the limit pass, and assert one expiry — one result with an expired decision, one model call for it.
- [X] T034 [US2] Tests first, in `TKA/AutonomousApprovalSuite.scala`: `autonomous-approvals.feature` "an autonomous agent's approval request that expires is refused and the task goes on", with `drain_node`; and a start without `TimerRuntime` fails naming it when a tool of the probe instance declares a limit.
- [X] T035 [US1] Create `AGENT/ApprovalExpiry.scala`: the platform timed action `ankka-approval-expiry` (`platform = true`) with one handler taking `{kind, componentId, id, approvalId}`; it sends `Decision.expired` through `ankka:decide` to the agent's host, or `HostProtocol.Decide` to the autonomous host, and treats `Conflict` and `NotFound` as done. Add it to `AgentRuntime.descriptors`. In `AgentLoop` schedule `approval:<component>:<session>:<id>` through `DatabaseTimerScheduler` when a request has `expiresAt`, refusing with `Internal` before `suspend-turn` when timers are absent; delete the timer on a decision, best effort. An expiry's resume names the suspended handler itself, so no handle is needed.
- [X] T036 [US2] In `AUTO/IterationLoop.scala` and `AGENT/AgentRuntime.scala`: schedule the same timer when `ApprovalRequested` carries `expiresAt`; in the start-up probe (`AgentRuntime.scala:180-197`) fail the start naming `TimerRuntime` when a tool declares a limit and timers are absent.
- [X] T037 [US1] `sbt 'testkit/testOnly *ApprovalSuite *AutonomousApprovalSuite'` passes, including T033 and T034.

**Checkpoint**: approvals are complete for Scala agents.

---

## Phase 6: User Story 3 — a tool calls another service as the service (Priority: P2), Scala

**Goal**: a tool reaches `context.services`, the called service's ACL admits the agent's service
by name, and the call is recorded inside the tool call.

**Independent Test**: `sbt 'testkit/testOnly *AgentServiceCallSuite'` — two services under TLS in
one JVM; the called one admits only the caller's name.

- [X] T038 [US3] V3: read `RT/Observability.scala` and `RT/Recorder.scala` for what records a span that is not a handler's, and how a name is admitted to `DeclaredNames`. Write a failing case in a new `TKT/AgentServiceCallSuite.scala` first: an endpoint calls an agent whose tool calls a component; the request's trace shows the tool call under the agent and the component call inside it (`service-calls.feature` "a tool's call to another service is in the trace inside the tool call", with a component standing in until T040).
- [X] T039 [US3] In `AGENT/Agent.scala` and `AUTO/AutonomousAgent.scala` add `def services: ServiceClients` to `AgentContext` and `AutonomousAgentContext` and their `Simple…` classes; pass `service.services` in `AGENT/AgentRuntime.scala` at the three sites that pass `service.secrets` (`:123`, `:186`, `:227`); in `SC/RemoteAgent.scala` and `RemoteAutonomousAgent.scala` nothing reads it. If 025 has landed and the field exists, this task is only the check that it does.
- [X] T040 [US3] In `AGENT/ToolRunner.scala` run each tool inside a span named for the agent's component and the tool, admitting an agent's tool names when it starts (a request agent's are known per effect: admit on first use from the declared tool, never from a model's call for an unknown tool). T038's case passes.
- [X] T041 [US3] In `TKT/AgentServiceCallSuite.scala`, with the TLS serving helper the HTTP suites use (`TlsServing`, in `http`'s test sources — if `testkit` cannot see it, add `http % "test->test"` to `testkit` in `build.sbt` and say so in the plan's structure): `service-calls.feature` "a tool's call is admitted by an ACL that names the agent's service" (and a second caller is refused), and "a refusal by the called service reaches the model as the tool's error" (the model's next request carries an error result naming the refusal). Use `pekko.actor.provider = local` for the systems that serve TLS.
- [ ] T042 [US3] SC-003 on k3s: add one case to `controlplane/src/test/scala/…/EndToEndClusterSuite.scala`, "a tool calls another service". First establish whether a deployed service can be given a scripted model (`ANKKA_MODEL_SCRIPT`, which the SDK integration kits set for the sidecar); if it can, add an agent with one tool to the shopping cart sample that calls a second deployed service whose route admits only the first, drive it with `InPod.curl`, and assert the same route refuses a request from the node. If it cannot, hold SC-003 with T041 and record in `research.md` why the k3s case was not built. Deploy the real image only for the two services the case needs.

**Checkpoint**: a Scala tool calls another service by name.

---

## Phase 7: User Story 4 — an agent uses an MCP server's tools (Priority: P2)

**Goal**: an agent lists MCP servers; their tools are read at start and offered as
`mcp__<server>__<tool>`; a server can require approval, be sent a credential, and have its
results checked by result guardrails.

**Independent Test**: `sbt 'agent/testOnly *McpClientSuite' 'testkit/testOnly *McpAgentSuite'` —
start a service whose agent lists a scripted server, assert the tools in the model's request,
call one, assert what the server saw; stop the server and assert the start fails naming it.

### The declaration and the variables

- [X] T043 [P] [US4] In `CORE/PlatformVariables.scala` add `"ANKKA_MCP_"` to `RuntimeOnlyPrefixes`; the suites that iterate the declaration (`CORET/PlatformVariablesSuite.scala`, `PlatformDeclarationSuite`, the operator's rendering suites and the sidecar's `config` suite) need no edit and must show the prefix routed to the platform's program and withheld from a module — run them and confirm a variable of the prefix appears in their iteration.
- [X] T044 [P] [US4] Tests first, in a new `MCPT/McpServerSuite.scala`: a name outside `[a-z0-9-]` is refused; `McpServer.at`, `.service` and `.named` build the three addresses; a header's variable not starting `ANKKA_MCP_` is refused; `requiresApproval` and its limit are carried; `McpServer.problems(servers, ownTools)` refuses one name twice and an own tool named `mcp__…`; `toolName("tickets", "create") == "mcp__tickets__create"`, and it parses back one way.
- [X] T045 [US4] Create `MCP/McpServer.scala` per `scala-api.md` "MCP servers" and R14, R16. In `AGENT/Agent.scala` add `def mcpServers: Vector[McpServer] = Vector.empty` and `def resultGuardrails: Vector[Guardrail] = Vector.empty` to `Agent.Companion`, carried on `AgentDescriptor` and checked in `descriptor`; in `AUTO/AutonomousAgent.scala` add `.mcpServers(...)` and `.resultGuardrails(...)` to `AutonomousAgentDefinition`, checked in `problems`. T044 passes; add the definition cases to `AGENTT/autonomous/AutonomousAgentDefinitionSuite.scala`. This holds `mcp-servers.feature` "an agent that lists one MCP server twice is refused where it is built".

### The client

- [X] T046 [US4] Create `AGENT/TestMcpServer.scala` on the JDK's `HttpServer`, loopback and port 0, per `scala-api.md` "Test kits": `tool(name, description, schema)(answer)`, `failNext(tool, error)`, `answerAsEventStream(on)`, `expireSessionOnce()`, `pageSize(n)`, `requests` (method, headers, body), `url`, `stop()`. It answers `initialize` with a session id, `tools/list` with a cursor when paged, and `tools/call`.
- [X] T047 [US4] Tests first, in a new `MCPT/McpClientSuite.scala` against `TestMcpServer`: `initialize` then `notifications/initialized`; the tool list across two pages; a call's arguments reach the server and its text parts come back joined; the same over an event-stream answer; structured content with no text is its JSON; a non-text part is named and left out; a result marked as an error and a JSON-RPC error are each a `Left`; a lost session is started again and the call repeated once; headers are sent on every request; a server that is down is a failure naming its address within the connect timeout; a server that answers `initialize` with 401 or 403 is a failure naming the status and never the header's value.
- [X] T048 [US4] Create `MCP/McpTransport.scala` (one POST, answered with status, headers and body; `UrlTransport` over `java.net.http.HttpClient`) and `MCP/McpClient.scala` (R13). T047 passes. Add the two timeouts to `modules/agent/src/main/resources/reference.conf` as `ankka.agent.mcp.connect-timeout` and `call-timeout`, with `${?ANKKA_MCP_CONNECT_TIMEOUT}` and `${?ANKKA_MCP_CALL_TIMEOUT}`.
- [X] T049 [P] [US4] V4: create `MCPT/McpServerSpike.scala`, run only under `-Dankka.spikes=on`, against `-Dankka.mcp.spike.url`: `initialize`, list, and call `echo` on the reference "everything" server (`npx -y @modelcontextprotocol/server-everything streamableHttp`). Forward both properties to the forked test JVM in `build.sbt`'s `Test / javaOptions` — an unforwarded switch runs nothing and reports green. Run it once and record the result; also record what the official Java SDK depends on (R13's stated reason).

### Tools at start

- [X] T050 [US4] Tests first: create the fixture `TKT/McpAgent.scala` (agent `helper` listing the server `tickets` by name, with one own tool `search`) and `TKT/McpAgentSuite.scala`, one case per scenario of `mcp-servers.feature` not held elsewhere: tools offered under the server's name with the server's description and schema (read `model.lastRequest.tools`); a call is made on the server; a server's error reaches the model as the tool's error; a server that cannot be reached fails the start naming the server and the agent; an own tool with the same name is offered beside it; a tool gained after start is not offered; a tool removed after start fails the call with the server's error; the address is taken from `ANKKA_MCP_TICKETS_URL` when set; a server with no address fails the start naming the variable. With a second fixture, an autonomous agent listing `tickets`: "an autonomous agent is offered an MCP server's tools" (the tools are in an iteration's model request beside `complete_task`).
- [X] T051 [US4] Create `MCP/McpTools.scala`: for one agent, connect to each server, read its tools and build a `FunctionTool` for each with `origin = Mcp(server)`, the server's approval, and an invoker that calls the client; refuse at start a whole name longer than 128 characters, naming it. In `AGENT/AgentRuntime.scala` do this in `start` before the agent is hosted, for request agents and autonomous agents, reading variables through a function `String => Option[String]` (default `sys.env.get`) the runtime is built with; add the tools to every effect's tools in the host (never to a judgment's effect) and to an autonomous agent's tools. In `TK/AnkkaTestKit.scala` add `variables: Map[String, String] = Map.empty` to `start` and carry it across `restartService`.
- [X] T052 [US4] The credential: in `TKT/McpAgentSuite.scala`, "the platform sends an MCP server the credential its agent lists for it" (assert the header on every request the server saw, and that a second scripted server saw none), "a service whose agent takes a credential from a variable that is not set does not start" (naming the variable, the server and the agent), and "an MCP server's credential is shown in no trace" (assert the tool call's span is there first, then that nothing in the recorded trace or the captured log reads as the value). Implement in `MCP/McpTools.scala` and `McpClient.scala`: values read once at start, held only by the client. And a server that refuses the credential fails the start naming the server.
- [X] T053 [US4] Approval per server: in `TKT/McpAgentSuite.scala`, "every tool of an MCP server that requires approval waits for a decision" (the server saw no `tools/call`; after `decide` it saw one). A case that an autonomous agent's call to that server waits and uses none of its budget. A case that a server declaring a time limit in a service with no `TimerRuntime` fails the start naming it.
- [X] T054 [US4] V6, then the service form: in `TKT/AgentServiceCallSuite.scala`, an ankka service whose HTTP endpoint answers the four MCP methods (as plain JSON, and once as an event stream to show `HttpServiceClients` returns it whole), with an ACL admitting only the agent's service — `mcp-servers.feature` "the platform connects to an MCP server that is a service as the agent's service". Implement `ServiceTransport` in `MCP/McpTransport.scala` over `context.services(...).request("POST", path, …)`.

### Result guardrails

- [X] T055 [P] [US4] Tests first, in `AGENTT/judgment/GuardrailsSuite.scala`: `Guardrails.check` with `Direction.Result` calls `checkResult`; a guardrail that defines none allows; `Guardrail.forbidding` refuses a matching result; a judged guardrail with `onResult` rules asks its provider, counts what it spent and throws `GuardrailCheckFailed` when it cannot decide.
- [X] T056 [US4] In `AGENT/AgentEffect.scala` add `checkResult` to `Guardrail` (and to `forbidding`); `Direction.Result` in `AGENT/Guardrails.scala`; `onResult(rules*)` in `AGENT/judgment/JudgedGuardrail.scala`. In `AGENT/ToolRunner.scala`, for a tool whose origin is an MCP server and whose answer is not an error, run `Guardrails.check(resultGuardrails, text, Direction.Result, judgments, spent)`; a refusal replaces the result with an error result naming the guardrail and its reason, and the refused text is dropped. Both loops pass their agent's result guardrails and their `Spent`. T055 passes.
- [X] T057 [US4] In `TKT/McpAgentSuite.scala`, the four result-guardrail scenarios of `mcp-servers.feature`: a refused result reaches neither the model's next request nor the session's history, and the model is told an error naming the guardrail; a result let through reaches the model as given; an own tool's result is not checked by a guardrail that refuses everything; with no result guardrail the server's text reaches the model unchanged. And "a result guardrail keeps an MCP server's result from an autonomous agent's model", plus a case that a result guardrail which cannot decide is a failed iteration there and `Unavailable` for a request agent.
- [X] T058 [US4] `sbt 'agent/test' 'core/testOnly *PlatformVariablesSuite' 'testkit/testOnly *McpAgentSuite *AgentServiceCallSuite *ApprovalSuite'` passes. Show it can fail: skip the result check in `ToolRunner`; the withholds case goes red; put it back.

**Checkpoint**: every story holds for a Scala service.

---

## Phase 8: Every language — the protocol, the sidecar and the SDKs

**Purpose**: `languages.feature`. The sidecar hosts the Scala loops, so this phase is declaration,
translation and the client's calls. Depends on Phases 3 to 7.

### The protocol and the sidecar

- [X] T059 [US1] Edit `PROTO/discovery.proto`, `agent.proto` and `client.proto` per `protocol.md`, with the next free field numbers, and choose the version: 1.7, or 1.8 if `WireProtocol.Version` on `main` is already 1.7. Apply the bump checklist at the end of `protocol.md` (the Scala constants and their suites, `protocol/README.md`). Run the three SDK copy scripts.
- [X] T060 [P] [US4] In `RS`: after `scripts/proto.sh`, name the new fields at their defaults in every exhaustive message literal (`sdks/rust/ankka/src/components/agent.rs:290`, `:466`; `components/autonomous.rs:508`; `effects/agent.rs:118`; `testkit/kinds.rs`; `tests/autonomous.rs:319`) and add a `_` arm where a `match` over a reply now has a new case; bump `PROTOCOL_VERSION`. `cd sdks/rust && cargo test --workspace` passes. No export, import or API is added.
- [X] T061 [US1] Tests first, in `SCT/ProtocolSuite.scala` and the sidecar's discovery cases: a tool's `approval` and its limit are read; a server's name, a name twice, a header's variable and a tool named `mcp__…` are each a problem in discovery's report; a result guardrail declared twice is a problem; the version assertion is the new one.
- [X] T062 [US1] In `RT/remote/Conversation.scala` add `GuardrailStage.Result` and an optional tool name to `checkGuardrail`; implement in `SC/GrpcConversation.scala` and `SC/wasm/WasmConversation.scala` (a module is never asked: it declares none); translate in `SC/Translate.scala`. In `SC/RemoteAgent.scala` and `RemoteAutonomousAgent.scala`: a declared tool's `approval` becomes the `FunctionTool`'s; `mcp_servers` become the descriptor's `mcpServers`; each name in `result_guardrails` becomes a `Guardrail` whose `checkResult` calls `checkGuardrail` with `Result`, capturing the session at plan time as the existing guardrails do. In `SC/Discovery.scala` add the problems of T061. T061 passes; `RemoteAgentSuite` and `RemoteAutonomousAgentSuite` gain a case each for a waiting tool and a blocked result against the scriptable process double.
- [X] T063 [US1] In `SC/ClientLogic.scala` and `ClientService.scala`: `invoke` answers the `approval` case when the reply's metadata says so; `invokeStream` ends with the approval token on `StreamAwaiting`; `decide` for an agent (through `ankka:decide`, naming the handler) and for an autonomous agent (`HostProtocol.Decide`), a refusal as an `Error` in the reply. Cases in the sidecar's client suite.
- [ ] T064 [US1] V7: read how a process's stream route sends frames to the sidecar (`PROTO/endpoint.proto` and `SC`'s endpoint host). If a frame cannot carry an event's name, add an optional name to it in this version and marshal it as `SseEvent`; add a case to the sidecar's endpoint suite.
- [X] T065 [US1] In `CONF/ConformanceReference.scala` add an agent `approver` (a tool with approval, the server `tickets` declared by name with approval off and a second server `guarded` with approval on, one result guardrail refusing a known phrase) and an autonomous agent's tool with approval, with the routes that call `ask`, `decide`, the autonomous `decide` and `state`; add the component ids to `ConformanceReference.ComponentIds`. In `CONF/ConformanceSuite.scala` start `TestMcpServer` before the target and give its address as `ANKKA_MCP_TICKETS_URL` and `ANKKA_MCP_GUARDED_URL`; add the `approval.*`, `auto.approval.*` and `mcp.*` cases of `protocol.md`, guarded by `onlyForProcesses()` for a module. `sbt 'sidecar/testOnly *ConformanceSuite -- *approval.*'` and `-- *mcp.*` pass in process — and print the cases they ran.

### Python (independent of TypeScript)

- [X] T066 [P] [US1] Tests first in `PY/tests/`: a tool's `approval` and a server's fields render into discovery; a bad server name, a bad header variable and a tool named `mcp__…` are refused where the class is registered; `ask` returns `Answered` or `AwaitingApproval`; `call` raises `ApprovalAwaited`; `decide` sends `Decide` and reads its reply; a runtime that answers `UNIMPLEMENTED` raises the "needs protocol" error; the unit kit pauses at a tool with approval, goes on at `decide`, offers scripted MCP tools and runs a result guardrail; an old `agent-state.json` and the new fixtures decode.
- [X] T067 [US1] In `PY/src/ankka/`: create `approvals.py` (`Approval`, `ApprovalRequest`, `Answered`, `AwaitingApproval`, `ApprovalAwaited`) and `mcp.py` (`McpServer`, `ResultGuardrail`); `agent.py` and `autonomous.py` gain `Tool(approval=…)`, `mcp_servers`, `result_guardrails` and their rendering in `to_component()`; `server.py` answers `CheckGuardrail` with `RESULT` from `result_guardrails`; `client.py` gains `ask`, `decide`, `stream_parts` on the agent calls and `decide` on the autonomous calls, and `AgentState.awaiting` and the two notifications; `service.py` `PROTOCOL_VERSION`; `testkit/unit.py` per `sdk-apis.md`. `uv run pytest -q && uv run mypy` pass.
- [X] T068 [US1] In `PY/examples/shopping_cart/conformance.py` add the `approver` agent, the autonomous tool and the routes of T065. `ANKKA_CONFORMANCE_ONLY='*approval.*' uv run conformance` and `'*mcp.*'` pass, and each run's output names the cases it ran.

### TypeScript (independent of Python)

- [X] T069 [P] [US1] Tests first in `TS/test/`: the cases of T066 in the SDK's own shapes.
- [X] T070 [US1] In `TS/src/`: create `approvals.ts` and `mcp.ts`; `handlers.ts` `tool(…, { approval })` and `resultGuardrail`; `agent.ts`, `autonomous.ts` and `spec.ts` for `mcpServers`, `resultGuardrails` and discovery; `server/agent.ts` for the `RESULT` stage; `client.ts` for `ask`, `decide`, `streamParts`, the autonomous `decide`, the state and notifications; `PROTOCOL_VERSION`; `testkit/kinds.ts`; exports in `index.ts`. Erasable syntax only. `npm run proto && npm run typecheck && npm test` pass on Node 22 and 24.
- [ ] T071 [US1] In `TS/examples/shopping-cart/conformance.ts` add the reference of T065. `ANKKA_CONFORMANCE_ONLY='*approval.*' npm run conformance` and `'*mcp.*'` pass, naming the cases they ran.

### The Rust reference

- [X] T072 [US4] In `RS/examples/shopping-cart/src/conformance.rs` nothing is added; confirm `discovery.lists-every-component` handles a module's shorter list (it compares an exact set — give the target its own expected ids, or guard the new ids with `onlyForProcesses()`), and `./conformance.sh` passes, printing both shapes.

**Checkpoint**: `languages.feature` holds for Scala, Python and TypeScript, except the service-call rows beyond Scala.

---

## Phase 9: User Story 3 — a Python or TypeScript tool calls another service (when 025 is on `main`)

**Purpose**: the second half of User Story 3. Blocked on 025-polyglot-service-client's `Request`
call and SDK clients.

- [X] T073 [US3] Confirm 025 is on `main` (`grep 'rpc Request' PROTO/client.proto`). If it is not, skip T074, leave the Python and TypeScript rows of `languages.feature` "a tool's call to another service is admitted in every language" unbuilt, and say so on `DOCS/reference/limitations.md` in T078.
- [X] T074 [US3] Add the conformance case `svc.tool-calls-service` to `CONF/ConformanceSuite.scala`: the `approver` agent's tool calls a scripted service through the SDK's service client, and the service saw the call. Add the tool to the Scala, Python and TypeScript references.

---

## Phase 10: Documentation, and the whole build

- [X] T075 [P] In `DOCS/build/agents.md` and `DOCS/build/autonomous-agents.md`: a section each on approvals (declaring, `ask` and `decide`, the endpoint that serves them, the time limit and `TimerRuntime`, at most once and at least once, who decided) and on the service client in a tool, with samples included from marked regions of `TKT/ApprovalAgent.scala`, `TKT/ChatEndpoint.scala`, `TKA/Answerer.scala` and the SDKs' conformance references (`// docs:start …`). Pages stand alone: no feature number, no "see above".
- [X] T076 [P] Create `DOCS/build/mcp-servers.md`: listing a server, its address and `ANKKA_MCP_<SERVER>_URL`, the tool names, a credential by header and variable, approval per server, result guardrails, what fails the start, testing with `TestMcpServer`; samples from `TKT/McpAgent.scala`. Add it to `mkdocs.yml`'s `nav` and to the `pages:` list of at least one skill under `SKILL`.
- [X] T077 [P] `DOCS/reference/limitations.md`: replace "There are no MCP tools" with what is not covered (stdio and other transports, resources and prompts, OAuth, a credential given while the service runs, a streamed decision, a module's agent, and T073's rows if unbuilt). `DOCS/reference/configuration.md`: the two timeouts and the `ANKKA_MCP_` variables, with prose naming each (the coverage check requires it). `DOCS/reference/glossary.md`: the terms of `GLOSSARY.md`'s *Agents* section. `DOCS/reference/sidecar-protocol.md`: the version in both places and the history paragraph. `DOCS/reference/python-sdk.md`, `typescript-sdk.md`, `scala-sdk.md`: the new surface.
- [X] T078 `just docs-sync && just docs` pass; the generated protocol rows and the rendered skills (`marketplace/plugins/ankka/skills/`, `ankka.g8/src/main/g8/.claude/skills/`) are regenerated and committed with every `$` escaped in the template's copy.
- [X] T079 [P] In `CLAUDE.md`: under *Agents*, a paragraph on approvals (the suspended turn, resuming by running the handler again, the instance's approvals and `SettleCalls`, expiry as a timed action) and one on MCP servers and result guardrails; under *Traps*, what implementation found. Update the `specs-from` note if the features check's status changed.
- [ ] T080 Audit against R21: for each of the 61 scenario references in `spec.md`, name the test that holds it (a table at the end of `research.md`); `just features` finds nothing in `features/agents/` or spec 029.
- [ ] T081 `sbt scalafmtAll scalafmtSbt`, then `caffeinate -i sbt buildAll`; then `cd sdks/python && uv run pytest -q && uv run mypy && uv run conformance`, `cd sdks/typescript && npm run typecheck && npm test && npm run test:slow && npm run conformance`, `cd sdks/rust && cargo test --workspace && ./conformance.sh`. Warning-free.
- [ ] T082 `quickstart.md` step 9 by hand against `docker compose up -d`, and step 6 against the real MCP server; record both in `research.md` "Verified during implementation".

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** blocks nothing but T002's amendment: if a colon is accepted, pause before Phase 7.
- **Phase 2** blocks Phases 3, 4 and 7.
- **Phase 3 (US1)** and **Phase 4 (US2)** are independent of each other after Phase 2.
- **Phase 5** needs both.
- **Phase 6 (US3, Scala)** needs only T006 (for the span) and can start beside Phase 3.
- **Phase 7 (US4)** needs Phase 3 for a server's approval, Phase 5 for its time limit, and Phase 6 for the service transport and the span in the credential's trace case.
- **Phase 8** needs Phases 3 to 7. Within it the protocol and sidecar tasks come first; Python and TypeScript are then independent of each other.
- **Phase 9** needs Phase 8 and 025.
- **Phase 10** needs everything built.

### Within a story

Tests first and failing, then the entity or record, then the loop, then the host, then the
client. A compatibility pin is written before the event it pins.

### Parallel opportunities

- T002 beside T001. T005 and T007 beside T003–T004.
- T021–T024 together (four files).
- T043 and T044 together; T049 beside T050; T055 beside T050–T054.
- T060 beside T061–T065. T066–T068 beside T069–T071.
- T075, T076, T077 and T079 together.
- Phases 3, 4 and 6 by three people, or three sessions, once Phase 2 is done.

### Parallel example: after the protocol is in

```text
Session A: T066 → T067 → T068   (Python)
Session B: T069 → T070 → T071   (TypeScript)
Session C: T060, T072           (Rust literals and its conformance run)
```

---

## Implementation Strategy

**MVP**: Phases 1 to 3. A Scala request agent's tool waits for a person and survives a restart —
the capability the domain plan's confirm-before-write needs. It can be demonstrated with
`quickstart.md` step 9.

**Then, each a deliverable increment**: Phase 4 (autonomous agents); Phase 5 (the time limit);
Phase 6 (a tool's service call); Phase 7 (MCP servers, credentials, result guardrails); Phase 8
(Python and TypeScript); Phase 10.

**Stop and check with the user** at: T002 if a colon is accepted; T019 if the stream route needs
its own name; T025 if a passivated waiting instance does not come back on `decide` (it must then
stay in memory while it waits); T042 if the k3s case cannot be built; T073 if 025 is not on
`main` when Phase 8 is done.

## Notes

- The scripted MCP server and the client were written together and agree by construction; T049 is
  the check that is independent of both.
- A "nothing happened" assertion needs a "something happened first" beside it: a held call count
  after it reached one, an absent value after the span that would hold it was found.
- A conformance filter without its leading `*` matches nothing and reports green; read what each
  run says it ran.
- A new event case goes at the end of its enum, in the pinned file, and in the suite's
  every-case assertion — in that order, or the pin rewrites itself and still fails.
- Commit at each checkpoint.
