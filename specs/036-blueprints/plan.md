# Implementation Plan: Blueprints — Agents and Their Interactions as Data, Run by the Platform

**Branch**: `036-blueprints` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/036-blueprints/spec.md`

## Summary

A Scala service registers **blueprints**: workers defined by data and steps that each use one of the
platform's **patterns** (ask, work, for-each, gather, judge, critique). Each registration that
differs is a new **blueprint version**, never changed. A **run** carries out one version, is held
with every step's result, the workers' sessions and their usage, survives a restart with no ended
step done again, and can be followed by a consumer. A **schedule** starts runs at due times in a
time zone, with each run's **period** as its input, and catches up after an outage as the schedule
says.

Technically: a run is a platform event sourced entity, `ankka-run`, worked by a host of its own
with remember-entities, as an autonomous agent instance is (R1); a blueprint is a platform event
sourced entity per name whose versions are compared by the digest of their canonical JSON (R2, R3).
Ask, for-each, gather and critique turns go to one platform request agent that builds its effect
from the turn's payload (R5); a work step is an autonomous task that carries its worker's
definition, worked by a platform autonomous agent that resolves the definition per task (R4).
Judgments are asked from the host through the runtime's `Judgments` (R8). Shapes are a JSON Schema
subset the platform checks (R9). Schedules are one timer per occurrence, computed with `java.time`
in the schedule's zone (R12, R13), and the timer runtime gains a settable clock (R14).

Planning found three things the spec did not have, and the spec is amended for each:

- **What a blueprint may name is registered for blueprints.** FR-002 and FR-004 said "the tools
  the service's agents have"; nothing lists those, because an agent's tools are chosen inside its
  handler. A service registers the tools, MCP servers, named models, guardrails and judgment
  questions its blueprints may name (R6).
- **Cancelling lets the turn in progress finish.** FR-012 said a cancelled run stops before its
  next model call; an ask turn's tool loop has no point to stop between model calls, so the turn in
  progress runs to its end and nothing starts after it (R17).
- **Each item of a for-each or gather is an ask turn.** The spec left open whether an item could be
  a work loop; in this feature it cannot, and *Not in this feature* says so (R5).

A review of the spec and plan found seven more, each amended in the spec with its reason in
`research.md`: a gather chosen by an earlier step (R22, without which SC-001 could not hold); an
ask turn interrupted by a restart runs again from its start (R18); a run that ends while waiting
refuses its approval requests (R16); catch-up runs use the version current when the service is back
(R13); a worker's budget is per turn (R7); a schedule in a service without timers is a check problem
(R6); and a run id reused for a different blueprint or input is refused (R1).

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21, on the Pekko line the family pins.

**Primary Dependencies**: none added. `java.time` for zones; jsoniter for the canonical form;
`java.security.MessageDigest` for digests.

**Storage**: no table and no DDL. Two platform event sourced entities (`ankka-blueprint`,
`ankka-run`) in the journal; one platform view (`ankka-blueprint-runs`) whose table the runtime
creates; schedule occurrences in the existing `ankka_timers`. `ankka-task`'s `Created` event and
record gain an optional definition, defaulting to none.

**Testing**: munit in `agent` (`BlueprintCheckSuite`'s property case, `ShapeSuite`, `ScheduleSuite`,
`CanonicalSuite`); `testkit` with Postgres (`BlueprintCheckFeatures`, `BlueprintFeatures`, `RunFeatures`, `PatternFeatures`, `ScheduleFeatures`,
`FollowingFeatures`, `BlueprintRestartSuite`, `AskApprovalSuite`, `EventCompatibilitySuite`); the sample's
`ResearchDigestFeatures`; `PlannerBlueprintSuite` in the planner sample; `BlueprintsDocumentationSuite`
in `controlplane-api`'s tests with the other documentation suites. Scripted models keyed by
instructions for anything run at once (R20).

**Target Platform**: any service ankka runs, Scala only (clarification).

**Project Type**: platform library feature in `ankka-agent`, a runtime change in `ankka-runtime`,
a new sample.

**Performance Goals**: none beyond the spec's: a scheduled run starts within the sweeper's poll
interval of its due time; a for-each runs at most its limit at once.

**Constraints**: `agent` still depends on `core`, `sdk` and `runtime` only; `runtime` names nothing
of blueprints; a step is effects and records, so the testkit and the runtime reduce the same values;
every wire name declared apart from its Scala name; no new extension point outside
`AgentRuntime`; `Test / parallelExecution := false` stays; warning-free; no suite binds a fixed port.

**Scale/Scope**: about 22 new Scala source files and 12 changed in `agent`, 3 changed in `runtime`
and `sdk` (the clock, the `platform` flag on views; the two agent descriptors' flags are in `agent`), 6 in `testkit`'s tests; the sample about 10; 1 new docs
page and 4 changed; 70 scenarios in eight root feature files and six in the sample's.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles `CLAUDE.md`
states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | A step's work is agent effects and recorded run events; the ask agent's effect is built from its payload alone (R5) |
| Where two interpreters reduce the same thing, they share one function | pass | Shape checking (R9) and due-time computation (R12) are pure functions both the host and the tests call |
| Module dependency direction | pass | Everything lives in `agent`; `runtime` gains a clock and `sdk` a flag, neither naming blueprints |
| No classpath scanning; explicit registration; a missing thing fails at start | pass | The registry is explicit (R6); a scheduled blueprint without timers fails at start |
| Wire names are a versioning boundary | pass | Component ids `ankka-blueprint`, `ankka-run`, `ankka-blueprint-ask`, `ankka-blueprint-worker`, `ankka-blueprint-runs`, `ankka-blueprint-schedule`; the run host's sharding type `ankka-run-host`; the task type `blueprint-step`; commands kebab-case, declared apart |
| Stored forms stay readable both ways | pass | New fields default; new journal files pinned (data-model.md, Compatibility) |
| Nothing wakes a passivated entity | pass | The run host uses remember-entities (R1); waits for decisions are polled by the host (R16) |
| A trace belongs on the thread doing the work | pass | `RunContext` is set and cleared around one tool invocation on that thread (R15) |
| A refusal is not a failure | pass | A refused blueprint names every problem and holds nothing; a failed step fails the run with its reason |
| Tests are serialised; no fixed port; no literal image tag | pass | No new image; suites use the shared Postgres and port 0 |
| Could this check pass while the thing it checks is false? | pass | Three named in R20 |
| Each acceptance scenario ends as a test that fails without the feature | pass | R20 maps every feature file to a suite |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R21 |
| Every tracked file claimed by a CI path filter | pass | `samples/**`, `modules/**`, `features/**`, `docs/**` are claimed already |

**Violations to justify**: none against these principles. Where the plan departs from the spec's
wording is under *Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no module and no table.

## Project Structure

### Documentation (this feature)

```text
specs/036-blueprints/
├── spec.md              # amended for R6, R17 and R5
├── plan.md              # this file
├── research.md          # R1–R21, with what to verify first
├── data-model.md        # values, two entities, the host, the platform agents, the view, the timer
├── quickstart.md        # the runs that show it works, cheapest first
├── contracts/
│   └── scala-api.md     # the registry, the builder, the calls, RunContext, the testkit clock
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/blueprints/` (seven files) and
`features/documentation/blueprints.feature`, in the words of `GLOSSARY.md`; the sample's are in
`samples/research-digest/features/`, in the words of its own glossary.

### Source Code (repository root)

```text
modules/agent/…/agent/blueprint/
├── Blueprint.scala           # new: Blueprint, Worker, Step, Pattern, Schedule, builder, JSON, canonical
├── Shape.scala               # new: the JSON Schema subset and its check (R9)
├── BlueprintRegistry.scala   # new: built from a context: tools, MCP servers, models, guardrails, questions, carried blueprints (R6)
├── BlueprintCheck.scala      # new: every problem in one pass (FR-004)
├── BlueprintEntity.scala     # new: ankka-blueprint (R2)
├── RunEntity.scala           # new: ankka-run, RunStatus, events
├── RunHost.scala             # new: ankka-run-host, the actor shell (R1)
├── RunWorker.scala           # new: the worker thread: steps, items, rounds, waits, budget, deadline
├── Patterns.scala            # new: one function per pattern over the run's record
├── AskAgent.scala            # new: ankka-blueprint-ask (R5)
├── WorkerAgent.scala         # new: ankka-blueprint-worker (R4)
├── RunsView.scala            # new: ankka-blueprint-runs (R11)
├── ScheduleTimer.scala       # new: ankka-blueprint-schedule, due times, catch-up (R12, R13)
├── RunContext.scala          # new: RunRef and the thread-local (R15)
└── Calls.scala               # new: client.blueprints, client.runs
modules/agent/…/agent/AgentRuntime.scala          # withBlueprints; descriptors; hosts the run host
modules/agent/…/agent/AgentEffect.scala           # maxToolCallSteps per effect (R7)
modules/agent/…/agent/AgentLoop.scala             # the effect's bound; RunContext around tools
modules/agent/…/agent/Agent.scala                 # platform flag on AgentDescriptor
modules/agent/…/agent/autonomous/TaskEntity.scala # definition on Create, Created, TaskRecord (R4)
modules/agent/…/agent/autonomous/AutonomousAgentHost.scala  # definition, tools, model, budget per task
modules/agent/…/agent/autonomous/IterationLoop.scala        # built per task; RunContext around tools
modules/agent/…/agent/autonomous/Calls.scala      # TaskBuilder.withDefinition (platform only)
modules/sdk/…/sdk/View.scala                      # platform flag on ViewDescriptor
modules/runtime/…/runtime/TimerRuntime.scala      # clock (R14)
modules/runtime/…/runtime/TimerSweeper.scala      # clock
modules/testkit/…/testkit/MovableClock.scala      # new
modules/testkit/…/testkit/AnkkaTestKit.scala      # awaitRun
modules/testkit/src/test/scala/…/blueprint/       # the suites in R20
modules/testkit/src/test/resources/journal/       # pinned blueprint, run and task-with-definition journals
samples/research-digest/                          # new sbt project, features, glossary (R19)
samples/multi-agent-planner/…/PlannerBlueprint.scala  # SC-001
build.sbt                                         # researchDigest, in root's aggregate
.github/features-check.sh                         # check the sample's glossary too
docs/build/blueprints.md                          # new
docs/reference/limitations.md                     # three entries
mkdocs.yml, tools/docs/skill/ankka-agents/SKILL.md
```

**Structure Decision**: no new module. The feature is a package in `ankka-agent`, because
everything it builds on (request agents, autonomous tasks, judgments, sessions, approvals, MCP) is
there; `runtime` gains only a clock, and `sdk` a flag on views.

## Order of work

Cut by user story, tests before the code they hold. Each slice stands on its own.

1. **Foundations**: `Shape`, `Blueprint` with its JSON and canonical form, `BlueprintCheck`,
   `ScheduleSuite`'s pure due times; `platform` flags; `AgentEffect.maxToolCallSteps`;
   `TimerRuntime`'s clock and `MovableClock`.
2. **Story 1, registering and checking**: `BlueprintRegistry`, `BlueprintEntity`,
   `client.blueprints`, carried blueprints at start; `BlueprintFeatures`, `BlueprintCheckFeatures`.
3. **Story 2 and the ask pattern, runs**: `RunEntity`, `RunHost`, `RunWorker`, `AskAgent`,
   `client.runs`, `RunsView`; `RunFeatures`, `BlueprintRestartSuite`.
4. **Story 3, the other patterns**: for-each and gather on the ask agent; judge through `Judgments`;
   critique; work through the task definition (`TaskEntity`, `AutonomousAgentHost`, `IterationLoop`,
   `WorkerAgent`); `PatternFeatures`, the compatibility files.
5. **Story 4, schedules**: `ScheduleTimer`, catch-up; `ScheduleFeatures`.
6. **Story 5, following**: `RunContext`; consumers over both entities; `FollowingFeatures`.
7. **Story 6 and SC-001, the samples and documentation**: the research digest, the planner as a
   blueprint, the guide, limitations, nav and skill; the features check over the sample's glossary.

Slice 1's parts can be built at once; slices 2 and 5's pure parts can start beside slice 3.

## Complexity Tracking

No principle is violated. These are the places the plan departs from the spec's wording or widens
the platform's surface, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| A registry of what blueprints may name (FR-002, FR-004, amended) | Nothing lists a service's tools; an agent's are chosen inside its handler (R6) | Collecting tools from agents cannot be done without running their handlers |
| A run host of its own, beside the workflow engine | A workflow cannot be followed and is not woken after a restart (R1) | A platform workflow per run would stop at the first restart nobody reads it through |
| A per-task definition on `ankka-task` | A work step needs a definition chosen at run time, durable as a task is (R4) | A component per worker would need a restart for each new version |
| A per-effect step bound on `AgentEffect` | One platform agent serves every worker, each with its own budget (R7) | A companion per worker, as above |
| A clock on `TimerRuntime` | Five Sundays in a test (SC-005); nothing has a settable clock (R14) | Real waits of a week; or schedules bypassing timers, which would lose the singleton sweeper |
| Shapes as a JSON Schema subset with the platform's own check | A blueprint's shapes are data; `JsonSchema[A]` only describes Scala types (R9) | A JSON Schema library: a dependency for one draft's subset |
| Cancelling lets the turn in progress finish (FR-012, amended) | `AgentLoop` has no stopping point between model calls (R17) | A cancellation hook inside the loop, a change to every agent for one caller |
| `platform` on `ViewDescriptor` and `AgentDescriptor` | The topology marks platform components; these two kinds had none (R5, R11) | Leaving them unmarked would show platform parts as the service's own |
| The run host polls a session while a run waits for a decision | A decision is made on the session, and nothing tells the run (R16) | Passivating the host would leave nothing to wake it; the poll backs off to 30 s |
| A gather chosen by an earlier step's result (FR-018, amended) | The planner's selection, the one dynamic shape the docs teach, could not otherwise be a blueprint (R22) | A fixed gather consults every specialist every run and SC-001 would not hold |

## Not in this feature (from the spec, restated for the tasks)

Branching search; a blueprint starting or reading another's run; conditions or loops in a blueprint;
replay and fork; a console view; a person as a step of its own; tools defined in a blueprint; a work
loop per item of a for-each or gather; Python, TypeScript and modules; cost in money; speech.
