# Contract: the runtime's wire — the reserved method, the replies, the client's loop

Between a `ShardingTransport` on one instance and a `WorkflowEngine` on any instance, including one
from the previous release.

## The ask

```scala
EntityProtocol.Invoke(
  method   = "ankka:await-end",          // WorkflowLifecycle.AwaitEnd
  payload  = "<remaining millis>".getBytes(UTF_8),   // what the caller still has; the engine holds at most askTimeout / 2
  metadata = MetaEntry.from(Trace.outbound(metadata)), // ankka-caller, traceparent, as every call
  replyTo  = ActorRef[EntityProtocol.Reply]
)
```

Sent under `Timeout(askTimeout)` (`ankka.ask-timeout`, 10s by default).

## The replies

| Reply | Engine sends it when | Transport does |
|---|---|---|
| `Succeeded(stateBytes, Vector(ankka-manifest, content-type))` | the run is `Completed` (now, or when `Ended` is persisted); the two entries name the state as its serializer does, so the sidecar can wrap it for a process | completes the future with the bytes and the metadata |
| `WorkflowFailed(step: Option[String], reason: String, deleted: Boolean)` | the run is `Failed` or `deleted` (now, or when persisted) | fails with `CommandError(message, WorkflowFailed, details)` |
| `NotYet(heldMillis: Long)` | the hold is spent, or the actor stops (`PostStop`) | asks again if the caller's deadline has not passed; else `Timeout` |
| `Rejected(NotFound, "… has no handler 'ankka:await-end' …")` | the instance is from before this release | fails with `CommandError("instance … is from before awaiting; both halves must be at this release", Unavailable)` |
| any other `Rejected` | never by this engine; kept for completeness | fails with `rejected.toCommandError` |
| (no reply within `askTimeout`) | the actor stopped between the ask and the hold, the shard moved, the message was dropped at hand-off | asks again if the caller's deadline has not passed; else `Timeout` |

`Succeeded` and `Rejected` are unchanged in shape. `NotYet` and `WorkflowFailed` are new
`final case class`es extending `Reply with AnkkaSerializable`, serialized by the existing
`jackson-cbor` binding; no `reference.conf` change.

## The transport's contract

```scala
// modules/sdk — CallTransport
def awaitEnd(componentId: ComponentId, entityId: EntityId, timeout: FiniteDuration, metadata: Metadata): Future[(Array[Byte], Metadata)]
```

- Refuses `timeout <= 0` with `CommandError(BadRequest)` before sending.
- Counts one `Client` span (`Recorder.reserve` at start, `record` at the end: `Ok` on a state or a
  `WorkflowFailed`, `TimedOut` at the deadline) and one `unanswered(TimedOut)` at the deadline.
  Never counts an attempt.
- Carries the caller and the trace captured on the asking thread in every attempt.
- `TestTransport` (testkit) implements it by answering from its stubs or failing `NotFound`.

## The engine's contract

- An `Invoke` on `ankka:await-end` runs no handler, opens no handler span, and never persists.
- Answered at once when `isTerminal || deleted`; otherwise held for `min(asked, askTimeout / 2)`.
- A `Succeeded` answer carries the state's manifest and content type as metadata, because the ask
  carries no payload the sidecar could take them from.
- Every end — `Ended`, `Failed` from any of the five fail sites, `Deleted` — answers every waiter
  in the `thenRun` after the persist, and counts `handled(metadata, component, "ankka:await-end",
  Ok, sinceAsked)` once per waiter.
- `PostStop` answers every waiter `NotYet` and logs nothing.
- The lifecycle query's answer is unchanged and is not asked by any part of a wait.
