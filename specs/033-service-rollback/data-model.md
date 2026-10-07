# Data Model: Service Rollback

Nothing is added to the database's schema, to `AnkkaServiceSpec` or to the cluster. Everything
here is the control plane's own journal, its `Service` state and its wire types.

## The event

`ServiceEvent.ServiceApplied` (`controlplane/…/domain/events.scala`)

| Field | Type | Note |
|---|---|---|
| `projectId` | `String` | unchanged |
| `descriptor` | `ServiceDescriptor` | unchanged; for a rollback, the target's descriptor, whole |
| `generation` | `Long` | unchanged; the generation this event made |
| `actor` | `Option[Actor]` | unchanged |
| `at` | `Option[Instant]` | unchanged |
| `rolledBackTo` | `Option[Long] = None` | **new.** The generation whose descriptor this is. Absent on an apply and on every event written before the feature |

No event is added. An older build decoding a new event skips the field and reads an apply.

## The state

`Service` (`controlplane/…/domain/model.scala`)

| Field | Type | Note |
|---|---|---|
| `kept` | `Vector[KeptDescriptor] = Vector.empty` | **new.** Newest first, at most `Service.KeptDescriptors` (50). Pushed by every `ServiceApplied`, an apply's and a rollback's |
| `history` | `Vector[HistoryEntry]` | unchanged in shape; its entries gain three fields |

`KeptDescriptor(generation: Long, descriptor: ServiceDescriptor)`: **new**, beside `Service`.

**Reading `kept`** always goes through one accessor that seeds a state written before the feature:
when `kept` is empty and `descriptor` is defined, the current descriptor stands as kept at the
generation of the newest history entry of kind `applied`, or at the current generation when the
history has none. `onApplied` pushes onto that. The seeding is a function of the state alone, so
replay reproduces it.

**What is untouched by a rollback**: `paused`, `exposed`, `restarts`, `suspended`. `onApplied`
already leaves them (a paused service stays paused through an apply), and a rollback is folded by
`onApplied`.

**Deletion** keeps `descriptor`, `kept` and `history`, as it keeps `descriptor` and `history`
today, so a service applied again can be rolled back across its deletion.

**`desiredState`** replies with `kept` and `history` emptied, since none of its readers (the projector, exposure, mount states, the organization's sweep) uses either. No other reply holds the kept descriptors. Measured with fifty typical descriptors: the state is 66 KiB, and the reply, with the descriptor of twelve variables, under 4 KiB.

## The fold

`Service.fold` on `ServiceApplied(_, descriptor, generation, actor, at, rolledBackTo)`:

1. `onApplied(descriptor, generation)`, which now also pushes `KeptDescriptor(generation, descriptor)`
   and caps at fifty.
2. `remember` an entry of kind `rolled-back` when `rolledBackTo` is defined and `applied`
   otherwise, with `image = Some(descriptor.service.image)`, `digest = Some(descriptor.digest)`
   and `rolledBackTo`.

Every other event's entry has no image, no digest and no `rolledBackTo`.

## Resolving a target

Two pure functions on `Service`, used by the entity's two queries and its command:

`descriptorAt(n: Long): Either[RollbackRefusal, KeptDescriptor]`

| State | Answer |
|---|---|
| `n < 1` or `n > generation` | `NoSuchGeneration(n)` |
| a kept descriptor has generation `n` | it |
| `n` is below the oldest kept generation, or nothing is kept | `NotKept(n, oldest)` |
| otherwise (inside the kept range, no descriptor of its own: a restart's) | `NoDescriptor(n, ran)`, where `ran` is the newest kept generation below `n` |

`rollbackTarget(requested: Option[Long]): Either[RollbackRefusal, KeptDescriptor]`

| Requested | Answer |
|---|---|
| `Some(n)` | `descriptorAt(n)`, then `SameDescriptor(n)` if its digest is the current descriptor's |
| `None` | the newest kept descriptor whose digest is not the current descriptor's, else `NothingToRollBackTo` |

"The current descriptor's digest" is `descriptor.digest`, computed when asked. Digests stored in
history entries are for showing and are never compared by the platform.

`RollbackRefusal` is an enum beside `Service`; each case has its message and its `ErrorCode`
(research R8).

## The digest

`ServiceDescriptor.digest: String` (`controlplane-api/…/api/descriptors.scala`): lowercase hex
SHA-256, 64 characters, of the UTF-8 bytes the descriptor's codec writes once `labels` and
`annotations` are in key order.

| Two descriptors that | Digests |
|---|---|
| are equal | same |
| differ only in the order of labels or annotations | same |
| differ in the order of variables | different |
| differ in any value | different |
| differ only in a field one states at its default and the other omits | same (the codec omits a default) |

## Wire types

`controlplane-api/…/api/descriptors.scala`

`HistoryEntry`

| Field | Type | Note |
|---|---|---|
| `kind` | `String` | gains the value `rolled-back` |
| `generation` | `Long` | unchanged |
| `actor` | `Option[HistoryActor]` | unchanged |
| `at` | `Option[Instant]` | unchanged |
| `image` | `Option[String] = None` | **new.** On `applied` and `rolled-back` |
| `digest` | `Option[String] = None` | **new.** On `applied` and `rolled-back`; all 64 characters |
| `rolledBackTo` | `Option[Long] = None` | **new.** On `rolled-back` only |

`RollbackRequest(generation: Option[Long] = None)`: **new.** `{}` asks for the default target.

`RolledBack(rolledBackTo: Long, status: ServiceStatus)`: **new.** The generation the rollback
resolved to, and the status it produced.

`RollbackService(generation: Long)`: **new**, in `domain/events.scala` beside `ApplyService`. The
entity command's input; never on the HTTP wire.

## Stored forms that must stay readable

| Form | Written by | Read by the feature as |
|---|---|---|
| `ServiceApplied` with five fields | every build so far | an apply, `rolledBackTo = None` |
| `Service` snapshot with no `kept`, history entries with four fields | every build so far | `kept` empty and seeded on read; entries with no image and no digest |
| `ServiceApplied` with `rolledBackTo` | this feature | by an older build during a rolling update: an apply |

`EventCompatibilitySuite` pins the first two as literal JSON and proves the third with a copy of
the old case class.

During a rolling update an entity may be hosted for a while by a node on the previous version. If
that node writes a snapshot, the snapshot has no `kept`, and the next node to host the entity
sees only the seeded descriptor. That is accepted: the service can still be rolled back once, the
loss is confined to the update's own window, and the journal is untouched.

## Sizes

Research R3. A `Service` with fifty typical descriptors and fifty history entries encodes to about
83 KiB; with forty variables per descriptor, about 214 KiB. It is written as a snapshot once per
hundred events, replacing the one before, and is never sent between nodes whole.
