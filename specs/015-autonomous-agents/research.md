# Research: Autonomous Agents — Tasks and the Durable Agent Loop

**Feature**: `015-autonomous-agents` | **Date**: 2026-09-28

Every decision below was checked against the code in this repository (paths given) and, where it
rests on a Pekko facility ankka does not use yet, against Pekko 1.7.0's documentation and
`reference.conf`. Where a fact can only be established by running it, the item says so and the
plan's first task in that area is a spike that proves it.

## R1. Placement: everything in `modules/agent`, hosted by `AgentRuntime`

**Decision**: The task type, task entity, autonomous agent definition, instance entity, agent host
and notifications live in `modules/agent`, in a new package
`com.thinkmorestupidless.ankka.agent.autonomous`. `AgentRuntime.start` hosts autonomous agents
exactly as it hosts request agents today (`AgentRuntime.scala:70–94`: collect descriptors of the
kind, `sharding.init(Entity(EntityTypeKey(componentId)) { … })`). `AgentRuntime.descriptors` grows
by the two platform entities and the one platform consumer this feature adds (R5, R7), so a service
that already does `registerAll(AgentRuntime.descriptors)` gets them without a new line.

**Rationale**: `ankka-runtime` must not depend on `ankka-agent` (CLAUDE.md, the `RuntimeExtension`
seam), and everything here needs `ModelProvider`, `FunctionTool`, `SessionMemoryEntity` and
`TestModelProvider`. The loop is the agent module's loop. `ComponentKind` (`core`) gains one case,
`AutonomousAgent`, `sharded = true` — the runtime's `Ankka.host` already falls through
(`case _ => hosted by their own phases`) for kinds an extension hosts, so nothing in `runtime`
changes but the enum.

**Alternatives considered**: a new module `ankka-autonomous` — a fourth published library for what
is one package, and every consumer would add a dependency to get a component that the `agent`
runtime hosts anyway. Rejected.

## R2. The instance is an entity; the loop is a driver

**Decision**: Two sharded actors per instance, both keyed by the instance id:

- **`ankka-agent-instance`**, a platform-registered `EventSourcedEntity` (SDK-defined, like
  `SessionMemoryEntity`) holding the durable state the spec's FR-024 and FR-029 name: phase, the
  queue, the current task and the iterations spent on it, consecutive iteration failures, the
  struggle flags, token usage, suspended, terminated, and whether the instance terminates itself
  when its queue drains (a "run single task" instance). Its entity id is
  `<componentId>/<instanceId>`, because one entity type serves every autonomous agent.
- **the agent host**, one non-persistent sharded actor per autonomous agent component
  (`EntityTypeKey(componentId)`, entity id = instance id), the same shape as `AgentHost`
  (`AgentRuntime.scala:160–346`): it takes the client operations, runs the iteration loop on a
  virtual thread, writes every state change through the instance entity with the component
  client, and pushes notifications to its subscribers. On start (and on every restart) it reads
  the instance entity's state and resumes from it (R4).

**Rationale**: The host is the single writer of the instance entity (every client operation
arrives at the host, which persists then acts), so the entity is the host's durable memory and
nothing else. Splitting them buys three things for one local ask per state change: the state fold
is an ordinary entity — `EventSourcedTestKit` drives it with no runtime, its events are ankka JSON
under ankka's manifest through the existing journal adapters (`EventSourcedEntityHost.scala:265–296`),
and `EventCompatibilitySuite`-style pins apply unchanged; the host stays the ~300-line actor
`AgentHost` already is rather than a second `WorkflowHost`+`WorkflowEngine` (600 lines with its
own event adapter, snapshot adapter and `WorkflowRecord` kinds); and the `getState` query is a
plain entity query any node can answer without waking the host.

**Alternatives considered**: One `EventSourcedBehavior` doing both, as `WorkflowHost` does —
correct, and twice the bespoke persistence code for no property the split lacks. Driving the loop
from a `Consumer` of the instance's events — a consumer runs on one node
(`ShardedDaemonProcess`) and would serialise every instance in the service through one actor.

## R3. Iteration durability: session memory is the iteration log; the instance journals the boundaries

**Decision**: A task's working history is session memory, session id `task:<taskId>` (FR-023). The
model's response and the tools' results go there through `SessionMemoryEntity.append`
(`SessionMemoryEntity.scala:79–92`), which already persists a turn atomically. The instance entity
records only iteration *boundaries*: `IterationStarted(n)` before the model call,
`IterationCompleted(n, usage)` after the response has been appended. Per iteration the order is:

1. persist `IterationStarted(n)` (budget checked here: `n > budget` fails the task instead);
2. call the model (retried with backoff on a transport failure, R11);
3. `append(AiMessage(text, toolCalls), usage)` to the task's session;
4. persist `IterationCompleted(n, usage)`;
5. run the tool calls, built-ins included (R6); `append(ToolResultMessage…)` for the domain tools;
6. go to 1, or end the task (R6).

**Resume rule** (FR-022), applied by the host whenever it starts with a task in progress: read the
instance state and the *tail* of the task's session.

| instance says | session's last message | do |
|---|---|---|
| iteration n started, not completed | not an `AiMessage` (a tool result, or the user turn) | the model call never landed: call the model for n again |
| iteration n started, not completed | an `AiMessage` | the response landed and the crash was between 3 and 4: persist `IterationCompleted(n)` and continue at 5 |
| iteration n completed | an `AiMessage` with tool calls | the tools did not all land: run them again (at-least-once) and continue |
| iteration n completed | `ToolResultMessage`s, or an `AiMessage` without tool calls | start iteration n+1 |

The rule inspects only the last message, so compaction — which keeps the most recent messages
verbatim (`CompactionSettings.keepRecentMessages`) — cannot confuse it. The task's instructions,
attachments and dependency results are not in the session at all: the host assembles them from the
task record on every iteration (R6), which is what keeps them whole through compaction.

**Rationale**: This is the cheapest arrangement that makes "a recorded iteration is never paid for
twice" (SC-003) exact: the model call is the expensive step, and it is bracketed by two durable
writes whose relative order tells the resumer what happened. It reuses the journal that already
exists for conversations, with compaction and observation for free, rather than adding a second
iteration store. The tolerance ("an `AiMessage` at the tail means the response landed") is the same
shape as `RemoteEventSourcedHost.snapshotWhen` accepting a snapshot at `seq` or `seq - 1`.

**Alternatives considered**: journaling the model response in the instance entity too — doubles
the storage of every conversation for a resume rule that needs one message. Journaling tool
results before running tools (exactly-once tools) — that makes every tool a durable step, which is
a workflow, and the spec chose at-least-once.

## R4. Waking a working instance after a crash: `remember-entities`, event-sourced store, over the existing journal

**Finding**: Nothing in the runtime wakes a passivated or restarted entity on its own. A workflow
mid-step survives a restart only because something messages it — `WorkflowSuite`'s restart test
polls `status`, and the polling is what resumes it (`WorkflowEngine.onRecovered`,
`WorkflowHost.scala`, `RecoveryCompleted`). `remember-entities` is not enabled anywhere.

**Decision**: The agent host's entity type is initialised with
`ClusterShardingSettings(system).withRememberEntities(true).withRememberEntitiesStoreMode(RememberEntitiesStoreModeEventSourced)`
— per entity type, in code, so no other component's sharding changes and `reference.conf` is
untouched. Remembering entities turns off automatic passivation for that type, and the host
passivates itself: `Passivate` after an idle timeout when it has no current task, nothing queued
and no subscriber. A remembered entity is started on every node that takes its shard and forgotten
when it passivates, which is precisely "a working instance comes back; an idle one costs nothing".

**Rationale**: The host's own loop keeps it busy for minutes between messages the shard delivers
(a model call can take the whole `modelTimeout`, two minutes by default), and Pekko's idle strategy
counts only messages delivered through the shard — a working host would be passivated mid-task.
Disabling idle passivation and remembering entities are the two halves of the same fix, and
`remember-entities` is Pekko's own mechanism for "keep this entity running through rebalances and
restarts". The event-sourced store uses the journal that is already there; `ddata` mode is not
durable across a full restart.

**Proven by spike (T001, `RememberEntitiesSpike` in `modules/testkit/src/test`, 2026-09-28)**:
with `ClusterShardingSettings(system).withRememberEntities(true)
.withRememberEntitiesStoreMode(RememberEntitiesStoreModeEventSourced)` on the entity type — and
nothing in `reference.conf` — an entity that was running when `restartService()` terminated the
system is started again on the fresh system with no message sent to it, and one that had
`Passivate`d before the restart is not. The journal afterwards holds `/sharding/<type>Coordinator`
and `/sharding/<type>Shard/<n>` persistence ids in ankka's ordinary `event_journal`, with no DDL
change and the default journal plugin (no `journal-plugin-id` needed). The coordinator's list of
shards is event-sourced as well in this store mode — it does **not** depend on the LMDB durable
distributed data that `state-store-mode = ddata` would otherwise use, and no LMDB directory was
created, so a pod that loses its disk loses nothing. The deprecated `state-store-mode =
persistence` also works and is not needed. Remember-entities disables automatic passivation for the
type, so the host's own `Passivate` is the only way an instance leaves memory — which is the
design. The original question, kept for the record: that pekko-persistence-r2dbc accepts sharding's
remember-entities persistence ids (`/sharding/<typeName>Shard/<id>`) in ankka's `event_journal`
table without a DDL change, and that a host stopped by `AnkkaTestKit.restartService()` comes back
with no message sent to it. If the spike fails, the fallback is an `ankka_agent_instances` table
(additive DDL) and a cluster-singleton sweeper that nudges every listed instance — the
`TimerRuntime` shape — and the plan's tasks change in one place.

**Alternatives considered**: The sweeper table first — more code and a DDL change for a facility
Pekko ships. Durable in-memory timers — none exist; `TimerRuntime` drops a `DeferredCall` whose
target is not a timed action (`TimerSweeper.scala:123–158`).

## R5. Tasks: a platform entity, dependencies by construction, cascade by a consumer

**Decision**: `ankka-task` is a platform-registered `EventSourcedEntity` (`AgentRuntime.descriptors`),
entity id = task id. Its record and events are in `data-model.md` §2. Three consequences of the
spec's rules fall out of how tasks are created:

- **Cycles are impossible.** A task's dependencies are fixed at creation and must already exist
  (FR-008), and a task's dependency list never changes, so no task can depend on one created after
  it. FR-008's cycle clause is satisfied by construction; `TaskCalls.create` checks existence only.
- **Dependents are indexed on the dependency.** After creating `D` with dependency `X`,
  `TaskCalls.create` calls `X.addDependent(D)`. If `X` is already failed or cancelled, that call
  answers so and `create` cancels `D` at once with the reason. The index is what makes the cascade
  possible without a search.
- **The cascade is a consumer.** `ankka-task-cascade`, a platform consumer of `ankka-task` events,
  reacts to `Failed` and `Cancelled` by cancelling every dependent (through `TaskCalls.cancel`, so
  an assignee host is nudged as a caller-initiated cancel would). It needs a `ProjectionRuntime`,
  as compaction does; `AgentRuntime.start` warns when an autonomous agent is registered and no
  projection runtime is, the message compaction already has (`AgentRuntime.scala:104–117`).

**Waiting on a dependency**: the host, not the consumer. A host whose next queued task has an
incomplete dependency emits `TaskDependencyWait` and polls the dependency's record on an in-memory
timer (every 2s) until it is completed — then starts the task with the dependency's result in its
context — or failed/cancelled, in which case the cascade has already cancelled the dependent and
the host sees `cancelled` and drops it. Polling a local entity every two seconds costs nothing
worth a subscription mechanism.

**Rationale**: An entity cannot see another entity's state (CLAUDE.md: cross-entity checks live in
the endpoint), so "cancel my dependents" has to be performed by something that can call them. A
consumer is the platform's way to react to an event on one node; the index is the cheapest way to
know whom to call. The client-side ordering (create, then index, then check terminal) closes the
race with a dependency that fails between the two.

**Alternatives considered**: a `View` of tasks by dependency — a row table per service for one
query the entity can answer itself. Making the host cancel dependents of its own tasks — leaves
unassigned dependents `pending` with a failed dependency, which the record would misreport.

## R6. The loop's request, the built-in tools, and the result check

**Decision**: Each iteration's `ModelRequest` is assembled by the host from four parts, in this
order, using the types in `messages.scala`:

- **system**: the agent's description and instructions; the task type's name and description; the
  result schema when there is one ("call `complete_task` with a result of this shape"); the budget
  line "iteration n of N" (and at ≥ 80% "you have k iterations left");
- **first user turn**: the task's instructions; each attachment (inline content as text under its
  name; a URI as "`<name>`: available at `<uri>`"); each dependency's result as "result of task
  `<id>` (`<type>`): `<json>`"; and, for a result-rejected task, the rejection reason;
- **the session** replayed as `AgentLoop.replay` does (`AgentLoop.scala:222–255`, lifted into a
  shared helper so both loops use one);
- **tools**: the agent's `FunctionTool`s plus two built-ins the host constructs per task:
  `complete_task` (input schema = the task type's result schema, or `{"result": string}` when the
  type has none) and `fail_task` (`{"reason": string}`).

The built-ins are intercepted by the host, never dispatched as tools: a `complete_task` call decodes
the arguments with the type's `JsonValueCodec[R]`; a decode failure is appended as an error tool
result and the loop continues (FR-017); a decoded result runs the type's rules in order, then the
output guardrails on the result's JSON; a rejection writes `rejectResult(reason)` to the task and
appends the reason as the tool's error result; an accepted result writes `complete(result)` to the
task and then `TaskEnded` to the instance. Any other tool calls in the same response are answered
with an error result ("the task is complete") and not run. `fail_task` writes `fail(reason)`. The
task entity is written **before** the instance (write the record first, then the bookkeeping): on
resume the host reads the task record and, if it is terminal, persists `TaskEnded` and moves on.

**The result schema in Scala** needs a JSON Schema for `R`. `SchemaType` (`SchemaType.scala`)
covers scalars, `Option` and `List` — no products. This feature adds `JsonSchema[A]`, a schema-only
type class with the same scalar instances plus `Vector`, `Map[String, _]` and a `Mirror`-based
`derived` for case classes (field names, nested products, `Option` → not required). Decoding stays
jsoniter's codec (`Codecs.make`), so schema and decoder come from the same case class declaration;
the schema is a description for the model and the codec is the truth, and a value the model sends
that the codec refuses goes back to it as the tool's error. `Task.resultConformsTo[R]` takes both
as givens. Python derives from the dataclass with the SDK's existing `_schema_for`
(`agent.py:62–92`); TypeScript from `s.record` with `toJsonSchema` (`spec.ts:102–117`).

**Rationale**: Completion through a tool, not prose, is what makes the end of a task unambiguous
and typed, and it is what Akka does (`AutonomousAgentTools.completeTask`). Intercepting rather than
dispatching keeps rules and guardrails in the host, where the task entity is written, and keeps
`FunctionTool` untouched.

## R7. Platform components this feature registers

| Component | Kind | Id | Registered by |
|---|---|---|---|
| task | event sourced entity | `ankka-task` | `AgentRuntime.descriptors` |
| instance | event sourced entity | `ankka-agent-instance` | `AgentRuntime.descriptors` |
| cascade | consumer of `ankka-task` | `ankka-task-cascade` | `AgentRuntime.descriptors` |
| each autonomous agent | `ComponentKind.AutonomousAgent`, sharded host | the developer's id | the developer, `register(QuestionAnswerer.descriptor)` |

`ObservabilityEndpoint.scala:321` hard-codes `ankka-session-memory` as a platform component the
console groups; the two new entity ids and the consumer join that list.

## R8. Notifications: a stream command to the host, subscribers held as watched refs

**Finding**: There is no cluster pub/sub in the codebase — no `Topic`, `DistributedPubSub`,
`SourceRef` or `BroadcastHub`. The one cross-node push is `InvokeStream`'s
`tokens: ActorRef[StreamToken]` (`wire.scala:79–112`), materialised on the caller's node by
`ActorSource.actorRef` (`AgentRuntime.scala:374–398`) and serialised as an `ActorRef`, which the
comment there explains is why it is not a `SourceRef`.

**Decision**: Subscribing is a `ModuleCommand` to the host, `Subscribe(subscriber:
ActorRef[NotificationEnvelope])`, sent through the sharded transport exactly as `InvokeStream` is.
The host `watch`es each subscriber, pushes every notification to every subscriber as it emits it,
and drops a subscriber on `Terminated`. The subscriber side is a small typed actor materialised as a
`Source[Notification, NotUsed]` with a bounded buffer (1024) that, when full, drops its oldest
buffered event, counts, and emits one `Dropped(count)` notification ahead of the next delivered
event (FR-036) — the same place `ActorSource.actorRef` sits today, with a counting buffer instead
of `OverflowStrategy.fail`. A subscription keeps the host from passivating (R4); an idle host with
subscribers is not costing a journal read, only an actor.

Over the sidecar it is `Client.InvokeStream` with `kind = AUTONOMOUS_AGENT`, `name =
"notifications"`, each token a JSON notification (R10) — no new rpc.

**Rationale**: It is the mechanism that exists, it already crosses nodes, and a subscription that
wakes the instance is the honest semantics of "watch this instance". A per-instance `Topic` would
decouple subscribers from the host's lifetime, at the price of a Receptionist registration per
active instance and a second streaming mechanism to document.

**Alternatives considered**: Pekko typed `Topic` per instance (above). Persisting notifications
and serving them as a projection — the spec says live only, and the task record is the durable
account.

## R9. Client operations and cancellation

**Decision**: `forAutonomousAgent(componentId)(instanceId)` and `forTask(taskId)` are extension
methods on `ComponentClient` in `agent`, as `forAgent` is (`AgentRuntime.scala:406–411`). Every
host operation is an `EntityProtocol.Invoke` with a reserved method name (`assign`, `suspend`,
`resume`, `terminate`, `state`, `dequeue`), decoded by the host from `MethodName` as `AgentHost`
decodes handler names; `state` is answered by the host from the instance entity (or, when the host
is not active, the caller may query the entity directly — `AutonomousAgentCalls.state` does the
entity query so an idle instance is not woken by a status check). Task operations are ordinary
`CommandHandle`s on `TaskEntity`'s companion, so the sidecar's `Client.Invoke` with
`kind = EVENT_SOURCED_ENTITY, component_id = "ankka-task"` reaches them with no new rpc.

`runSingleTask(task)`: `TaskCalls.create` then `assign` to a host with a generated instance id
(`UUID`), flagged `terminateWhenDone`; the task record's `assignee` names the instance so its
notifications can be found.

**Cancel** (clarification Q2): `TaskCalls.cancel(reason)` writes `cancel` to the task entity (refused
if terminal), then, if the record names an assignee, tells that host `Dequeue(taskId)`. A host
receiving `Dequeue` for a queued task removes it (persisting `TaskDequeued`); for the task in
progress it sets a flag the loop reads at its next boundary (after step 5 or before step 1), where
it re-reads the task record, sees `cancelled`, and ends the task locally with `TaskEnded(Cancelled)`.
The record is the truth; the nudge is promptness.

**Await** (`TaskCalls.await(timeout)`): polls the record every 250ms on the caller's virtual thread
until terminal; the sidecar does the same in `ClientService` for the SDKs, so no long-poll rpc is
needed. Default timeout the `ankka.ask-timeout`'s big brother, 10 minutes, as `RemoteWorkflowHost`
caps a step.

## R10. Wire formats

- **Notifications** are a sealed trait with one `Codecs.serializer[Notification]("agent-notification")`,
  discriminated by `type` under ankka's shared config — the enum-as-`{"type":…}` shape is right here
  because every case carries fields. Python and TypeScript decode the same JSON.
- **Instance and task events** are ankka JSON under manifests `agent-instance-event` and
  `task-event`; `EventCompatibilitySuite`'s pattern (a fixture of every event's JSON, decoded and
  re-encoded) pins them from the first release, in `modules/agent`.
- **A task's result** is stored as the JSON string the model's `complete_task` arguments produced,
  re-encoded through `R`'s codec so it is canonical, with the type's name beside it; readers decode
  with the type. A task type with no result shape stores the `result` string.
- **The task type's wire name** is the `Task.named("…")` name; the Scala value name is free.

## R11. Iteration failures and struggle signals

**Decision**: A model call that throws or times out (`ModelCallFailed`, `TimeoutException`, as
`AgentLoop.runToolLoop` catches them, `AgentLoop.scala:281–302`) is an *iteration failure*: the host
persists `IterationFailed(n, error)`, emits the notification, waits `1s × 2^k` capped at one minute
(in-memory timer, the host is alive), and calls the model again for the same iteration — no new
`IterationStarted`, so the budget is untouched (FR-020). `consecutiveFailures` is in the instance
state; at 3 the host emits `RepeatedIterationFailure` once; at 5 it fails the task with the last
error. A refusal (`StopReason.Refusal`) is not a transport failure: it is recorded as the response
and the loop continues, since a model that declines one iteration may complete the next; the
iteration budget is what bounds a model that keeps declining.

`TaskApproachingMaxIterations` fires once when `n ≥ ceil(0.8 × budget)`, reset by a result
rejection (the task "resumes" in the spec's words). `TaskDependencyStuck` fires once after a task
has waited 5 minutes on a dependency. All three thresholds are `AutonomousAgentSettings` on the
definition with those defaults.

## R12. Polyglot: the loop stays in the sidecar; discovery declares the definition

**Finding**: `RemoteAgent` (in `sidecar`, not `runtime/remote`) asks the process to *plan* each
request because a request agent's effect is per request. An autonomous agent's definition is
static — description, instructions, tools, guardrails, model, task types, budgets, rules — so there
is nothing to plan per task: discovery carries the definition, and the sidecar builds the
in-process `AutonomousAgentDescriptor` from it as `RemoteAgent.spec` builds an `AgentDescriptor`
(`RemoteAgent.scala:142–156`).

**Decision**: `discovery.proto` gains `Kind.AUTONOMOUS_AGENT = 7` and `AutonomousAgentDetail
autonomous_agent = 17` (`contracts/protocol.md`); `agent.proto` gains one unary rpc,
`CheckTaskResult`, beside `InvokeTool` and `CheckGuardrail`; the `Conversation` trait gains
`checkTaskResult`. Tools and guardrails are invoked through the existing rpcs with the task's session
id. Nothing else in the conversation changes; the process is asked for three things: run a tool,
check a guardrail, check a rule. The spec's FR-037 said "plan a task's model request" — corrected
in the spec to match, since a definition declared in discovery leaves nothing to plan.

The sidecar's `Discovery.validate` gains the autonomous checks (duplicate task types, empty
description, budget ≤ 0, a rule or tool named that is not declared, a schema that is not JSON) and
`kindOf`/`kindTo` gain the case — both currently fall through to `Agent` for an unknown kind, which
would host an autonomous agent as a request agent and fail its first task obscurely.

**The scripted model over the sidecar**: `ANKKA_MODEL_SCRIPT` (`Models.scala`) already scripts a
tool call turn, so `{"tool": "complete_task", "arguments": {…}}` completes a task with no new
syntax. Two additions for FR-040: a `when` on a tool turn (today `when` only pairs with `text`) and
`when_tool_result: <substring>` matching the latest tool result in the request, both on the same
`TestModelProvider.whenRequest` the Scala kit uses.

**Protocol version** goes 1.1 → 1.2: a new kind, a new detail, a new rpc and new fixtures are all
additive (`protocol/README.md`'s minor rule). `Discovery.ProtocolVersion`, `controlplane-api`'s
`Protocol.version`, the Python and TypeScript constants and the docs (which still say 1.0 in two
places — fixed in passing) move together.

## R13. SDK shapes

**Python** (full, per clarification Q1): `AutonomousAgent` class with class vars `component_id`,
`description`, `instructions`, `tools`, `guardrails`, `accepts: list[TaskAcceptance]`; `TaskType`
dataclass with `name`, `description`, `result: type | None`, `rules: list[TaskRule]`; `to_component()`
renders the detail with the schema from `_schema_for(result)`. The client gains `for_autonomous_agent`
and `for_task` with the operations in `contracts/sdk-api.md`; the server gains `CheckTaskResult` on
`AgentServicer`. Unit testkit: `AutonomousAgentTestKit.of(cls)` runs tools, guardrails and rules
directly (`run_tool`, `check_rule`) — the loop is the sidecar's and is not re-implemented in Python;
the integration testkit gains `await_task(task_id, timeout)` and `notifications(component,
instance)` and the docs show the scripted-sidecar shape `test_cart.py` already uses.

**TypeScript** (declaration and client only, per Q1): `AutonomousAgent` static members mirroring
Python, `registerAutonomousAgent`, `spec.ts` rendering, `server/agent.ts` gaining `handleTaskRule`;
`client.ts` gaining `forAutonomousAgent` and `forTask` including `notifications()` over
`invokeStream`. No testkit additions, no guide, no sample beyond the conformance reference's
declaration (R14).

## R14. Tests and samples

- **Unit** (`modules/agent/src/test`): `TaskEntitySuite` and `InstanceEntitySuite` on
  `EventSourcedTestKit` — every transition and refusal in `data-model.md`; `JsonSchemaSuite`;
  `NotificationCodecSuite`; `AutonomousAgentDefinitionSuite` (validation).
- **Whole service** (`modules/testkit/src/test`): `AutonomousAgentSuite` on `AnkkaTestKit` with
  `TestModelProvider` — the acceptance scenarios of US1–US7, including `restartService()` mid-task
  for US2 (which proves R4's spike in the same test) and a subscriber joining mid-run for US6.
  `AnkkaTestKit` gains `awaitTask` and a shared `eventually` — today every suite copies one.
- **Two nodes**: `AutonomousAgentHandoffSuite` starts a second service on the testkit's Postgres
  with `AnkkaTestKit.startPeer()` (new: a second `Ankka.service` on the same database joining the
  first's cluster by seed node, the shape `TlsClusterFormationSuite` uses for two systems on one
  machine), runs a task on the first, stops the first, and asserts the second finishes it. That is
  US2 scenario 4 without k3s.
- **Sidecar**: `RemoteAutonomousAgentSuite` on `ProcessDouble` (which gains an autonomous agent
  spec and a `CheckTaskResult` servicer) for the process-side faults — a rule check that never
  answers, a tool that throws, a process restart mid-task; `ConformanceSuite` gains the `auto.*`
  cases in `contracts/conformance.md` on the reference services of all three targets.
- **Samples**: the question answerer joins the shopping cart in Scala (`samples/shopping-cart`,
  `CatalogueAnswerer`: tools `count_items`, `cart_total`) and Python
  (`sdks/python/examples/shopping_cart/answerer.py`), each with an endpoint route that runs a task,
  one that reads it and one SSE route for notifications, and tests with `docs:start` regions the
  build page includes. TypeScript's sample gets the declaration only, so the build page's tabs are
  Scala and Python with a line saying so. The multi-agent planner is untouched.

## R15. Documentation surface

New: `docs/concepts/autonomous-agents.md` (kind concept; the component, tasks, the loop, budgets,
crash semantics, when to choose it) and `docs/build/autonomous-agents.md` (kind guide; declaring a
task type and an agent, running and reading a task, notifications over SSE, testing, in Scala and
Python tabs). Changed: `reference/scala-sdk.md`, `reference/python-sdk.md`,
`reference/typescript-sdk.md` (an "Autonomous agent" section each, TypeScript's saying what it
has), `reference/sidecar-protocol.md` (generated table plus prose for the new detail and rpc, and
the 1.0 → 1.2 corrections), `reference/akka-divergences.md` (summary rows and a section:
declared definition and explicit registration, `Task.named` wire names, at-least-once tools stated,
no delegation yet), `reference/limitations.md` (delegation, handoff, teams, moderation, MCP,
overrides, URI fetching, console, the TypeScript testkit), `concepts/agents.md` (one paragraph
pointing at the other kind), `build/multi-agent-orchestration.md` (when a workflow is still the
answer). `tools/docs/src/ankka_docs/pages.py` `COMPONENTS` gains `autonomous-agent`; `mkdocs.yml`
nav gains both pages; `tools/docs/skill/ankka-agents/SKILL.md` and `ankka-python/SKILL.md` list
them. `just docs-sync` refreshes the rendered skills in `marketplace/` and `ankka.g8/`.

## R16. Version

The protocol minor increments (R12). The runtime's version is the next minor after the release
that carries feature 014, from the tag; `Compatibility` needs no change — a descriptor declaring
this runtime is checked as any other.
