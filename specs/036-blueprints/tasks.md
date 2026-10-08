# Tasks: Blueprints — Agents and Their Interactions as Data, Run by the Platform

**Input**: Design documents from `/specs/036-blueprints/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
code into a test before the code that relies on it. The scenarios are in `features/blueprints/` and
`features/documentation/blueprints.feature`, and the sample's in `samples/research-digest/features/`;
where a task says "case", it means a scenario run by the named `GherkinSuite`, or a `test(...)` in
the named suite named for the scenario it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (register a blueprint and have it checked), US2 (start a run and read what it
  did), US3 (the patterns), US4 (runs on a schedule), US5 (tools know their run; others follow
  runs), US6 (the research digest sample)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `SDK` =
`modules/sdk/src/main/scala/…/sdk`; `RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`;
`AGENT`/`AGENTT` = `modules/agent/src/{main,test}/scala/…/agent`; `AUTO` = `AGENT/autonomous`;
`BP`/`BPU` = `AGENT/blueprint`, `AGENTT/blueprint`; `TK`/`TKT` =
`modules/testkit/src/{main,test}/scala/…/testkit`; `BPT` = `TKT/blueprint`; `JOURNAL` =
`modules/testkit/src/test/resources/journal`; `APIT` =
`controlplane-api/src/test/scala/…/controlplane/api`; `RD` = `samples/research-digest`; `MAP` =
`samples/multi-agent-planner`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`. "R*n*" is a section of
`research.md`; "V*n*" an item of its *Verify first* list, numbered in order (V1 field order, V2 the
timer store's `now`, V3 a consumer over a platform entity, V4 an approval resumed through the ask
agent).

Work on the branch `036-blueprints`. Every `sbt` command below takes `-Dankka.cluster.tests=off`.
When a verify-first task shows its claim false, stop and say so before building on it; when it
holds, add a line to a `## Verified during implementation` section at the end of `research.md`.

---

## Phase 1: Setup — the ground everything stands on

**Purpose**: a green baseline, and the facts the plan read from code.

- [X] T001 Record the baseline: `sbt 'agent/test' 'testkit/testOnly *AutonomousAgentSuite *ResumePointSuite *EventCompatibilitySuite *TimerSuite *WorkflowSuite *JudgmentAgentSuite *ApprovalSuite'` and `sbt multiAgentPlanner/test` pass before any change. Run `git add -N features/blueprints features/documentation/blueprints.feature samples/research-digest specs/036-blueprints && python3 .github/ci-coverage.py` and confirm every new path is claimed by a filter.
- [X] T002 [P] V1: in `BPU/CanonicalSuite.scala`, encode a case class with fields in declaration order and a `Map` with shared codec config and assert the field order is declaration order and the map's is not guaranteed; record which in `research.md` (R3).
- [X] T003 [P] V2: in `RTT/TimerStoreClockSuite.scala` (testkit's Postgres if needed: put it in `TKT/TimerStoreClockSuite.scala`), insert a timer due at `t`, call `TimerStore.due(t - 1s)` and `TimerStore.due(t)` and assert the timer is returned only by the second; confirms `due` reads only its `now` (`RT/TimerRuntime.scala:126-129`).
- [X] T004 [P] V3: in `TKT/PlatformEntityConsumerSuite.scala`, register a consumer declared outside the `ankka` package over `ChangeSource.eventsOf(TaskEntity)` with `ProjectionRuntime()`, run one task, and assert the consumer receives `Created` and `Completed` in order. If it does not, stop: FR-026 needs a different seam.

**Checkpoint**: baseline green; V1–V3 confirmed or the plan is paused. ✅ 2026-10-07: all three hold; see research.md, *Verified during implementation*.

---

## Phase 2: Foundational — values, shapes, flags and the clock

**Purpose**: what every story stands on. Blocks US1–US6. Nothing here changes behaviour an
existing suite can see.

- [X] T005 [P] `ShapeSuite` in `BPU/ShapeSuite.scala`: a shape decodes from JSON Schema text with `type`, `properties`, `required`, `items`, `enum`, `description`; any other keyword is refused naming it; `check` returns every non-conforming path (missing required field, wrong type, an item of an array, a value outside an enum) and none for a conforming value.
- [X] T006 [P] Implement `Shape` (R9) in `BP/Shape.scala`: the subset as a sealed value, its decoder and encoder with the shared codec config, `check(json): Vector[ShapeProblem(path, message)]`, `isArray`, `field(name)`; make T005 pass.
- [X] T007 [P] Blueprint values in `BP/Blueprint.scala` per data-model.md: `Blueprint`, `Worker`, `Step`, `Pattern` (`Ask`, `Work`, `ForEach`, `Gather`, `Judge`, `Critique`), `Verdict` (`Critic`, `Judgment`), `Schedule` (`Every`, `Weekly`, `zone`, `catchUp: CatchUp` with a string codec `one`/`each`), the builder in contracts/scala-api.md, `Blueprint.fromJson`, `fromResource`, `canonical` (sorted pairs for any map, per T002's result) and `digest` (SHA-256 hex of `canonical`).
- [X] T008 [P] Extend `BPU/CanonicalSuite.scala`: a blueprint written by the builder and the same read from JSON with fields in another order have equal `canonical` and `digest`; changing one worker's instructions changes the digest; `canonical` round-trips through `fromJson`.
- [X] T009 [P] Add `platform: Boolean = false` to `ViewDescriptor` in `SDK/View.scala` and to `AgentDescriptor` in `AGENT/Agent.scala`, and to `AutonomousAgentDescriptor` in `AUTO/AutonomousAgent.scala` if it has none; read them where `TopologyJson` reads the entity's (`TopologyJson.scala:252`). Existing topology suites stay green.
- [X] T010 [P] Per-effect step bound (R7): add `maxToolCallSteps(n)` to `AgentEffect` in `AGENT/AgentEffect.scala` (not stored), and make `AgentLoop.step` (`AGENT/AgentLoop.scala:586-597`) and the streaming loop use the effect's bound when set. Case in `TKT/AgentSuite.scala`: an effect bound of 2 fails a turn whose model asks for three tool calls, with the existing "exceeded" message.
- [X] T011 A clock for timers (R14): `TimerRuntime(pollInterval, clock: java.time.Clock = Clock.systemUTC)` in `RT/TimerRuntime.scala`; pass it to `DatabaseTimerScheduler` (`due_at` at `:91`), `TimerStore` backoff (`:138`) and `TimerSweeper.runBatch` (`RT/TimerSweeper.scala:118`). No other `Instant.now()` in those files.
- [X] T012 [P] `MovableClock` in `TK/MovableClock.scala`: `at(instant)`, `moveTo`, `moveBy`, thread-safe. Case in `TKT/MovableClockTimerSuite.scala` (its own kit, since `TimerSuite`'s runs on the wall clock): a timer scheduled for one hour ahead fires within the poll interval after the clock is moved two hours, and not before.
- [X] T013 `BlueprintRegistry` (R6) in `BP/BlueprintRegistry.scala`, built once at start from a `BlueprintContext` (component client, service clients, secrets, whether the service has timers): `tools(FunctionTool*)`, `mcpServers(McpServer*)`, `models(name -> ModelProvider*)` (`default` is the runtime's default model), `guardrails(name -> Guardrail*)`, `questions(Question[?]*)`, `carrying(Blueprint*)`; duplicate names refused where built, naming them. MCP servers are connected at start with `McpTools.connect` as an agent's are (`AGENT/AgentRuntime.scala:204-233`), and their tools enter the registry under their `mcp__<server>__<tool>` names.

**Checkpoint**: `sbt 'agent/testOnly *ShapeSuite *CanonicalSuite' 'testkit/testOnly *AgentSuite *TimerSuite *TopologySuite'` green. ✅ 2026-10-07: 12 and 179 passed, with `MovableClockTimerSuite`, `ApprovalSuite` and the topology suites.

---

## Phase 3: User Story 1 — register a blueprint and have it checked (Priority: P1) 🎯 MVP

**Goal**: a service registers blueprints, holds versions that never change, and is refused with every problem named.

**Independent test**: `BlueprintFeatures` and `BlueprintCheckFeatures` pass (spec, US1).

### Tests first

- [X] T014 [P] [US1] `BPT/BlueprintCheckFeatures.scala`: `GherkinSuite` over `features/blueprints/checking.feature` (in `testkit`'s tests, since `agent`'s cannot see `GherkinSuite`), calling `BlueprintCheck` directly with the registry of the feature's background and no runtime; and `BPU/BlueprintCheckSuite.scala` with a property case that a blueprint with N problems (N = 1 … the number of checks) is refused with exactly N (SC-003).
- [X] T015 [P] [US1] `BPT/BlueprintFeatures.scala`: `GherkinSuite` over `features/blueprints/registering.feature` with `AnkkaTestKit` and `AgentRuntime.descriptors`; the "carries" scenarios start a service whose registry carries the blueprint, and the second restarts it with `restartService()`. "Nothing is held" reads the entity, not the client's answer (R20).

### Implementation

- [X] T016 [US1] `BlueprintCheck` in `BP/BlueprintCheck.scala`: `check(blueprint, registry): (Vector[Problem], Vector[Note])` covering every rule of FR-004 in one pass (unknown tool, model, guardrail, question, pattern; a step reading a later or unknown step; a for-each over a non-array shape; a critique without a verdict, or a judgment verdict naming a question that is not yes/no; a worker with no budget or a non-positive one; a schedule in an unknown zone or in a service without timers; a gather `chosenBy` that is not an array of strings; duplicate worker or step names; a step named `input`); the note for a worker no step uses. Make T014 pass.
- [X] T017 [US1] `BlueprintEntity` (`ankka-blueprint`, platform) in `BP/BlueprintEntity.scala` per data-model.md: state, `VersionRegistered`, `ScheduleAdvanced`, `ScheduleStopped`, commands `register`, `advance-schedule`, `stop-schedule`, queries `get` and `version`, serializers `blueprint-record`, `blueprint-event`, `blueprint-register`; equal digest answers the current version (R2).
- [X] T018 [US1] `agents.blueprints` (`BlueprintCalls`, reached from the `AgentRuntime`, since only it holds the registry the check needs; see R23) in `BP/Calls.scala`: `register` (check in the caller, then the command; a refusal is `CommandError(BadRequest)` carrying every `Problem` as JSON in its message and in a typed field the client decodes), `versions`, `version`; extension methods on `ComponentClient` as `client.tasks` is (`AUTO/Calls.scala`).
- [X] T019 [US1] `AgentRuntime.withBlueprints(ctx => registry)` in `AGENT/AgentRuntime.scala`: build the registry once at start from a `BlueprintContext`; add `BlueprintEntity` to `AgentRuntime.descriptors`; at start, register every carried blueprint, refusing to start with the problems named when one is refused; warn when the platform descriptors are missing, as for tasks. Make T015 pass.
- [X] T020 [P] [US1] Pin `JOURNAL/blueprint-events.json` (a `VersionRegistered` and a `ScheduleAdvanced`) and read it in `TKT/EventCompatibilitySuite.scala`.

**Checkpoint**: US1 complete and demonstrable alone (quickstart §1, §2 first part). ✅ 2026-10-07: `BlueprintFeatures` 7, `BlueprintCheckFeatures` 10, `BlueprintCheckSuite` 4, `BlueprintCompatibilitySuite` 3; `blueprint-events.json` pinned.

---

## Phase 4: User Story 2 — start a run and read what it did (Priority: P1)

**Goal**: a run of ask steps carried out in order, held, readable, resumable, cancellable, budgeted, waiting on approvals.

**Independent test**: `RunFeatures` and `BlueprintRestartSuite` pass (spec, US2).

### Tests first

- [X] T021 [P] [US2] `BPT/RunFeatures.scala`: `GherkinSuite` over `features/blueprints/runs.feature` with a scripted model keyed by instructions (`whenUserAsks`), `ProjectionRuntime()` for listing, and `TimerRuntime` absent; the background's blueprint `brief` has three ask steps. The approval scenario uses a registry tool with `requiresApproval` and decides through the session's `decide`.
- [X] T022 [P] [US2] `BPT/BlueprintRestartSuite.scala` (SC-002): a three-step run restarted once in each step completes; every model call recorded before a restart is made exactly once (count `model.requests` per instructions). The suite waits on a consumer's record of the run's end, never by reading the run, so a read cannot wake it (R20).
- [X] T023 [P] [US2] V4 in `BPT/AskApprovalSuite.scala`: an ask turn whose tool requires approval suspends, the handler of `ankka-blueprint-ask` is run again from the recorded payload on `decide`, and the turn ends with the tool run once.

### Implementation

- [X] T024 [US2] `RunEntity` (`ankka-run`, platform) in `BP/RunEntity.scala` per data-model.md: `RunStatus` with a string codec (`running`, `waiting-for-decision`, `completed`, `failed`, `cancelled`), `RunRecord`, `StepRecord`, events, commands `start` (input checked against the version's input shape; an existing id with the same blueprint and input answers itself, a different one is `Conflict`, a caller's id starting `schedule:` is `BadRequest`), `record` (host only, refused after `Ended`), `cancel`, query `get`; serializers `run-record`, `run-event`, `run-start`, `run-record-event`.
- [X] T025 [US2] `AskAgent` (`ankka-blueprint-ask`, platform) in `BP/AskAgent.scala` (R5): one handler `turn` taking `WorkerTurn`; build the effect from the payload alone: model by name from the registry, system message from instructions, user message from the input (and, on a retry, the shape problems), tools by name, guardrails by name, `MemoryProvider` for the session, `maxToolCallSteps(budget)`; reply as JSON text.
- [X] T026 [US2] `RunContext` in `BP/RunContext.scala` (R15): `RunRef`, a thread-local, `current`, `within(ref)(body)`, `fromSession(sessionId)`; set around each tool invocation in `AgentLoop`'s tool runner from the session id. (Its own scenarios are US5's; it is needed here so ask turns are attributable.)
- [X] T027 [US2] `RunHost` (`ankka-run-host`) in `BP/RunHost.scala` (R1): sharded per run id with remember-entities on the event sourced store, its own stop message, an actor shell with one worker virtual thread, passivating itself when the run has ended; started by `start` and by remember-entities after a crash.
- [X] T028 [US2] `RunWorker` in `BP/RunWorker.scala`: re-read the run at every boundary; for each step from the first not ended: record `StepStarted`; for an ask step, read the session first and take an ended turn's answer without a call (R18), otherwise call `AskAgent.turn` on `run:<runId>:<step>:<worker>`; check the answer's shape and send problems back to the worker within its budget (FR-013); on `AwaitingApproval` record `WaitingForDecision` and poll the session, backing off from 1 s to 30 s, until the turn has ended, then `DecisionReceived`; when the run ends with requests awaiting a decision, refuse each on its session as the platform with the note "run <id> ended" and record `ApprovalsRefused` (R16); record `StepEnded` with result, sessions and usage read from the session history; on failure record `Ended(failed, "<step>: <reason>")`. Before each turn and step: cancel requested → `Ended(cancelled)`; run budget (model calls summed from sessions) or deadline passed → `Ended(failed, …)` (R17). The time limit is an actor timer re-armed from the record; when it fires the run ends as failed whatever the worker is doing, including while it waits for a decision, and the refusal of waiting requests above follows.
- [X] T029 [US2] `agents.runs` in `BP/Calls.scala` (as `agents.blueprints`, R23): `start`, `get`, `await` (polls `get` every 250 ms as `forTask.await` does), `cancel`, `list`; `RunSnapshot`, `StepSnapshot`, `RunSummary`.
- [X] T030 [US2] `RunsView` (`ankka-blueprint-runs`, platform) in `BP/RunsView.scala` (R11): a row per run from `RunEntity` events; `client.runs.list(name)` queries it, and is refused with a reason when no `ProjectionRuntime` is registered.
- [X] T031 [US2] Register `RunEntity`, `AskAgent`, `RunsView` in `AgentRuntime.descriptors` and start `RunHost` sharding in `AgentRuntime.start`, beside the autonomous hosts. Make T021–T023 pass.
- [X] T032 [P] [US2] `TK/AnkkaTestKit.scala`: `awaitRun(runId, within)`, and `restartService(extensions)` for a restart with different extensions on the same database (T044).
- [X] T033 [P] [US2] Pin `JOURNAL/run-events.json` (every run event) and read it in `TKT/EventCompatibilitySuite.scala`.

**Checkpoint**: US1 and US2 work together: a blueprint of ask steps registered and run (quickstart §2). ✅ 2026-10-07 (review, R24–R26): steps are scheduled as a graph, a step is an action, an over and an until, and the call step is built with `CallStepSuite`; `runs.feature` gains *steps that read only what has ended are carried out at once*. ✅ 2026-10-07: `RunFeatures` 23 scenarios, `BlueprintRestartSuite` (SC-002), `AskApprovalSuite` (V4), `RunCompatibilitySuite`; `run-events.json` pinned. Departures from the tasks as written: `RunContext` is bound to each tool by the ask agent (the tool runs after the handler returns, on the agent's thread), not set in `AgentLoop`; the time limit is checked at every boundary and in every wait rather than armed as an actor timer; `TestModelProvider.respondWhen` gives a scripted response worked out when the rule fires.

---

## Phase 5: User Story 3 — the patterns (Priority: P1)

**Goal**: work, for-each, gather, judge and critique steps.

**Independent test**: `PatternFeatures` passes (spec, US3).

### Tests first

- [X] T034 [P] [US3] `BPT/PatternFeatures.scala`: `GherkinSuite` over `features/blueprints/patterns.feature` with a scripted model keyed by instructions and item text, and a `TestJudgmentProvider`; the "at once, up to the limit" case counts concurrent turns with a tool that records entry and exit.
- [X] T035 [P] [US3] Pin `JOURNAL/task-created-with-definition.json` and read it, and the existing task journals (no definition), in `TKT/EventCompatibilitySuite.scala`.

### Implementation

- [X] T036 [US3] Per-task definition (R4): `TaskDefinition` in `AUTO/Task.scala`; `definition: Option[TaskDefinition] = None` on `TaskEntity.Create`, `TaskEvent.Created` and `TaskRecord` in `AUTO/TaskEntity.scala`; `TaskBuilder.withDefinition` in `AUTO/Calls.scala`, `private[ankka]`.
- [X] T037 [US3] `AUTO/AutonomousAgentHost.scala`: resolve per task, in `work`, the definition (the task's or the descriptor's), the tools (the agent's, or the registry's by name), the model (by name, or the descriptor's), the budget (the definition's, or the acceptance's), and build an `IterationLoop` for that task; `accepted(typeName)` (`:434`, `:706`) also admits any task carrying a definition when the agent is `ankka-blueprint-worker`. `AUTO/IterationLoop.scala`: set `RunContext` around each tool from the definition's run reference. Every autonomous suite stays green.
- [X] T038 [US3] `WorkerAgent` (`ankka-blueprint-worker`, platform) in `BP/WorkerAgent.scala`: an autonomous agent with no tools of its own and no task types, which works only tasks carrying a definition; register it in `AgentRuntime.descriptors`.
- [X] T039 [US3] Work steps in `BP/Patterns.scala`, called by `RunWorker`: create a task of the platform type `blueprint-step` carrying the worker's definition, the result shape and the run reference (the host builds the type's schema and shape rule per task from the definition, R4), run it on `ankka-blueprint-worker` under instance `run:<runId>:<step>`, await it; the task's iterations and rejected results are the platform's; the step's result is the task's. When the run is cancelled or ends while the task is in progress, cancel the task (`client.forTask(id).cancel`), so no iteration starts after (FR-012).
- [X] T040 [US3] The overs in `BP/Patterns.scala`, once for every action (R25): `Each` resolves `read` to an array, does the action once per item at most `limit` at once (sessions `run:<runId>:<step>:<worker>:<index>` for a turn), records `ItemEnded` as each ends and skips items already ended on resume; fails the step on a failed item unless `keepGoing`, when the result marks it. `Workers` does the action once per worker (or `Times` so many times, sessions `…:<n>`) at once and keeps every result with its worker; with `chosenBy`, resolve the read to a list of names and run only those among the step's workers, in the step's order, an empty list giving an empty result (R22). A call or a judge over `Each` repeats the handler or the judgment per item.
- [X] T041 [US3] Judge in `BP/Patterns.scala` (R8): `Judgments.ask(None, JudgmentState.fromJson(input), questions)` through the instance `AgentRuntime` passes to the host; the result is each answer with its probabilities as JSON; usage on the step's `judgmentUsage`.
- [X] T042 [US3] The until in `BP/Patterns.scala` (R10, R25), for an ask and for a work: the drafter's turns on one session `run:<runId>:<step>:<drafter>` (for a work, one task per round, each told the reasons), each round's reasons sent as the next user message; a critic verdict an ask turn with the `Verdict` shape on its own session; a judgment verdict one yes/no question with the draft as state; `RoundEnded` per round; after the last round fail the step with the last reasons, or keep the last draft marked `notPassed` when `keepLast`. Make T034 and T035 pass, including *a call step runs a handler with what it reads* and *a for-each step may drive work tasks*.

**Checkpoint**: every pattern; US1–US3 make the P1 MVP (quickstart §2). ✅ 2026-10-07: `PatternFeatures` runs the 18 scenarios of `patterns.feature`; the overs and the until are one `Patterns.scala`, composed around `once` for every action; a work step is a task of the platform type `blueprint-step` carrying its worker's definition, resolved per task by `BlueprintTasks.resolver` on the platform worker agent `ankka-blueprint-worker`.

---

## Phase 6: User Story 4 — runs on a schedule (Priority: P2)

**Goal**: due times in a zone, periods that meet, catch-up as the schedule says, one run per due time.

**Independent test**: `ScheduleSuite` and `ScheduleFeatures` pass (spec, US4).

### Tests first

- [X] T043 [P] [US4] `BPU/ScheduleSuite.scala`: due times for `Every(6.hours)`, `Every(1.day)` and `Weekly(SUNDAY, 20:00, Europe/London)`; across 25 October 2026 the local time stays 20:00 and the period is 169 hours; `missed(lastEnd, now)` lists every due time passed; `one` gives one period from the last end to the latest, `each` one per due time in order, and periods meet; a schedule's first period starts one cadence before its first due time.
- [X] T044 [P] [US4] `BPT/ScheduleFeatures.scala`: `GherkinSuite` over `features/blueprints/schedules.feature` with `TimerRuntime(pollInterval = 200.millis, clock = movableClock)`; the outage scenarios stop the service with the kit, move the clock, and start it again; the catch-up version scenario needs the service started again with a registry carrying version 2, so the kit gains `restartService(extensions)` (or the suite starts a second kit on the same database) and T032 adds it; the instances scenario uses `startPeer` for a second and third instance.

### Implementation

- [X] T045 [US4] Due times in `BP/Schedule.scala` (or beside `Schedule` in `BP/Blueprint.scala`): `next(after, schedule)`, `missed(lastEnd, now, schedule)`, `periods(lastEnd, missed, catchUp)`, pure, with `java.time` in the schedule's zone (R12, R13). Make T043 pass.
- [X] T046 [US4] `ScheduleTimer` (`ankka-blueprint-schedule`, platform timed action) in `BP/ScheduleTimer.scala`: timer `schedule:<blueprint>:<due epoch millis>`; on firing, read the blueprint, compute the missed due times under the current version's schedule with the runtime's clock, start each run under `schedule:<blueprint>:<due epoch millis>` with the period as input and the current version (R13), record `ScheduleAdvanced`, create the next occurrence's timer (R12).
- [X] T047 [US4] Registering a version (T017, T019): a version with a schedule creates the next occurrence's timer when none is pending, the first period starting at the last period end or, for a first schedule, one cadence before the first due time; a version without one deletes the pending timer and records `ScheduleStopped` (FR-024). A service carrying a scheduled blueprint without `TimerRuntime` is refused at start. Register `ScheduleTimer` in `AgentRuntime.descriptors`; take the clock from `TimerRuntime`. Make T044 pass.

**Checkpoint**: quickstart §3. ✅ 2026-10-07: `ScheduleSuite` (7) and `ScheduleFeatures` (10 scenarios) pass; the clock-change scenario now reaches the due time on 25 October, whose period is the one that crosses the change.

---

## Phase 7: User Story 5 — tools know their run; others follow runs (Priority: P2)

**Goal**: `RunContext` in tools; consumers over blueprint versions and runs.

**Independent test**: `FollowingFeatures` passes (spec, US5).

- [X] T048 [P] [US5] `BPT/FollowingFeatures.scala`: `GherkinSuite` over `features/blueprints/following.feature`; the tool records `RunContext.current`; the consumers are declared in the test's own package over `ChangeSource.eventsOf(RunEntity)` and `eventsOf(BlueprintEntity)` with `ProjectionRuntime()`.
- [X] T049 [US5] Make `RunEvent` and `BlueprintEvent` and their companions public API in `BP/RunEntity.scala` and `BP/BlueprintEntity.scala` (scaladoc saying which events a follower sees and in what order); check `RunContext` is set for work-step tools (T037) and outside any run is `None`. Make T048 pass.

**Checkpoint**: quickstart §4. ankka-reasoning's projection can be built on this. ✅ 2026-10-07: `FollowingFeatures` passes, 5 scenarios.

---

## Phase 8: User Story 6 — the research digest sample, and the planner as a blueprint (Priority: P3)

**Goal**: the sample of the spec's example, offline; SC-001.

**Independent test**: `sbt researchDigest/test` and `PlannerBlueprintSuite` pass (spec, US6, SC-001, SC-006).

- [X] T050 [US6] `build.sbt`: `researchDigest` project at `samples/research-digest`, `.dependsOn(sdk, runtime, http, agent, testkit % Test)`, `name := "sample-research-digest"`, `publish / skip := true`, no image; add it to `root`'s `.aggregate`.
- [X] T051 [P] [US6] `RD/src/main/scala/digest/PapersEntity.scala`: a key value entity per paper identifier holding the sources that found it and when it was first found; `RD/src/main/scala/digest/Tools.scala`: `keep_paper` (idempotent by identifier), `papers_found_between(from, to)`, and one `search_<source>` per source over its public interface (Europe PMC, bioRxiv, OpenAlex), each also reachable as a `TestMcpServer` in tests.
- [X] T052 [P] [US6] `RD/src/main/resources/blueprints/watch.json` (daily: gather over the sources, ask to keep entries) and `digest.json` (weekly Sunday 20:00 Europe/London, `catchUp: each`: ask for the period's papers, for-each reader, ask to relate, critique with the judgment verdict `names-a-paper`); `RD/src/main/scala/Main.scala` registering them with the registry and an HTTP endpoint to start and read runs.
- [X] T053 [US6] `RD/src/test/scala/digest/ResearchDigestFeatures.scala`: `GherkinSuite("features")` over the sample's features with scripted sources, model and judgment provider; make every scenario pass offline.
- [X] T054 [US6] Hold `features/blueprints/research-digest.feature`'s one scenario in `RD/src/test/scala/digest/SampleFeaturesPassSuite.scala` (the sample's project, which can run the sample's suite): run `ResearchDigestFeatures` with `ANTHROPIC_API_KEY` unset and a test registry holding only the scripted sources, so no tool can reach a network, and assert every scenario passed.
- [X] T055 [P] [US6] `.github/features-check.sh`: a second `speckit-bdd check` over `samples/research-digest` with its own glossary and features and no specs; `just features` runs both.
- [X] T056 [P] [US6] SC-001: `MAP/src/main/scala/planner/application/PlannerBlueprint.scala` (select as an ask whose result shape lists the specialists as an `enum`, consult as a gather chosen by `select.specialists`, summarise as an ask) and `MAP/src/test/scala/planner/PlannerBlueprintSuite.scala` running it with the planner's scripted model, asserting the same plan as `PlannerSuite` for every case but the fallback, which becomes a shape refusal the scripted model corrects (R22).

**Checkpoint**: quickstart §6 and §7. ✅ 2026-10-07: `researchDigest/test` 7 passed (the six scenarios and the root scenario that runs them); `PlannerBlueprintSuite` 5 passed.

---

## Phase 9: Documentation, and the whole build

- [X] T057 [P] `DOCS/build/blueprints.md` (guide; components `[agent, autonomous-agent]`; related agents, autonomous agents, multi-agent orchestration, judgments, timers): what a blueprint is and is not, the registry, each pattern with an included sample (`// docs:start` regions in the research digest and the test fixtures), runs and their status, schedules and catch-up, following runs, what resumes and what may run again (R18), the limits.
- [X] T058 [P] `DOCS/reference/limitations.md`: revise "Autonomous agents do not coordinate yet" (a work step gives a task its own definition; per-instance overrides remain absent outside blueprints), the judgments entry (a blueprint's judge or critique step asks one), and add "Blueprints": Scala only, no branching search, no person as a step of its own, an interrupted ask turn runs again.
- [X] T059 [P] `mkdocs.yml` nav (Build, after *Autonomous agents*) and `SKILL/ankka-agents/SKILL.md` `pages:` and description; `DOCS/build/multi-agent-orchestration.md` gains a short "or a blueprint" section beside "A workflow, or an autonomous agent".
- [X] T060 `APIT/BlueprintsDocumentationSuite.scala`: one test per scenario of `features/documentation/blueprints.feature`, reading the published pages as `ServiceCallsDocumentationSuite` does.
- [X] T061 Glossary: once T006 and T039–T040 have used them, settle **for-each step**, **work step** and **input shape** (remove *Proposed.* or rename across features, spec and glossary), and record the outcome in the spec's Clarifications.
- [X] T062 `just docs-sync && just docs`, `just features`, `sbt scalafmtAll scalafmtSbt`, then `caffeinate -i sbt -Dankka.cluster.tests=off buildAll`; record in `research.md`'s *Verified during implementation* what each verify-first item showed.
- [X] T063 [P] `DOCS/build/timers.md`: a handler's `timerContext.timers` and `timerContext.clock` (the sweeper's, which the testkit's `MovableClock` moves), and `AnkkaService.extension[E]`; `DOCS/build/testing.md` (or where the testkit is documented): `stopService`, `startService`, `restartService(extensions, whileStopped)` and `MovableClock`.

---

## Dependencies & Execution Order

### Phase dependencies

- Phase 1 → Phase 2 → everything.
- US1 (Phase 3) before US2: a run needs a held version.
- US2 (Phase 4) before US3, US4, US5: they extend the run worker.
- US3, US4 and US5 are independent of each other once US2 is in.
- US6 needs US3 (for-each, critique) and US4 (schedules); its planner part (T056) needs only US3's gather.
- Phase 9 last; T057 can be drafted beside US3.

### Within a story

Tests first, failing for the right reason; then entities, then the host or agent, then the client
calls and registration.

### Parallel opportunities

- T002–T004; T005–T010 and T012 (different files).
- T014 and T015; T021–T023; T034–T035; T043–T044.
- After US2: US3, US4 (T043, T045 even earlier: they are pure) and US5 by different hands.
- T051, T052, T055, T056; T057–T059.

### Parallel example: after Phase 4

```text
US3: T036 → T037 → T038 → T039; T040, T041, T042 in BP/Patterns.scala in turn
US4: T045 → T046 → T047
US5: T048 → T049
```

## Implementation Strategy

1. **MVP**: Phases 1–3 (registering and checking) prove blueprints as data; Phase 4 makes them run.
   Stop there and demonstrate a blueprint of ask steps run, restarted and read.
2. Add Phase 5: every pattern, completing the P1 stories.
3. Add Phases 6 and 7 in either order; ankka-reasoning can start its projection after Phase 7.
4. Phase 8 proves the spec's example; Phase 9 documents and runs the whole build.

## Notes

- A verify-first task that fails stops the work it gates; say so rather than working round it.
- A scripted model shared by items run at once must be keyed by instructions, never queued (R20).
- Commit after each task or logical group; never change a pinned journal file.
