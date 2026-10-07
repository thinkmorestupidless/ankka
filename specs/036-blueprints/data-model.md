# Data Model: Blueprints

Values are encoded with the shared codec config (`core/Serializer.scala:53-71`); status words have a
string codec in their companion, as `TaskStatus` has. New fields on existing records have defaults.

## Values

### Blueprint

| Field | Type | Rule |
|---|---|---|
| `name` | String | lower-case letters, digits and hyphens; the entity id |
| | | worker and step names unique; no step named `input` |
| `input` | Shape | the input shape of its runs (R9) |
| `workers` | Vector[Worker] | names unique |
| `steps` | Vector[Step] | names unique; at least one |
| `schedule` | Option[Schedule] | |
| `runBudget` | Option[Int] | model calls for the whole run; positive |
| `timeLimit` | Option[Duration] | positive |

### Worker

| Field | Type | Rule |
|---|---|---|
| `name` | String | |
| `instructions` | String | not blank |
| `model` | String | a name in the registry; `default` when left out |
| `tools` | Vector[String] | names in the registry |
| `guardrails` | Vector[String] | names in the registry |
| `budget` | Int | positive; model calls per step for this worker |

### Step

| Field | Type | Rule |
|---|---|---|
| `name` | String | unique; not `input` |
| `does` | Action | |
| `over` | Over | default `Once` |
| `until` | Option[Until] | only with an ask or a work |
| `reads` | Vector[String] | `input` or names of earlier steps, optionally `.field` |
| `result` | Shape | |

### Action (what the step does once)

| Case | Fields | Rule |
|---|---|---|
| `Ask` | `worker` (optional) | a worker, unless `over` is `Workers` |
| `Work` | `worker` (optional) | as `Ask` |
| `Judge` | `questions` | at least one, each in the registry; not over workers |
| `Call` | `handler` | in the registry; not over workers |

### Over (how many times, over what)

| Case | Fields | Rule |
|---|---|---|
| `Once` | | |
| `Each` | `read`, `limit` (default 4), `keepGoing` (default false) | `read` resolves to an array shape |
| `Workers` | `workers`, `chosenBy` (optional read) | at least two, or one with `chosenBy`; `chosenBy` resolves to an array of strings (R22); the action names no worker |
| `Times` | `n` | above one |

A step that repeats gives a list: `Each` a list of what each doing gave, `Workers` a list of
`{worker, result}` and `Times` a list of `{n, result}`, so a reader can tell the answers apart. The
step's `result` is the whole of it; one doing answers with its item (`Over.itemOf`), and the check
refuses a repeating step whose result shape is not that list. The builders wrap the result set so
far when an `over` is added (`Over.resultOf`), so `.result(item).each("list")` and
`.each("list").result(arr(item))` say the same.

### Until (a verdict within rounds)

| Field | Type | Rule |
|---|---|---|
| `verdict` | `Critic(worker)` or `Judgment(question)` | a critic is not the drafter; a judgment is a yes/no question |
| `rounds` | Int | positive |
| `keepLast` | Boolean | default false |

The named patterns are builders: `ask`, `work`, `judge`, `call`, `forEach` (an ask over each item),
`gather` (an ask over workers, or one worker so many times), `critique` (an ask until a verdict).

### Schedule

| Field | Type | Rule |
|---|---|---|
| `cadence` | `Every(hours or days)` or `Weekly(day, localTime)` | |
| `zone` | ZoneId | a zone the JVM knows |
| `catchUp` | `one` or `each` | default `one` |

### Shape

A JSON Schema subset (R9): `type`, `properties`, `required`, `items`, `enum`, `description`.

### Verdict (platform shape of a critic's answer)

`{ "passed": boolean, "reasons": [string] }`

### Period (the input of a scheduled run)

`{ "from": instant, "to": instant, "dueTimes": [instant] }`

### RunRef (what a tool is told)

`runId`, `step`, `blueprint`, `version`.

## `ankka-blueprint` (event sourced, platform)

Id: the blueprint's name.

### State

`versions: Vector[VersionRecord(number, canonical, digest, registeredAt)]`, `lastPeriodEnd: Option[Instant]`,
`nextDue: Option[Instant]`.

### Events

| Event | Fold |
|---|---|
| `VersionRegistered(number, canonical, digest, registeredAt)` | appended to `versions` |
| `ScheduleAdvanced(periodEnd, nextDue)` | `lastPeriodEnd`, `nextDue` set |
| `ScheduleStopped` | `nextDue` cleared |

### Commands (wire names)

| Command | Refusals |
|---|---|
| `register` (canonical, digest) | none: an equal digest answers the current version |
| `advance-schedule` | a period end before the last |
| `stop-schedule` | |
| `get` (query) | |
| `version` (query, number) | `NotFound` |

## `ankka-run` (event sourced, platform)

Id: the run id. Written only by its host through `record(event)`, except `start` and `cancel`.

### State

`blueprint`, `version`, `input`, `status` (RunStatus), `steps: Vector[StepRecord]`, `startedAt`,
`endedAt`, `reason`, `deadline`, `cancelRequested`.

`StepRecord`: `name`, `status`, `items: Vector[ItemRecord]`, `rounds: Vector[RoundRecord]`,
`result`, `sessions`, `usage`, `judgmentUsage`, `waiting: Vector[ApprovalRef]`.

### RunStatus

`running`, `waiting-for-decision`, `completed`, `failed`, `cancelled`.

### Events

| Event | Fold |
|---|---|
| `Started(blueprint, version, input, startedBy, deadline)` | status running |
| `StepStarted(step)` | a step record |
| `ItemEnded(step, index, result or failure, session, usage)` | the item recorded |
| `RoundEnded(step, round, draft, verdict, sessions, usage)` | the round recorded |
| `WaitingForDecision(step, session, approvalId)` | the request added; status waiting |
| `DecisionReceived(step, approvalId)` | the request removed; status running when none waits |
| `ApprovalsRefused(step, approvalIds)` | the requests the host refused when the run ended |
| `StepEnded(step, result, usage, judgmentUsage)` | step ended |
| `CancelRequested(by)` | flag |
| `Ended(status, reason)` | completed, failed or cancelled |

### Commands (wire names)

| Command | Refusals |
|---|---|
| `start` | `BadRequest` when the input does not have the input shape; an existing run with the same blueprint and input answers itself, with a different one `Conflict`; a run id starting `schedule:` from a caller `BadRequest` |
| `record` | from anyone but the host: `Forbidden`; after `Ended`: `Conflict` |
| `cancel` | an ended run: answered as it ended |
| `get` (query) | |

## `ankka-run-host` (sharded, remember-entities)

Id: the run id. Started by `start`; stops itself when the run has ended.

## `ankka-blueprint-ask` (request agent, platform)

One handler, `turn`, input `WorkerTurn(worker, model, tools, guardrails, budget, input, shape, run)`.
Sessions `run:<runId>:<step>:<worker>[:<item>]`.

## `ankka-blueprint-worker` (autonomous agent, platform)

Accepts a task of the platform type `blueprint-step` that carries a `TaskDefinition` (instructions,
model name, tool names, guardrail names, budget, result shape, run reference); refuses one that does
not.

## `ankka-blueprint-runs` (view, platform)

Row: `runId`, `blueprint`, `version`, `status`, `startedAt`, `endedAt`. Source: `RunEntity` events.

## `ankka-blueprint-schedule` (timed action, platform)

Timer `schedule:<blueprint>:<due epoch millis>`, payload the blueprint name and the due time.

### TaskDefinition (on `ankka-task`, for a work step)

| Field | Type | Rule |
|---|---|---|
| `instructions`, `model`, `tools`, `guardrails`, `budget` | the worker's | resolved by the service's `TaskDefinitionResolver` |
| `resultSchema` | Json | the step's shape, or its items' |
| `context` | Map[String, String] | `run`, `step`, `blueprint`, `version` |

## Changes to existing records

| Record | Change | Default |
|---|---|---|
| `TaskRecord`, `TaskEvent.Created`, `TaskEntity.Create` | `definition: Option[TaskDefinition]` | `None` |
| `AgentEffect` | `maxToolCallSteps: Option[Int]` (not stored) | `None` |
| `ViewDescriptor`, `AgentDescriptor`, `AutonomousAgentDescriptor` | `platform: Boolean` | `false` |

## Compatibility

New journal files pinned under `modules/testkit/src/test/resources/journal/`: a blueprint's events, a
run's events, and a task `Created` with and without a definition, read by `EventCompatibilitySuite`.
A task journal written before this feature reads with `definition = None`.
