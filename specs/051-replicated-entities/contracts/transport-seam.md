# Contract: the replication transport seam

The one interface the private half (`ankka-replication`) implements and the one the runtime's own
transport implements. In `modules/runtime`, package `com.thinkmorestupidless.ankka.runtime.replication`.
Nothing the entity host does depends on anything outside this trait; a change to it is a change to
this contract and to the private repository's pin.

```scala
package com.thinkmorestupidless.ankka.runtime.replication

trait ReplicationTransport:
  /** For logs and the startup refusal. */
  def name: String

  /**
   * The records of one entity held at `installation`, from sequence number `from` upwards, in order,
   * live: the source stays open and emits each record as the installation holds it. Completing or
   * failing the source is a signal to the caller to open it again from its own position; the caller
   * never skips a position.
   */
  def records(installation: String, componentId: ComponentId, entityId: EntityId, from: Long): Source[Replicated, NotUsed]

  /**
   * Handles `call` at `installation` as that installation's copy would handle an `Invoke` from a
   * local caller, and returns the reply. The call carries the caller's metadata (the trace, the
   * caller) unchanged. Fails the future when the installation cannot be reached.
   */
  def forward(installation: String, componentId: ComponentId, entityId: EntityId, call: ForwardedCall): Future[ForwardedReply]

  /** Tells `installation` the sequence number this installation holds for the entity. Best effort. */
  def report(installation: String, componentId: ComponentId, entityId: EntityId, position: Long): Future[Done]

  /**
   * Where an entity this installation has never seen lives: its home and its replication set, or
   * `None` when no installation holds it. Asked only for a copy with no replication record.
   */
  def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]]

  /**
   * Entities that advanced at another installation: the wake feed. An element says `installation`
   * wrote the entity up to `position`. Best effort; a copy nobody wakes is behind until read.
   */
  def advanced: Source[Advanced, NotUsed]

  /** Whether `installation` can be reached now, for the refusal message and the test kit. */
  def reachable(installation: String): Boolean

  def stop(): Unit = ()

final case class Replicated(sequenceNr: Long, record: JournalRecord)
final case class ForwardedCall(method: MethodName, payload: Array[Byte], metadata: Metadata)
enum ForwardedReply:
  case Succeeded(payload: Array[Byte], metadata: Metadata)
  case Rejected(error: CommandError)
final case class Located(home: String, replicationSet: Set[String])
final case class Advanced(installation: String, componentId: ComponentId, entityId: EntityId, position: Long)

/** Found through java.util.ServiceLoader, as RuntimeExtensionProvider is. */
trait ReplicationTransportProvider:
  /** `None` when this provider is not configured; the runtime then falls back or refuses. */
  def transport(system: ActorSystem[?], config: Config, settings: ReplicationSettings): Option[ReplicationTransport]

final case class ReplicationSettings(installation: Option[String], installations: Set[String], poll: FiniteDuration, holdWrites: Boolean, handoverTimeout: FiniteDuration)
```

## Resolution at start

1. `ReplicationSettings` is read from `ankka.replication`.
2. If `installations` is empty: no transport; every replication check is skipped.
3. Else the first `ReplicationTransportProvider` that returns `Some` wins.
4. Else, if every sibling has `db.*` settings: the runtime's own `DatabaseReplicationTransport`.
5. Else `StartRefusal("a replication transport: the service names installations <…> and no provider supplies one; add ankka-replication, or name each installation's database for the runtime's own transport")`.

## What the runtime guarantees the transport

- `records` is opened by a follower from `lastSequenceNumber + 1` and re-opened from there after
  any completion or failure; the transport may replay from earlier and the follower discards what it
  holds, but a record whose `sequenceNr` is beyond the next one fails the stream.
- `forward` is called only for a non-read-only `Invoke` at a copy that is not the home, with the
  home the copy's own journal names, or the one `locate` named for an unknown entity.
- `report` is called by a follower that is the target of a pending handover, after each record it
  persists; and by the runtime's own transport's loopback route into `Reported`.
- `locate` is called at most once per command for a copy with no record.
- The runtime never asks the transport for records of a copy in conflict.

## What the transport must guarantee the runtime

- Records for one entity from one installation arrive in sequence order with no gaps between the
  first emitted and the last.
- A record's `installation` is what the writer stamped; the transport never rewrites a record. (A
  follower stamps an unstamped record it mirrors, research R5; that is the host's act, not the
  transport's.)
- `forward` delivers the call once or fails; it does not retry a command (a command is sent once,
  `docs/reference/limitations.md`).
- `locate` answers from facts held at an installation, not from a cache it cannot invalidate, or says
  so in its documentation.

## The runtime's own implementation (`DatabaseReplicationTransport`)

| Operation | Mechanism |
|---|---|
| `records` | `eventsByPersistenceId` on `ankka.replication.installations.<name>.query`, an r2dbc query plugin rendered per sibling with its own connection factory, refresh `ankka.replication.poll` |
| `advanced` | `eventsBySlices` per sibling per replicated component, filtered to records written at that sibling, as a sharded daemon process with offsets in the local projection store |
| `locate` | `currentEventsByPersistenceId(pid, 1, 1)` at each sibling: a kind 3 first record names the home; a journal with a first record of another kind is one from before this feature and the sibling holding it is the home |
| `forward`, `report` | in one JVM, the other service's `ShardingTransport` directly; across processes on one machine, `POST /replication/forward` and `/replication/report` on the sibling's local observability endpoint, found in `~/.ankka/running` |
| `reachable` | the last pull or forward succeeded within two polls |

It is the test kit's transport and the local overlay's. It is not a transport between
installations and does not pretend to be: a Kubernetes overlay with `ankka.replication.installations`
and no provider refuses to start with the message above.
