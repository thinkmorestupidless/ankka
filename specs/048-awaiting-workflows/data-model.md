# Data Model: Awaiting Workflows

Nothing here is a table. The model is what the engine holds in memory for a wait, what the journal
and the snapshot gain so an end can be answered with its step and its reason, and the shape of the
error a caller reads.

## Waiter (in memory, never journalled)

Held by one `WorkflowEngine` for one caller awaiting that workflow.

| Field | Type | Meaning |
|---|---|---|
| `replyTo` | `ActorRef[EntityProtocol.Reply]` | the ask to answer |
| `key` | `Long` | the timer key of this hold, unique in the engine |
| `askedAtNanos` | `Long` | when the hold began; the duration `handled` is counted with |
| `metadata` | `Metadata` | the ask's metadata: the caller and the trace, for the count |

Rules:

- A waiter is added only when the run is not terminal and not deleted; otherwise the ask is
  answered at once and no waiter exists.
- A waiter lives for `min(asked hold, askTimeout / 2)`, then is answered `NotYet` and removed.
- Every waiter is answered and removed in the `thenRun` after `Ended`, `Failed` or `Deleted` is
  persisted, and from `PostStop` with `NotYet`.
- Waiters are a `Vector`: two callers for one workflow are two waiters (FR-007).

## Run (the engine's state, rebuilt from the journal)

Two fields added to `Run[S]` (`WorkflowHost.scala:32-42`):

| Field | Type | Set by | Cleared by |
|---|---|---|---|
| `failedStep` | `Option[String]` | `Event.Failed(_, step)` | `Deleted` (the empty run) |
| `deleted` | `Boolean` | `Event.Deleted` | `StateUpdated`, `TransitionedTo` |

`isTerminal` is unchanged (`Completed || Failed`). A run that is `deleted` is answered as a failure
at once; one that is `NotStarted` and not `deleted` has recorded nothing and is waited for.

## Event.Failed (journalled)

`Event.Failed(message: String, step: Option[String])`. In `WorkflowRecord` the message is `message`
and the step is `step` (empty string for none), under the existing kind `KindFailed` and manifest
`fail`. A record written before this release has an empty `step`; a runtime from before it reads
only `message`.

## WorkflowSnapshot (journalled)

Two fields appended: `failedStep: String` (empty for none) and `deleted: Boolean`. A snapshot
written before this release decodes with `""` and `false`.

## End (what a wait is answered with)

| End | Standing | Answer on the wire | Answer to a Scala caller |
|---|---|---|---|
| completed | `Completed` | `Succeeded(stateBytes, manifest + content type as metadata)` | the state, type `S` |
| failed | `Failed` | `WorkflowFailed(step, reason, deleted = false)` | `CommandError(message, WorkflowFailed, details)` |
| deleted | `deleted` | `WorkflowFailed(None, "the workflow was deleted", deleted = true)` | the same, `details("deleted") = "true"` |
| not yet | anything else, hold spent | `NotYet(heldMillis)` | nothing; the client asks again |
| timed out | — | — (the caller's deadline) | `CommandError(…, Timeout)` |
| before awaiting | an older instance | `Rejected(NotFound, "… has no handler …")` | `CommandError("… from before awaiting …", Unavailable)` |

The message of a `WorkflowFailed` error is the lifecycle query's `failure` text, so a reader of
either sees the same words.

## CommandError and ErrorCode (core)

```scala
enum ErrorCode:
  case BadRequest, NotFound, Conflict, Forbidden, Unauthorized, Internal, Unavailable, Timeout, WorkflowFailed

final case class CommandError(message: String, code: ErrorCode = ErrorCode.BadRequest, details: Map[String, String] = Map.empty)
```

`details` keys a workflow failure uses: `step` (absent when no step failed), `reason` (always),
`deleted` (`"true"` only for a deletion). `WorkflowEnd.failure(error): Option[WorkflowEnd.Failure]`
reads them into `Failure(step: Option[String], reason: String, deleted: Boolean)`; it is `None` for
any other code.

Mappings a new code needs:

| Where | `WorkflowFailed` |
|---|---|
| `HttpProblem.from` | 424, `details` in the problem body |
| `GrpcStatus` | `ABORTED` |
| `Observability.outcomeOf` | `Refused` (the call was answered) |
| `ErrorCode.retryable` | `false` |
| `Translate.toCode`/`fromCode` | `WORKFLOW_FAILED` |
| Python `ErrorCode`, HTTP map | `WORKFLOW_FAILED`, 424 |
| TypeScript `ErrorCode`, status map | `WorkflowFailed`, 424 |
| Rust `ErrorCode`, status, proto both ways | `WorkflowFailed`, 424, `WORKFLOW_FAILED` |

## Protocol (payload.proto, client.proto)

```proto
enum ErrorCode { …; UNAVAILABLE = 7; WORKFLOW_FAILED = 8; }
message Error { string message = 1; ErrorCode code = 2; map<string, string> details = 3; }

message AwaitEndRequest { string component_id = 1; string entity_id = 2; int64 timeout_millis = 3; Metadata metadata = 4; }
message StreamToken { oneof token { string text = 1; Empty completed = 2; Error failed = 3; ApprovalAwaited approval = 4; Empty heartbeat = 5; Payload ended = 6; } }
```

`AwaitEnd` answers `InvokeReply`: `reply` with the state as a `Payload`, or `error`.
`AwaitEndStream` emits `heartbeat` tokens, then exactly one of `ended` or `failed`, and completes.

## Stream events (ankka-http)

| Event name | Data | When |
|---|---|---|
| `heartbeat` | `{}` | every heartbeat interval while the wait goes on |
| `ended` | the state's JSON | the workflow completed; the stream completes after it |
| `failed` | `{"code":"WorkflowFailed","message":…,"step":…,"reason":…,"deleted":…}` | failed or deleted; the stream completes |
| `timed-out` | `{"timeout":"PT30S"}` | the caller's timeout passed; the stream completes |

The heartbeat interval is a third of `pekko.http.server.idle-timeout`, 20 seconds when infinite.

## Validation rules (from the requirements)

- A timeout is required on every await; there is no default (FR-003). A non-positive timeout is
  refused by the client with `BadRequest` before anything is sent.
- `ankka:await-end` is refused as a user handler name by `Companion.descriptor`, as `ankka:*` is.
- A heartbeat interval is never the caller's (clarification 3).
- Nothing about a wait is journalled (FR-006): a waiter has no record, no event, no snapshot field.

## State transitions touched

```text
Running ──step returns End──────────▶ Completed   answer waiters: state
Running ──retries spent / Fail──────▶ Failed      answer waiters: WorkflowFailed(step, reason)
Running/Paused ──workflow timeout───▶ Failed      answer waiters: WorkflowFailed(pending, "timed out …")
Paused ──pause timeout, no handler──▶ Failed      answer waiters
any ──command with delete()─────────▶ NotStarted+deleted   answer waiters: WorkflowFailed(deleted)
any ──actor stops (passivation, move, SIGTERM)──▶ (recovered later)   answer waiters: NotYet
```

A `Paused` run answers no waiter: the wait goes on until the end or the caller's deadline.
