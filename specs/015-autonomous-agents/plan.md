# Implementation Plan: Autonomous Agents — Tasks and the Durable Agent Loop

**Branch**: `015-autonomous-agents` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/015-autonomous-agents/spec.md`

## Summary

A second kind of agent: a durable process handed a typed **task** that iterates — model call,
tools, record — until the model calls a built-in tool to complete the task with a result the
type's rules accept, or to fail it, or the iteration budget runs out. Tasks are records with their
own identity and dependencies; instances queue tasks, can be suspended, resumed and terminated, and
answer what they are doing; a live notification stream reaches a browser as server-sent events; a
process that dies mid-task resumes where its journal says it was, on whichever node next hosts it.
Request agents are untouched.

Technically: everything lives in `modules/agent`, hosted by the existing `AgentRuntime` (R1). An
instance is an ordinary event sourced entity for its state plus a sharded, non-persistent host that
runs the loop and is the entity's only writer (R2). Session memory is the iteration log and the
instance journals only iteration boundaries, which gives an exact resume rule from the tail of the
session (R3). Working hosts survive restarts through Pekko's `remember-entities` over the r2dbc
journal, with idle passivation replaced by the host's own (R4, spiked first). Tasks are a platform
entity with a dependents index; a platform consumer cascades cancellation (R5). The model completes
a task through intercepted built-in tools, and `JsonSchema.derived` describes a case class result to
it (R6). Notifications are the `InvokeStream` shape with watched subscriber refs (R8). The sidecar
carries the whole definition in discovery and runs the loop itself, asking the process only to run a
tool, check a guardrail and check a rule — protocol 1.2 (R12). Python gets the SDK, both testkits
and the sample; TypeScript the declaration and the client (R13).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`agent`, `sdk` extension methods, `testkit`, `sidecar`,
`core` enum case); Python ≥ 3.12; TypeScript on Node ≥ 22.22

**Primary Dependencies**: Pekko 1.7.0 — cluster sharding `remember-entities` with the
`eventsourced` store (new use of an existing dependency), pekko-persistence-r2dbc 1.2.0 (the store's
journal), `pekko-stream-typed` (`ActorSource`, already in `agent`); jsoniter (`Codecs.make`),
Scala 3 `Mirror` for `JsonSchema.derived`; the Anthropic Java SDK only as today. **No new
library.**

**Storage**: Postgres via the existing journal — two new platform entities (`ankka-task`,
`ankka-agent-instance`) and session memory for working histories; sharding's remember-entities
rows in the same `event_journal`. **No DDL change** (R4's fallback would add one additive table).

**Testing**: munit offline suites in `agent` (`EventSourcedTestKit`), whole-service suites in
`testkit` (`AnkkaTestKit` with `TestModelProvider`, `restartService()`, a new `startPeer()`),
`sidecar` suites on `ProcessDouble` and the `ConformanceSuite` `auto.*` cases on three targets;
pytest and mypy for Python, the TypeScript test suite and conformance; `EncodingFixturesSuite` and
an `EventCompatibilitySuite` for the new events. No k3s suite.

**Target Platform**: wherever an ankka service runs — the local overlay, the testkit and Kubernetes
alike; sharding settings go in `reference.conf` because they are not a property of where the
process runs

**Project Type**: platform library (`ankka-agent`, `ankka-testkit`), sidecar, three SDKs, samples, docs

**Performance Goals**: a caller blocking for a result returns within 1s of completion (SC-002);
the next queued task starts within 1s of the previous ending (SC-004); recording an iteration adds
two local entity writes to a model call that takes seconds

**Constraints**: `ankka-runtime` gains no dependency on `ankka-agent`; effects stay inert; wire
names declared separately from Scala names; no secret in any journal (none here); a fieldless enum
gets a string codec in its companion; `Test / parallelExecution := false` stays; `-Wunused` clean;
protocol change is minor (additive); a tool may run twice and the docs say so

**Scale/Scope**: ~30 new Scala files in `agent`/`testkit`/`sidecar` (+ 1 enum case in `core`), ~10
Python and ~6 TypeScript files, 2 proto files, 2 new docs pages and 8 changed, 2 samples extended,
1 conformance reference per target extended; ~18 conformance cases

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | a definition is data (`AutonomousAgentDefinition`); the loop, rules and built-ins are interpreted by the host; a rule returns `Accepted`/`Rejected` values |
| Module dependency direction | pass | new code in `agent` (depends on `core`, `sdk`, `runtime`); `core` gains one enum case; `runtime` unchanged; `testkit` gains helpers; `sidecar` translates; nothing new is published |
| No classpath scanning; explicit registration | pass | `register(X.descriptor)`; the platform entities and consumer come through `AgentRuntime.descriptors`, which services already register |
| Wire names are a versioning boundary | pass | `Task.named("…")` is separate from the Scala value; host methods are reserved literal names; proto additions are new numbers, minor bump |
| `RuntimeExtension` seam | pass | `AgentRuntime.start` hosts the new kind as it hosts request agents; `Ankka.host` falls through for it |
| `ModuleCommand` — every host must handle unexpected commands and reply to `InvokeStream` | pass | the host answers unknown methods `NotFound` and `InvokeStream` with anything but `notifications` `StreamFailed(BadRequest)` |
| Never touch `ActorContext` from a `Future` callback | pass | the loop runs on a virtual thread and reports through `pipeToSelf`, as `AgentHost` does; state writes go through the component client |
| SSE payloads are JSON-encoded | pass | notifications are JSON strings through the existing `sse` route |
| One `TestModelProvider` per consumer; scripts fail loudly | pass | an exhausted script fails the task naming the script (FR-042); the cascade consumer calls no model |
| Two services never share a database; nothing unbounded interned | pass | per-service entities; the recorder interns component ids only — task and instance ids never |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill, `COMPONENTS` vocabulary | pass | R15 |
| Testkit-first: unit level with no runtime, whole service against Postgres | pass | R14 |

**Violations to justify**: none against these principles. Additions that widen the platform's
surface are under *Complexity Tracking*.

## Project Structure

### Documentation (this feature)

```text
specs/015-autonomous-agents/
├── plan.md              # this file
├── research.md          # R1–R16: decisions with file-level evidence and the one spike
├── data-model.md        # task type/definition, task record, instance record, session, notifications, state
├── quickstart.md        # the validation runs, spike → offline → whole service → sidecar/SDKs → by hand → docs
├── contracts/
│   ├── scala-api.md         # declaration, client, testkit additions, reserved names, errors
│   ├── protocol.md          # discovery/agent proto additions, client mapping, fixtures, versions
│   ├── sdk-api.md           # Python (full) and TypeScript (declaration + client)
│   └── conformance.md       # reference service routes and the auto.* cases
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
modules/core/src/main/scala/…/core/ComponentDescriptor.scala      # ComponentKind.AutonomousAgent (sharded)
modules/agent/src/main/scala/…/agent/
├── autonomous/
│   ├── Task.scala                      # TaskType, Task.named builder, TaskRule, Attachment
│   ├── JsonSchema.scala                # schema type class + Mirror derivation
│   ├── AutonomousAgent.scala           # AutonomousAgent, Companion, Definition, Capability, Settings, Descriptor, Context
│   ├── TaskEntity.scala                # ankka-task: record, events, handlers (data-model §2)
│   ├── InstanceEntity.scala            # ankka-agent-instance: record, events, handlers (§3)
│   ├── TaskCascade.scala               # ankka-task-cascade consumer (R5)
│   ├── AutonomousAgentHost.scala       # the sharded driver: operations, subscribers, passivation (R2, R4, R8)
│   ├── IterationLoop.scala             # one iteration: assemble, call, record, tools, built-ins, resume rule (R3, R6, R11)
│   ├── Notification.scala              # sealed trait + codec; NotificationSource (subscriber buffer, Dropped)
│   ├── AgentState.scala
│   └── Calls.scala                     # forAutonomousAgent, forTask, tasks; TaskCalls/AutonomousAgentCalls/TaskBuilder
├── AgentLoop.scala                     # replay(...) lifted to a shared PromptReplay helper
├── AgentRuntime.scala                  # hosts the kind (remember-entities, no idle passivation); descriptors += 3; warns without ProjectionRuntime
├── TestModelProvider.scala             # expectCompleteTask, expectCompleteTaskJson, expectFailTask, whenToolResult
└── resources/reference.conf            # sharding remember-entities store + plugin ids
modules/agent/src/test/scala/…/agent/autonomous/{JsonSchemaSuite, TaskTypeSuite, NotificationCodecSuite, AutonomousAgentDefinitionSuite}.scala   # need only agent
modules/testkit/src/test/scala/…/testkit/{RememberEntitiesSpike}.scala and …/testkit/autonomous/{TaskEntitySuite, InstanceEntitySuite, CallsSuite, TaskCascadeSuite, EventCompatibilitySuite, AutonomousFixturesSuite}.scala   # need testkit, which depends on agent
modules/testkit/src/test/resources/journal/autonomous-events.json
modules/runtime/…/ObservabilityEndpoint.scala                    # platform component ids list
modules/testkit/src/main/…/AnkkaTestKit.scala                    # awaitTask, eventually, startPeer
modules/testkit/src/test/…/{AutonomousAgentSuite, AutonomousAgentHandoffSuite, CatalogueAnswerer (fixture)}.scala
protocol/src/main/protobuf/ankka/protocol/v1/{discovery,agent}.proto
protocol/fixtures/{task-*, agent-*}.json ; protocol/README.md
modules/runtime/…/remote/Conversation.scala                      # checkTaskResult
sidecar/src/main/scala/…/sidecar/
├── Discovery.scala                     # kind 7, detail 17, validation, ProtocolVersion 1.2
├── RemoteAutonomousAgent.scala         # NEW: definition from discovery; tools/guardrails/rules over the conversation
├── GrpcConversation.scala              # checkTaskResult
├── ClientService.scala                 # kind AUTONOMOUS_AGENT for Invoke/InvokeStream; await poll
├── Models.scala                        # script: `when` on tool turns, `when_tool_result`
└── Main.scala                          # registers remote autonomous agents
sidecar/src/test/scala/…/sidecar/{ProcessDouble (autonomous spec + CheckTaskResult), RemoteAutonomousAgentSuite, ProtocolSuite, conformance/ConformanceSuite, conformance/ConformanceReference}.scala
controlplane-api/src/main/scala/…/api/Protocol.scala             # version 1.2
sdks/python/src/ankka/{autonomous.py, client.py, server.py, service.py, testkit/unit.py, testkit/integration.py}
sdks/python/{examples/shopping_cart/{answerer.py, endpoint.py, conformance.py, test_cart.py}, tests/test_autonomous.py}
sdks/typescript/src/{autonomous.ts, client.ts, spec.ts, service.ts, server/agent.ts}; examples/shopping-cart/{answerer.ts, conformance.ts}
samples/shopping-cart/src/main/scala/shoppingcart/{application/CatalogueAnswerer.scala, api/CatalogueEndpoint.scala, Main.scala}; src/test/…/CatalogueAnswererSuite.scala
docs/{concepts/autonomous-agents.md, build/autonomous-agents.md} (new); docs/reference/{scala-sdk, python-sdk, typescript-sdk, sidecar-protocol, akka-divergences, limitations}.md; docs/concepts/{agents, polyglot}.md; docs/build/multi-agent-orchestration.md; mkdocs.yml
tools/docs/src/ankka_docs/pages.py ; tools/docs/skill/{ankka-agents, ankka-python}/SKILL.md ; marketplace/… and ankka.g8/… (rendered by docs sync)
```

**Structure Decision**: no new module. The feature is a package in `ankka-agent` because
everything it needs — the model provider, tools, session memory, the scripted model — is there,
and `AgentRuntime` already hosts agents outside the core runtime (R1).

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| `remember-entities` (event-sourced store) on one entity type, with idle passivation disabled for it | a working instance must come back after a crash or rebalance with no caller to wake it, and must not be passivated mid-model-call | polling from callers is what workflows rely on today and is exactly the gap; a sweeper table is more code and a DDL change for a facility Pekko ships (R4, fallback kept) |
| Two platform entities and a platform consumer in `AgentRuntime.descriptors` | tasks outlive instances (a record of their own), the instance's state must be durable and testable without a runtime, and dependents must be cancelled by something that can call them | one `EventSourcedBehavior` doing loop and state repeats `WorkflowHost`+`WorkflowEngine`'s bespoke persistence (R2); an entity cannot cancel another (R5) |
| `JsonSchema[A]` with `Mirror` derivation | the model must be shown the shape of a case class result | asking developers for a hand-written schema invites the schema/decoder disagreement `SchemaType` exists to prevent (R6) |
| A `dependents` index written by the creator | the cascade must know whom to cancel | a view of tasks by dependency is a row table per service for one lookup (R5) |
| `startPeer()` on `AnkkaTestKit` | US2's rolling-deployment scenario without a k3s suite | `MultiNodeClusterSuite` is minutes and k3s for a property two `ActorSystem`s on one journal can show (R14) |

## Constitution Check (post-design)

Re-evaluated after Phase 1: unchanged, all pass. The design adds one enum case to `core`, no
dependency, no DDL, no published module, no descriptor field; the one config change is sharding's
remember-entities store in `runtime`'s `reference.conf`, which applies wherever the service runs
and so is not an overlay concern. The spec was corrected in one place during planning (FR-037: no
per-task plan request, since the definition is declared in discovery — R12).

## Not in this feature (from the spec's Out of Scope, restated for the tasks)

No delegation, handoff, team leadership or moderation (the `capabilities` list and the task's
changeable assignee are the room left); no MCP tools; no per-instance overrides; no URI fetching;
no persisted notifications; no console view of instances or tasks; no TypeScript testkit, guide,
notification-stream example or sample beyond the conformance reference's declaration; no change to
request agents or to the multi-agent planner.
