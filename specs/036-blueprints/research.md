# Research: Blueprints

Decisions for [spec.md](spec.md), from `main` at `afb840b7` (feature 029 merged). Paths are under
`modules/` unless they start elsewhere; `agent/` is `modules/agent/src/main/scala/com/thinkmorestupidless/ankka/agent/`,
`runtime/` and `sdk/` likewise. Words are the glossary's: blueprint, blueprint version, worker,
pattern, run, run status, schedule, due time, period. *Verify first* marks a claim read from the code
and not yet run.

## R1. A run is a platform event sourced entity with a host of its own, not a workflow

**Decision**: a run is `ankka-run`, an event sourced entity written only by its host, and the host is
a sharded actor per run id with remember-entities and one worker virtual thread, as an autonomous
agent instance is. The worker re-reads the run's record at every boundary and goes on from it.

**Rationale**:
- A workflow cannot be followed: nothing can consume a workflow's events or state, because
  projections are typed over `JournalRecord` and workflows journal `WorkflowRecord`
  (`runtime/ProjectionRuntime.scala:670`, `runtime/WorkflowHost.scala:148-193`). FR-026 needs a
  consumer to subscribe to runs, and listing a blueprint's runs needs a view (R11).
- A workflow mid-step is not woken after a restart: `WorkflowEngine.onRecovered` runs only when a
  message reaches the entity (`runtime/WorkflowEngine.scala:87-101`), and "a workflow mid-step survives a
  restart only because something polls it" (`.claude/rules/agents.md`; `specs/015-autonomous-agents/research.md:107-111`).
  Its step, workflow and pause timers are actor timers (`WorkflowEngine.scala:46-48`). A weekly run
  that nobody reads would stop at the first restart. FR-010 forbids that.
- The autonomous host already solves both: `remember-entities` with the event sourced store brings a
  working instance back with nothing sent to it (`agent/AgentRuntime.scala:317-325`), and
  `IterationLoop.resumePoint` reads the record to pick up exactly (`agent/autonomous/IterationLoop.scala:80-99`).
- `WorkflowDescriptor` has no `platform` flag (`sdk/Workflow.scala:291-308`); an event sourced
  entity's companion has one (`sdk/EventSourcedEntity.scala:165`).
- `start` under an id already held with the same blueprint and input answers the run; with a
  different one it is `Conflict`, as `TaskEntity.create` is (`agent/autonomous/TaskEntity.scala:172`).
  `ankka-run-host` is the host's sharding entity type, not a component descriptor.

**Alternatives considered**: a platform workflow per run (rejected for the two reasons above); a
user workflow generated from the blueprint (rejected: a blueprint is data, and a workflow's steps are
code registered at start); the autonomous instance itself running steps (rejected: an instance works
one task at a time from a queue, and a run's for-each needs many workers at once).

## R2. A blueprint is a platform event sourced entity per name; checking happens before the command

**Decision**: `ankka-blueprint`, one entity per blueprint name, whose events are
`VersionRegistered(number, canonical, digest, registeredAt)` and `ScheduleAdvanced(periodEnd)`.
`client.blueprints.register(blueprint)` checks the blueprint against the service's blueprint
registry (R6) in the caller, and only a blueprint with no problems is sent. The entity compares the
digest of the canonical JSON with the current version's: equal is the current version, different is
the next number.

**Rationale**: checking needs the service's registered tools, models, guardrails and questions,
which live in the runtime, not in an entity's state; an entity serialises two instances registering
the same carried blueprint at start, so FR-005's "one version" holds without a lock. Consumers
subscribe with `ChangeSource.eventsOf(BlueprintEntity)` (FR-026), as `TaskCascade` does over
`TaskEntity` (`agent/autonomous/TaskCascade.scala:44`).

**Alternatives considered**: a key value entity holding all versions (rejected: a consumer of state
may skip an intermediate version, `sdk/ChangeSource.scala:25-34`); checking inside the entity
(rejected: an entity has no access to the registry, and a refusal would then be a command error
carrying a list, which `CommandError` does not hold).

## R3. Canonical form and identity of a version

**Decision**: a blueprint is encoded with the shared codec config, keys sorted, no insignificant
whitespace; its digest is SHA-256 of that text. Version numbers start at 1 and are never reused.

**Rationale**: "registered twice is one version" needs equality that survives field order and
re-encoding; the canonical text is also what a consumer receives. *Verify first*: jsoniter writes
case class fields in declaration order, so sorting is needed only for maps (`parameters`), which are
written as sorted vectors of pairs.

## R4. A work step is an autonomous task that carries its worker's definition

**Decision**: `TaskRecord` and `TaskEvent.Created` gain an optional `definition: Option[TaskDefinition]`
(instructions, model name, tool names, guardrail names, budget), defaulting to none. A platform
autonomous agent, `ankka-blueprint-worker`, accepts any task that carries a definition; its host's
`Worker` resolves the definition, the tools, the model and the budget per task rather than once per
activation, and builds an `IterationLoop` per task. A task without a definition is worked exactly as
today. The task's type is one platform type, `blueprint-step`, whose schema and whose one rule (the
shape check, R9) the host builds per task from the definition the task carries; the type's name is
one wire name, declared once.

**Rationale**: the definition is read in four places, all once per activation
(`AutonomousAgentHost.scala:84`, `:94`, `:99`, `:510-536`); `IterationLoop` is a plain class taking
`definition` and `model` (`IterationLoop.scala:41-60`), so one per task is cheap. Data-defined task
types exist (`Task.scala:130-144`, `private[ankka]`, which the platform may use). The sidecar already
builds a whole definition from data (`sidecar/.../RemoteAutonomousAgent.scala:74-200`), so every value
needed is constructible. Resumption and the never-repeated model call are unchanged, which FR-016
asks for.

**Alternatives considered**: one autonomous agent component per worker generated at start (rejected:
a new version would need a restart, against SC-004); a request agent looping in the run host
(rejected: a crash would repeat a whole loop's model calls, against FR-010).

## R5. Ask, for-each, gather and critique turns go to one platform request agent

**Decision**: `ankka-blueprint-ask`, a platform request agent with one handler, `turn`, whose input
is a `WorkerTurn` (the worker's definition as data, the step's input, the run reference, and the
result shape). The handler builds its effect from the payload alone:
`effects.model(models(name)).systemMessage(instructions).userMessage(input).tools(tools(names)*).guardrails(...).memory(...).maxToolCallSteps(budget)`.
Each worker call has a session of its own, `run:<runId>:<step>:<worker>[:<item>]`.

**Rationale**: an effect is chosen in the handler (`agent/AgentEffect.scala:45-124`), so data is
enough. Building from the payload alone keeps the handler deterministic, which approvals need: a
suspended turn is resumed by running the handler again on the recorded payload
(`agent/AgentRuntime.scala:667-699`). One session runs one request at a time and stashes up to 32
(`AgentRuntime.scala:406-533`), so items running at once need sessions of their own (FR-021 asks for
that anyway).

**Alternatives considered**: calling `AgentLoop` directly from the run host (rejected: it would
bypass the session entity, approvals and the trace a handler gives); a component per worker
(rejected as in R4).

## R6. Tools, models, guardrails and questions a blueprint may name are registered for blueprints

**Decision**: `AgentRuntime.withBlueprints(BlueprintContext => BlueprintRegistry)`: the registry is
built once at start from a context carrying the component client, the service clients and the
secrets, because a tool that writes an entity needs the client when it is built (that is why
`AutonomousAgent#tools` is an instance member, `agent/autonomous/AutonomousAgent.scala:49`). It
holds named `FunctionTool`s, MCP servers (connected at start as an agent's are), named `ModelProvider`s (the
runtime's default model is `default`), named guardrails and named judgment questions. A blueprint
names only these. **The spec is amended**: FR-002 and FR-004 said "the tools the service's agents
have"; there is no such list.

**Rationale**: nothing keys tools by name across a service: an agent's tools are its effect's, and
MCP tool vectors are local to each host (`AgentRuntime.scala:151-165`, `:269-317`). Explicit
registration is the codebase's rule ("no classpath scanning"; a missing thing fails at start).
Named models have a precedent in the sidecar (`sidecar/.../Models.scala:28-56`). Questions built from
data exist (`judgment/Question.scala:47-74`), but registering them keeps their wire ids declared once.
The registry also knows whether the service has timers (`service.extensionNames`), so a schedule in
a service without them is a problem the check names, whether the blueprint is carried or registered
by a call.

**Alternatives considered**: collecting every agent's tools at start (rejected: a tool is chosen
inside a handler, so the runtime cannot list an agent's tools without running it); defining tools in
the blueprint (out of scope in the spec).

## R7. A step bound per effect

**Decision**: `AgentEffect.maxToolCallSteps(n)`, overriding the companion's for one turn; the loop
uses the effect's when set (`AgentLoop.scala:586-597`). A worker's budget bounds one turn: an ask
turn, one item, one round, or the task of a work step. The run budget bounds the run.

**Rationale**: `maxToolCallSteps` is per companion (`agent/Agent.scala:98`), and one platform agent
serves every worker.

## R8. A judgment from the run host

**Decision**: `AgentRuntime` hands its `Judgments` instance to the blueprint host; a judge step and a
judgment verdict call `Judgments.ask(None, state, questions)` with the step's input as state. Usage
is recorded on the run, not a session.

**Rationale**: `Judgments.ask` verifies every answer and owns the timeout
(`agent/judgment/JudgmentProvider.scala:58-89`); it is `private[ankka]`, and the run host is
platform code. This is the "judgment from a workflow step" ankka-reasoning asked for, in the form
blueprints need, without a public client.

**Alternatives considered**: a platform agent whose handler returns `effects.judgment` (rejected:
an extra hop and a session for nothing).

## R9. Shapes are a small JSON Schema subset, checked by the platform

**Decision**: a shape is JSON Schema restricted to `type` (object, array, string, number, integer,
boolean), `properties`, `required`, `items`, `enum` and `description`. `Shape.check(json)` returns the
paths that do not conform. The run's input is checked before the run is held (FR-007); a step's
result is checked when the worker answers, and a failure goes back to the worker with the paths
(FR-013): for an ask turn as a further user message in the same session, within the worker's budget;
for a work step through the task's rejected-result path.

**Rationale**: `JsonSchema[A]` only describes a shape to a model and derives from case classes
(`autonomous/JsonSchema.scala:17-94`); a blueprint's shapes are data, so the platform needs a
checker. A subset is enough for the patterns' needs (a list to iterate over, a verdict with reasons)
and is cheap to check exhaustively. "Input shape" stays a proposed term until this lands.

**Alternatives considered**: a JSON Schema library (rejected: a new dependency in `agent` for a
subset of one draft).

## R10. Critique

**Decision**: the drafting worker's turns share one session across rounds, so each draft sees the
earlier drafts and the reasons; a critic is an ask turn in a session of its own whose result shape is
the platform's `Verdict { passed: boolean, reasons: [string] }`; a judgment verdict asks one yes/no
question with the draft as state, and a "no" gives the reason "the judgment answered no to <question>".
Each round is recorded on the run (`RoundEnded`) before the next starts.

## R11. Listing runs: a platform view

**Decision**: `ankka-blueprint-runs`, a view over `RunEntity` events with one row per run (run id,
blueprint, version, status, started, ended), queried by blueprint. `ViewDescriptor` gains the
`platform` flag the entity, consumer and timed action descriptors have.

**Rationale**: a view's table is created by the runtime (`ProjectionRuntime.scala:90-111`), so no
DDL; an event sourced source gets exactly-once view updates (`:437-447`). `ViewDescriptor` has no
`platform` field today (`sdk/View.scala:90-97`), nor has `AgentDescriptor` (R5), and the topology
reads it (`TopologyJson.scala:252`). Listing requires a `ProjectionRuntime`; without one, listing is
refused with a reason and everything else works, as `TaskCascade` warns today
(`AgentRuntime.scala:345-349`).

## R12. Schedules: one timer per occurrence, computed in the zone, from the clock

**Decision**: a platform timed action, `ankka-blueprint-schedule`, fired by the service's
`TimerRuntime`. Each occurrence is its own timer, `schedule:<blueprint>:<due epoch millis>`; its
handler reads the blueprint's last period end, computes every due time passed since (R13), starts the
run or runs under ids `schedule:<blueprint>:<due epoch millis>`, records `ScheduleAdvanced`, and
creates the timer for the next due time. Due times are computed with `java.time` in the schedule's
`ZoneId`: weekly on a day at a local time is `ZonedDateTime.with(nextOrSame(day)).with(localTime)`,
so a change of the clocks keeps the local time and the period is an hour shorter or longer.

**Rationale**: a handler that schedules its own timer's name is deleted by the sweeper's success
path (`runtime/TimerSweeper.scala:267-269`; the defect spec 032 records), so each occurrence is named
apart. The sweeper is a cluster singleton (`TimerRuntime.scala:44-61`) and run ids are idempotent
(FR-007), so one run per due time holds across instances (FR-022). Spec 032 is a draft and covers
intervals only, not zones or calendars (`specs/032-recurring-timers/spec.md:76-78`); this feature
does not wait for it. A blueprint version without a schedule deletes the pending occurrence; one
with a schedule creates it, from the last period end (FR-024).

**Alternatives considered**: a self-rescheduling handler (rejected: the sweeper deletes it); waiting
for 032 (rejected: it does not do zones).

## R13. Catch-up after an outage

**Decision**: the schedule records `catchUp: one | each`, default `one`. The handler's missed due
times are every due time after the last period end up to now, under the schedule of the version
current when the handler runs, and the runs use that version: `one` starts one run covering from the
last period end to the latest; `each` starts one per due time, oldest first, each covering its own
period, under its own id. The first period of a schedule starts one cadence before its first due
time.

**Rationale**: the clarification; ids per due time make `each` idempotent across a crash part way.
The version current at a missed due time is knowable from the version history, but its schedule
may differ from the current one, and two schedules cannot both say which due times were missed;
the current version is the one the developer has asked for.

## R14. A clock for timers and schedules

**Decision**: `TimerRuntime(clock: Clock = Clock.systemUTC)`; the sweeper's `now`, a timer's
`due_at` and the backoff use it, and the blueprint host takes its clock from the same runtime. The
testkit gives a `MovableClock` a suite moves. **Verify first**: `TimerStore.due(now, limit)` already
takes `now` as a parameter (`runtime/TimerRuntime.scala:126-129`); the two `Instant.now()` calls
(`:91`, `:138`) and the sweeper's (`TimerSweeper.scala:118`) are the only reads.

**Rationale**: SC-005 needs five Sundays in a test, and nothing in the runtime has a settable clock
today. ankka-reasoning has asked for a command clock for its own reasons; this is not that, but is
the same direction.

## R15. Tools know their run

**Decision**: `RunContext.current: Option[RunRef]` (run id, step, blueprint, version), set around
each tool's invocation by the tool runner from the session id (`run:<runId>:<step>:…`) for ask
turns, and from the task's definition for work steps. Outside a run it is `None` (FR-025).

**Rationale**: a tool runs after its handler has returned, so it cannot read the handler's context
(the trap in `.claude/rules/agents.md`: "a `FunctionTool` invoker that reads `sessionId` when called
throws"). The tool runner knows the session at the call; a thread-local set and cleared around one
invocation is on the thread doing the work.

## R16. Approvals in a step

**Decision**: when an ask turn answers `AwaitingApproval`, the run records `WaitingForDecision(step,
session, approvalId)` and its status becomes waiting for a decision; a step record holds every
request awaiting a decision, since several items may wait at once. The host polls each waiting
session, every second at first and backing off to every 30 s, until the suspended turn has ended,
then reads the turn's answer from the session and goes on. When the run ends while any request
awaits a decision (its time limit passed, or it was cancelled), the host refuses each request itself
as the platform, with the note "run <id> ended", so a later decision runs nothing. A work
step's waiting is the task's (feature 029), which the host already awaits. Budgets do not advance
while waiting, because no model call is made.

**Rationale**: the decision is made by whoever calls `decide` on the session, not the run; the
session is the one place the answer is recorded (`agent/SessionMemoryEntity.scala:315-324`).

## R17. Cancelling, budgets and time limits

**Decision**: cancel is recorded on the run; the worker checks it before every turn, item, round,
iteration (work steps cancel their task) and step. **The spec is amended**: FR-012 said a cancelled
run "stops before its next model call"; an ask turn in progress runs to its end, because `AgentLoop`
has no point to stop between model calls, and nothing starts after it. The run budget counts model
calls from each session's history; the time limit is a deadline on the run's record, checked at the
same boundaries and armed as an actor timer that the record re-arms after a restart.

## R18. Resuming an ask turn

**Decision**: before calling a worker for an item, round or ask step, the host reads the session; a
turn already ended there for the same input is taken as the answer without a call. A turn cut off
part way is run again from its start.

**Rationale**: an ask turn's messages are written when it finishes (`AgentLoop.scala:214-246`), so a
model call inside a turn is recorded only with the turn; FR-010's "a model call recorded before the
restart is not made again" holds, and a turn that was not recorded is run again. This is stated in
the guide.

## R22. A gather chosen by an earlier step

**Decision**: `Gather` gains `chosenBy: Option[Read]`, a read of an earlier step's result that must
be an array of strings. At run time only the named workers among the step's own run, in the step's
order; an empty list gives an empty result. The check refuses a read that is not an array of
strings. The planner's blueprint gives the select step a result shape whose `specialists` items
have an `enum` of the three names, so an invented name is a shape refusal the model corrects.

**Rationale**: the multi-agent planner consults only the specialists its selector chose
(`samples/multi-agent-planner/.../PlannerWorkflow.scala:87-116`), and SC-001 says the planner is a
blueprint whose suite passes; a fixed gather consults every specialist and the case *a different
selection* fails. A choice read from data is a pattern parameter as a for-each's list is, not a
condition in the blueprint: the step's workers stay the fixed limit, and the read picks within them.
The planner's fallback to a default specialist is a condition, and is not expressed; SC-001 says so.

**Alternatives considered**: a fixed gather with SC-001 restated (rejected: it drops the one dynamic
shape the docs teach, and nothing else would exercise it); a condition on the step (rejected: the
rule that a blueprint has none).

## R23. The calls hang off the `AgentRuntime`, not the `ComponentClient`

**Decision**: `agents.blueprints` and `agents.runs`, where `agents` is the service's `AgentRuntime`,
available once the service has started; the contract is amended from `client.blueprints`.

**Rationale**: registering checks the blueprint against the registry (R6), and the registry is
built by the runtime at start from a context; a `ComponentClient` is the SDK's and knows nothing of
it, and a process-wide holder would be wrong with two services in one JVM, as the testkit runs them.
`TimerRuntime.timerScheduler` is the precedent: "available once the service has started; inject
into endpoints and workflows".

**Alternatives considered**: `client.blueprints(registry)` (rejected: a component rarely holds the
registry); checking inside the entity (rejected in R2).

## R19. The research digest sample

**Decision**: `samples/research-digest`, an sbt project like `multiAgentPlanner` (`build.sbt:880-884`)
added to `root`'s aggregate, no image. It registers a `PapersEntity` (one key value entity per paper
identifier, holding the sources that found it and when it was first found), tools `search_<source>`,
`keep_paper`, `papers_found_between`, and two blueprints carried in its code as JSON resources. Its
features run through `GherkinSuite("features")` as the shopping cart's do
(`samples/shopping-cart/src/test/scala/shoppingcart/CartFeatures.scala:23`), with scripted sources
(a `TestMcpServer` per source), a scripted model keyed by instructions (`whenUserAsks`, since items
run at once and one queue would race) and a scripted judgment provider. The root scenario is held by
a suite that runs the sample's suite and counts its passed scenarios. Its glossary is checked by a
second `speckit-bdd check` invocation in `.github/features-check.sh`.

**Rationale**: the shopping cart's precedent; nothing checks a sample's glossary today, and the
clarification put the sample's words there.

## R20. Where each scenario is tested

| Feature | Level | Suite |
|---|---|---|
| `blueprints/registering.feature` | testkit, Postgres | `BlueprintFeatures` (GherkinSuite over the file) |
| `blueprints/checking.feature` | no runtime, in `testkit`'s tests | `BlueprintCheckFeatures`, with `BlueprintCheckSuite`'s property case in `agent` |
| `blueprints/runs.feature` | testkit | `RunFeatures` |
| `blueprints/patterns.feature` | testkit | `PatternFeatures` |
| `blueprints/schedules.feature` | unit (due times, zones) and testkit with a movable clock | `ScheduleSuite`, `ScheduleFeatures` |
| `blueprints/following.feature` | testkit with `ProjectionRuntime` | `FollowingFeatures` |
| `blueprints/research-digest.feature` | the sample's project | `SampleFeaturesPassSuite` |
| `documentation/blueprints.feature` | pages | `BlueprintsDocumentationSuite` |

Three places a test could pass while the thing is false, named now:
- a restart test that polls the run would wake it as `WorkflowSuite` does; the restart suites read
  the run only after it has ended by itself, waiting on a consumer's record instead;
- a for-each with one scripted queue would pass in one order and fail in another; items are scripted
  by their instructions;
- "nothing is held" after a refusal must read the entity and the view, not the client's answer.

## R21. Documentation

**Decision**: one new page, `docs/build/blueprints.md` (guide, components `[agent, autonomous-agent]`),
in `mkdocs.yml` under Build after *Autonomous agents*, and in the `ankka-agents` skill's `pages:`
with its description extended. Samples are included from the research digest and from the test
fixtures. `docs/reference/limitations.md` changes the autonomous agents entry (blueprints give
per-task definitions to work steps), the judgments entry (a blueprint step asks one), and gains a
blueprints entry: Scala only, no branching search, no person as a step of its own.

## Verify first, gathered

- jsoniter field order is declaration order (R3).
- `TimerStore.due` uses its `now` parameter only (R14).
- A user's consumer over a platform event sourced entity works end to end; mechanically it compiles
  (`TaskCascade`), but no test subscribes from user code (R2, R11).
- The approval resume re-runs the `ankka-blueprint-ask` handler from its recorded payload with the
  same tools when the registry is unchanged (R5, R16).

## Verified during implementation

- 2026-10-07, baseline (T001): `agent/test` 174 passed, the named testkit suites 130 passed, the
  planner sample 10 passed; every new path is claimed by a CI filter.
- V1 (T002, `CanonicalSuite`): the shared codec config writes a case class's fields in declaration
  order; a `Map`'s order is its own, so the canonical form writes any map as pairs sorted by key
  and never a `Map` (R3 holds, with that rule).
- V2 (T003, `TimerStoreClockSuite`): `TimerStore.due(now, limit)` returns a timer due at `t` for
  `now = t` and not for `now = t - 1s`, a year ahead of the wall clock; the query reads only its
  parameter (R14 holds).
- Phase 3 (T014–T020): `registering.feature` and `checking.feature` pass against a real journal; a
  blueprint registered by two services at once would serialise on the entity, and the restart case
  showed a carried blueprint re-registered at start adds no version. The calls moved to the
  `AgentRuntime` (R23).
- V3 (T004, `PlatformEntityConsumerSuite`): a consumer declared in a package outside `ankka`
  subscribes to `ankka-task`'s events with `ChangeSource.eventsOf(TaskEntity)` and receives
  `Created` first and `Completed` last for a task, in order (R2, R11 and FR-026 hold).
