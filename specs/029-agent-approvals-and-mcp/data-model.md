# Data Model: Agent Approvals and MCP

What is recorded, where, and how it changes. No table is added and no DDL changes: everything is
in two journals that exist, `ankka-session-memory` and `ankka-agent-instance`. Decisions are in
[research.md](research.md).

## Values

### ApprovalRequest

| Field | Type | Rule |
|---|---|---|
| `id` | text | made by the platform, unique within its session or instance; opaque |
| `callId` | text | the model's id for the tool call it is for |
| `tool` | text | the tool's name as the model was offered it |
| `arguments` | text | the tool call's arguments, as rendered JSON — the form `RecordedToolCall` keeps |
| `requestedAt` | millis | |
| `expiresAt` | optional millis | present when the tool, or its MCP server, declares a time limit |
| `decision` | optional `Decision` | absent while awaiting |

Awaiting means `decision` is absent. An approval request is never edited after its decision.

### Decision

| Field | Type | Rule |
|---|---|---|
| `approvalId` | text | the request it decided; what lets a decided id be found once its turn is over |
| `approved` | boolean | |
| `by` | text | required and not blank; `ankka` for an expiry |
| `note` | optional text | told to the model with a refusal |
| `at` | millis | |
| `expired` | boolean, default false | true only for the platform's decision at a time limit |

### McpServer (a definition's, never journaled)

| Field | Rule |
|---|---|
| `name` | lower-case letters, digits, hyphens; unique within the agent |
| address | a URL, or a service (with an optional project) and a path; the variable `ANKKA_MCP_<NAME>_URL` replaces it when set |
| `approval` | optional; with an optional time limit; applies to every tool of the server |
| `headers` | each a header's name and the name of a variable starting `ANKKA_MCP_` |

A header's value is read once, at start, and is held only by the client that sends it.

## The session (`ankka-session-memory`)

### State

`SessionHistory` gains one field, absent from the wire at its default:

```text
suspended: Option[SuspendedTurn] = None
```

`SuspendedTurn`:

| Field | Meaning |
|---|---|
| `agentId` | the component id of the agent whose turn it is |
| `handler` | the handler's wire name |
| `streaming` | whether the handler was called as a stream |
| `payload` | the request's payload, base64 |
| `messages` | what the turn has produced: the user message, each model message with its tool calls, the results already made |
| `usage`, `judgmentUsage` | tokens spent so far in the turn |
| `steps` | tool-call steps taken, against `maxToolCallSteps` |
| `requests` | the turn's approval requests, in the order the model made the calls |

`SessionMessage.ToolResultMessage` gains `decision: Option[Decision] = None`.

### Events

| Event | Fold |
|---|---|
| `TurnSuspended(turn)` | `suspended = Some(turn)`; replaces a turn already there (a turn that waits a second time) |
| `ApprovalDecided(approvalId, decision)` | sets the decision on that request |
| `TurnEnded` | `suspended = None` |

A turn that ends with an answer persists its messages' events and `TurnEnded` together.

### Commands (wire names)

| Command | Refusals |
|---|---|
| `suspend-turn` | `Conflict` when a turn with a request awaiting is already suspended |
| `decide-approval` | `BadRequest` when `by` is blank; `Conflict` when the suspended turn holds the id and it is decided, or when no suspended request has the id and a tool result in `messages` carries a decision with it; otherwise `NotFound`, naming the id, or naming the session when the session is empty |
| `end-turn` | none; ending when nothing is suspended changes nothing |
| `append` | unchanged, with one new defaulted field, `endsTurn` |

### Lifecycle of a turn

```text
running ──(the model calls a tool that requires approval)──▶ suspended, awaiting
suspended, awaiting ──(a decision; another request still awaiting)──▶ suspended, awaiting
suspended, awaiting ──(the last decision)──▶ running
running ──(answer)──▶ ended: messages appended, suspended cleared
running ──(another tool that requires approval)──▶ suspended, awaiting
running ──(failure, or the service stops)──▶ ended: nothing appended, suspended cleared
```

A suspended turn with nothing awaiting and nobody running it was cut off; the next request or
decision on the session ends it first. Its decisions were never written to a tool result, so a
later `decide` for one of its ids is `NotFound`.

## The autonomous agent's instance (`ankka-agent-instance`)

`Working` gains `approvals: Vector[ApprovalRequest] = Vector.empty`.

| Event | Fold |
|---|---|
| `ApprovalRequested(request)` | appends to `current.approvals` |
| `ApprovalDecided(approvalId, decision)` | sets the decision on that request |
| `IterationStarted`, `TaskEnded` (existing) | additionally clear `approvals` |

`record` refuses `ApprovalRequested` with no current task, and `ApprovalDecided` for an id not
in `current.approvals` (`NotFound`), for a decided one (`Conflict`) and for a blank `by`
(`BadRequest`). The record cannot see a session, so the host answers a `decide` in this order:
the id in `current.approvals`; then the id on a tool result's decision in the current task's
session, which is `Conflict`; then `NotFound`, which is what a discarded request and a request of
a task that is over are.

`AgentState` gains `awaiting: Vector[ApprovalRequest]`. `Phase` is unchanged: an instance that
is awaiting a decision is `Working`, and its state says what it awaits.

### Notifications

| Case | Fields beyond the common ones |
|---|---|
| `ApprovalRequested` | `taskId`, `approvalId`, `tool`, `arguments`, `expiresAt` |
| `ApprovalDecided` | `taskId`, `approvalId`, `approved`, `by`, `note`, `expired` |

### Order of writes in an iteration that waits

1. `IterationStarted`, the model call, the model's message appended, `IterationCompleted` — as today.
2. `ApprovalRequested`, once per tool call that requires approval.
3. The other tool calls run; their results are appended.
4. Notifications; timers for the requests with a time limit.
5. On each decision: `ApprovalDecided`; then the call is run or answered as refused, and its
   result appended with the decision on it.
6. When every call of the message has a result, the next round starts the next iteration.

A stop anywhere after step 2 resumes by settling every call of the last model message that has
no result.

## Timers

One row of `ankka_timers` per approval request with a time limit, named
`approval:<component>:<session or instance>:<approval id>`, for the platform timed action
`ankka-approval-expiry`. Its payload is those three ids and which kind of agent.

## Compatibility

- Journals written before the feature read unchanged: every new field has a default and every
  new event is an added case. The pinned files gain lines for the new events;
  `SessionMemoryCompatibilitySuite` and `EventCompatibilitySuite` assert every case is pinned.
- `protocol/fixtures/autonomous/` gains a notification fixture and an `agent-state.json` with
  `awaiting`, copied to the three SDKs.
- Nothing of an MCP server is journaled except, in a tool result, what it answered and passed
  the result guardrails.
