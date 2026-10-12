# Contract: the Scala SDK surface

What a service author writes. Every name below is public API of `ankka-sdk` or `ankka-core`; the
runtime and the test kits implement it.

## Declaring replication

```scala
object VehicleEntity
    extends EventSourcedEntity.Companion[VehicleEntity, Vehicle, VehicleEvent](
      ComponentId("vehicle"),
      Codecs.serializer[Vehicle]("vehicle"),
      Codecs.serializer[VehicleEvent]("vehicle-event")
    ):
  override def replicated: Boolean = true          // default false
  def create(ctx: EventSourcedEntityContext) = new VehicleEntity(ctx)
  val addStep = command("add-step")(_.addStep)
  val get     = query("get")(_.get)
```

- `EventSourcedEntityDescriptor.replicated: Boolean` carries it; `ComponentDescriptor` gains
  `replicated` (false for every other kind), which the service's registry and the control plane's
  descriptor validation read.
- Only an event sourced entity may be declared replicated. There is no `replicated` member on any
  other companion; a process or module that declares it on another kind is refused at discovery
  (`contracts/protocol.md`).

## Effects

On `PersistBuilder[S, E]` (so they compose with `persist`), each a `PersistBuilder`:

```scala
effects.persist(Shipped(dealer)).handOverTo("vienna").thenReply(_ => Done)
effects.persist(Allotted(dealer)).replicateTo("vienna").thenReply(_ => Done)
effects.persist(Sold).stopReplicatingTo("leipzig").thenReply(_ => Done)
```

and without a domain event:

```scala
effects.handOverTo("vienna").thenReply(_ => Done)      // returns PersistBuilder
effects.replicateTo("vienna").thenNoReply()
```

Semantics the runtime gives them (research R8, R10):

| Effect | Records written at the home, after the events | Refused when |
|---|---|---|
| `handOverTo(x)` | kind 5 naming `x`; later kind 3 or kind 6 | `x` is this installation, not in the service's installations, or not in the replication set; a handover is already pending; the copy is not the home |
| `replicateTo(x)` | kind 4 with the set plus `x` | `x` is not in the service's installations; the copy is not the home |
| `stopReplicatingTo(x)` | kind 4 with the set minus `x` | `x` is the home; the copy is not the home |

A refusal is `effects.error`'s shape: the caller sees a `CommandError` with `ErrorCode.BadRequest`
or `ErrorCode.NotHome` and nothing is persisted. `EventSourcedEffect.materialise` reduces the new
fields so the pure test kit and the runtime agree: `Materialised` gains `replication: Option[ReplicationChange]`.

## Contexts

```scala
trait CommandContext:
  // as today, plus
  def installation: Option[String]   // this installation; None on a service with none named
  def isHome: Boolean                // true on a service with none named

trait EventContext:
  def installation: Option[String]   // where the event was written; None for a record from before replication
  def isLocal: Boolean               // written here
  def sequenceNumber: Long

abstract class EventSourcedEntity[S, E]:
  protected final def eventContext: EventContext   // inside applyEvent only; throws outside, as commandContext does
```

`applyEvent(event: E): S` is unchanged. The host sets the event context around `_applyEvent` on
replay and on apply, as it sets the command context today.

```scala
trait ChangeContext:
  // as today, plus
  def installation: Option[String]   // where the change was written
  // localOrigin is now truthful: installation.forall(_ == self)
```

## Consumers

```scala
object BuildsConsumer extends Consumer.Companion[...](...):
  override def produceTo: Option[String] = Some("vehicle-builds")
  override def mirroredChanges: Boolean = true     // default false: a producing or graph consumer sees local changes only
```

## Reading a copy's replication state

```scala
componentClient.forEventSourced(VehicleEntity, "v1").replication   // Future[ReplicationStatus]
final case class ReplicationStatus(home: Option[String], replicationSet: Set[String], pendingHandover: Option[String], conflict: Option[String], position: Long)
```

It is the reserved query `ankka:replication`, read-only, served by any copy from its own journal.

## Errors

| `ErrorCode` | Meaning for a caller |
|---|---|
| `NotHome` | this copy cannot handle the write and could not reach the home named in the message, or this copy is outside the replication set and the read is refused; the message names the home |
| `Conflict` | the entity is in conflict; the message names the other home |
| `Unavailable` | a held handover was abandoned; the message names the target |

## Test kits

```scala
val fleet = AnkkaTestKit.installations("a", "b")        // one database per name, acquired up front
val a = fleet.start("a", Seq(VehicleEntity.descriptor))  // each knows the others' databases from the start
val b = fleet.start("b", Seq(VehicleEntity.descriptor))
fleet.transport.pause(from = "a", to = "b"); fleet.transport.resume(from = "a", to = "b")
fleet.transport.unreachable("b", true)
fleet.stopAll()

val kit = EventSourcedTestKit.of(VehicleEntity, mode = EventSourcedTestKit.Mode.Follower(home = "a"))
kit.call(VehicleEntity.addStep)(step)   // Left(CommandError(_, NotHome))
kit.call(VehicleEntity.get)(())         // Right(state)
```
