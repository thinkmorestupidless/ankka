# Data Model: Autonomous Agents — Tasks and the Durable Agent Loop

**Feature**: `015-autonomous-agents` | **Date**: 2026-09-28

Two journals hold everything durable: the task record (§2) and the instance record (§3), both
ankka event sourced entities in `modules/agent`, plus the working history in session memory (§4),
which already exists. Nothing here needs DDL. §1 and §5 are declarations in code; §6 is the live
stream.

## 1. Task type and agent definition (declared, not persisted)

**`TaskType[R]`** — `Task.named(name)`:

| Field | Type | Rule |
|---|---|---|
| `name` | `String` | the wire name; non-empty; unique per service (registry-checked at startup) |
| `description` | `String` | non-empty; shown to the model |
| `result` | `Option[ResultShape[R]]` = `(JsonValueCodec[R], JsonSchema[R])` | absent ⇒ `R = String`, `complete_task` takes `{"result": string}` |
| `rules` | `Vector[TaskRule[R]]` | run in order on a decoded result; first `Rejected(reason)` wins |

**`AutonomousAgentDefinition`** — `AutonomousAgent.Companion`:

| Field | Type | Rule |
|---|---|---|
| `componentId` | `ComponentId` | as every component |
| `description` | `String` | required, non-empty |
| `instructions` | `Option[String]` | |
| `tools` | declared on the **instance** (`override def tools`), which holds the component client | names unique; `complete_task` and `fail_task` reserved; checked at startup on one instance |
| `guardrails` | `Vector[Guardrail]` | input: task instructions; output: the completion result's JSON |
| `model` | `Option[ModelProvider]` | falls back to `AgentRuntime`'s default; neither ⇒ startup failure |
| `capabilities` | `Vector[Capability]` | in this phase only `TaskAcceptance.of(taskType).maxIterationsPerTask(n)`, whose field is `budget`; at least one; a type accepted twice is refused; `budget ≥ 1` |
| `settings` | `AutonomousAgentSettings` | `approachingBudgetAt = 0.8`, `repeatedFailureAt = 3`, `maxConsecutiveFailures = 5`, `dependencyStuckAfter = 5.minutes`, `idlePassivationAfter = 120.seconds`, `dependencyPoll = 2.seconds` |

## 2. Task record (`ankka-task`, event sourced, id = task id)

**State** `TaskRecord`:

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | caller-chosen or a UUID |
| `typeName` | `String` | the task type's wire name |
| `instructions` | `String` | |
| `attachments` | `Vector[Attachment]` | `Attachment(name, contentType, content: Inline(text) \| Reference(uri))` |
| `dependencies` | `Vector[String]` | task ids; fixed at creation |
| `dependents` | `Vector[String]` | task ids that named this one (R5) |
| `status` | `TaskStatus` | `Pending, Assigned, InProgress, ResultRejected, Completed, Failed, Cancelled` — string `JsonValueCodec` in the companion (the enum-as-word rule) |
| `assignee` | `Option[Assignee(componentId, instanceId)]` | |
| `result` | `Option[String]` | canonical JSON of `R`, present iff `Completed` |
| `reason` | `Option[String]` | of the last rejection, failure, cancellation or unassignment |
| `iterations` | `Int` | updated on rejection and at terminal transitions |
| `usage` | `TokenUsage` | same |
| `createdAt, assignedAt, startedAt, endedAt` | `Option[Long]` millis | `createdAt` always present once created |

**Events** (`Codecs.serializer[TaskEvent]("task-event")`): `Created(id, typeName, instructions,
attachments, dependencies, at)`, `DependentAdded(taskId)`, `Assigned(assignee, at)`,
`Unassigned(reason, at)`, `Started(at)`, `ResultRejected(reason, iterations, usage, at)`,
`Completed(result, iterations, usage, at)`, `Failed(reason, iterations, usage, at)`,
`Cancelled(reason, at)`.

**Transitions** (anything not listed is refused with `Conflict`, and any command on an unknown
task other than `create` is `NotFound`):

| From | Command | To | Notes |
|---|---|---|---|
| — | `create` | `Pending` | refused `Conflict` if the id exists (terminal or not) |
| any non-terminal | `addDependent` | same | on a terminal task answers `AlreadyTerminal(status, reason)` so the creator cancels the dependent |
| `Pending` | `assign(assignee)` | `Assigned` | |
| `Assigned`, `InProgress`, `ResultRejected` | `unassign(reason)` | `Pending` | termination of the assignee; `assignee = None` |
| `Assigned` | `start` | `InProgress` | |
| `InProgress` | `rejectResult(reason, iterations, usage)` | `ResultRejected` | |
| `ResultRejected` | `start` | `InProgress` | the next iteration begins |
| `InProgress`, `ResultRejected` | `complete(result, …)` | `Completed` | terminal |
| `InProgress`, `ResultRejected`, `Assigned` | `fail(reason, …)` | `Failed` | terminal; `Assigned` covers input-guardrail and budget-zero refusals before any iteration |
| any non-terminal | `cancel(reason)` | `Cancelled` | terminal |
| any | `get` | — | read-only |

## 3. Instance record (`ankka-agent-instance`, event sourced, id = `<componentId>/<instanceId>`)

**State** `InstanceRecord`:

| Field | Type | Notes |
|---|---|---|
| `componentId, instanceId` | `String` | |
| `phase` | `Phase` | derived, not stored: `Idle, Working, Waiting` (queued work, nothing started)`, Suspended, Terminated` — string codec |
| `suspended` | `Boolean` | orthogonal to `current`: a suspended instance keeps its task |
| `terminated` | `Boolean` | permanent |
| `terminateWhenDone` | `Boolean` | a "run single task" instance |
| `queue` | `Vector[String]` | task ids in assignment order |
| `current` | `Option[Working]` | `Working(taskId, iteration, completed, iterationStartedAt, started, consecutiveFailures, warned: Set[Struggle])` — `iterationStartedAt` is later than anything in the task's session, which is how the resume rule tells this iteration's response from the last one's |
| `usage` | `TokenUsage` | lifetime total |
| `taskUsage` | `TokenUsage` | for the current task |
| `lastActiveAt` | `Long` | |

**Events** (`Codecs.serializer[InstanceEvent]("agent-instance-event")`): `Created(componentId,
instanceId, terminateWhenDone, at)`, `TasksAssigned(taskIds, at)`, `TaskDequeued(taskId, reason,
at)`, `TaskSelected(taskId, at)` (moved from queue to `current`, not yet started),
(a task waiting on a dependency stays queued, so a later ready one runs first), `TaskStarted(taskId, at)`, `IterationStarted(n,
at)`, `IterationFailed(n, error, at)`, `IterationCompleted(n, usage, at)`, `StruggleNoted(kind, reset)`,
`TaskEnded(taskId, outcome: Completed \| Failed(reason) \| Cancelled(reason), iterations, usage,
at)`, `Suspended(at)`, `Resumed(at)`, `Terminated(at)`.

**Commands**: `record(event)` and `get`. The host is the instance's only writer and knows what it
did, so the entity takes the events themselves and refuses what cannot have happened — anything after
termination (creation included: termination burns the id), an iteration with no task or for another task,
a task selected that was never queued, suspending twice, resuming what is not suspended. `get` on an id
never used answers an empty, idle record without persisting anything. The operations below are the host's,
each persisting through `record`:

| Command | Precondition | Effect |
|---|---|---|
| `assign(taskIds)` | not terminated; each task's type is accepted (host checks the definition); | `Created` if new, `TasksAssigned` |
| `dequeue(taskId)` | task in queue | `TaskDequeued`; if it is `current`, a flag the loop reads at its next boundary |
| `suspend` | not terminated | `Suspended`; refused `Conflict` if already |
| `resume` | suspended | `Resumed` |
| `terminate` | — | `Terminated` (idempotent: a second is a no-op reply; on a never-created id it creates it terminated); current and queued tasks are `unassign`ed on the task entity by the host |
| `state` | — | read-only, answered from the entity |

**Budget check**: `IterationStarted(n)` is refused by the fold's caller when `n > maxIterations`;
the host persists `TaskEnded(Failed("iteration budget of N exhausted"))` instead.

## 4. Working history (session memory, existing)

Session id `task:<taskId>` on `ankka-session-memory`. Written only by the host, through
`SessionMemoryEntity.append`, with `agentId` = the component id (so `MemoryFilter` works as for
any session). Contents per iteration: one `AiMessage(text, toolCalls)` and, for each domain tool
call, one `ToolResultMessage`; the built-ins' results are appended too (the rejection reason, the
decode error), so the model sees them on the next iteration. No `UserMessage` is written: the task's
instructions live on the record and are assembled fresh each iteration (R6). Compaction applies
unchanged.

## 5. Notification (live; `Codecs.serializer[Notification]("agent-notification")`)

Every notification carries `componentId`, `instanceId`, `at` (millis) and, where one applies,
`taskId`. Cases, by family:

| Family | Cases | Extra fields |
|---|---|---|
| lifecycle | `Activated`, `Deactivated`, `IterationStarted`, `IterationCompleted`, `IterationFailed`, `Suspended`, `Resumed`, `Terminated` | `iteration`, `remaining` (budget), `usage`, `error` |
| task | `TaskAssigned`, `TaskStarted`, `TaskCompleted`, `TaskFailed`, `TaskCancelled`, `TaskResultRejected`, `TaskDependencyWait`, `DependencyResolved` | `reason`, `dependencyId`, `iterations`, `usage` |
| struggle | `TaskApproachingMaxIterations`, `RepeatedIterationFailure`, `TaskDependencyStuck` | `iteration`, `budget`, `failures`, `waitedMillis` |
| stream | `Dropped` | `count` — emitted by the subscriber's buffer, never by the host |

Ordering: per instance, the order the host emits them, which is the order of the events it
persists. Delivery: at-most-once per subscriber; no replay.

## 6. Instance state as answered to a client (`AgentState`)

`phase`, `suspended`, `terminated`, `currentTask: Option[(taskId, iteration, budget)]`,
`queued: Vector[taskId]`, `usage`, `taskUsage` — a projection of §3 with the budget looked up from
the definition. `Codecs.serializer[AgentState]("agent-state")`.
