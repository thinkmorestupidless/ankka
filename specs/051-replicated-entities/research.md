# Research: Replicated Entities

Every unknown the technical context raised, resolved against the code as it is on
`051-replicated-entities` (main at 5f1474a8). File references are to that tree. The spike that
decided the shape is `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ReplicationOrderSpike.scala`.

## R1. Why not Pekko's replicated event sourcing

**Decision**: not used. Every copy is an ordinary `EventSourcedBehavior` under the persistence id it
has today, `PersistenceId(componentId, entityId)` (`EventSourcedEntityHost.scala:61`); a follower
persists records it pulls, a home persists records its handlers produce.

**Rationale**: the spike ran three copies on three databases through `perReplicaJournalConfig`. After
a home move from A to B, C applied B's events before A's earlier ones with `concurrent = false` on
every one, and its journal holds `6 7 8 1 2 3 4 5` for good; Pekko's `handleExternalReplicatedEventPersist`
(persistence-typed 1.7.0, `Running.scala:475`) persists on arrival and the version vector only sets a
flag. A position buffer repaired the entity's state but not the journal a view reads. Pekko's
replication also changes the persistence id to `type|entity|replica`, which forecloses FR-014, and
opens one `eventsByPersistenceId` stream per sibling per running copy. Pekko publishes no transport
between clusters (no `pekko-projection-grpc` on Maven Central; Akka's is post-fork, BSL), so a link
between installations is ankka's to write under either engine.

**Alternatives considered**: Pekko replication plus a position buffer in the entity and a second
buffer in the projection layer (two more tables, and followers' journals still in arrival order);
Pekko replication with the move gated on every member confirming (needs every member reachable to
move, and a late joiner still interleaves two past writers' streams).

## R2. Where a copy's mode lives: in the stored state, derived from the journal

**Decision**: `EventSourcedEntityHost.Stored[S]` (`:31`) gains `home: Option[String]`,
`replicationSet: Set[String]`, `pendingHandover: Option[String]` and `conflict: Boolean`, each
folded from platform records in the journal (R3). The host knows its own installation from
`ankka.replication.installation` (`ANKKA_INSTALLATION`, R9) and derives the mode on every command:
`home.contains(self)` is the home; `replicationSet.contains(self)` and not home is a follower;
anything else is outside the set. `home = None` is an entity that has no replication record yet:
at the installation whose journal holds it, the next persisting command writes `KindHome(self)`
first (FR-014); at any other installation it is unknown and the directory is asked (R6).

**Rationale**: the state is rebuilt from the journal on every recovery and carried in the snapshot
(`StateRecord`, R4), so a mode survives a restart with nothing new to store, and a follower's mode
changes exactly when it persists the record that changes it, which is what FR-010's "effective only
once its own journal ends with the handover record" means. `onCommand` (`:86`) already shows a
deleted or expired entity `emptyState`; the mode check sits beside it.

**Alternatives considered**: a mode held in actor memory (lost on restart, and a follower that
restarted would not know whether it is home until it had asked someone); a `replica_modes` table
(a second source of truth beside the journal).

## R3. The platform records: four new kinds, and the writer on every record

**Decision**: `JournalRecord` (`wire.scala:149`) gains `installation: Option[String]` with
`@JsonInclude(NON_ABSENT)`, as `WorkflowRecord.standing` did (`wire.scala:252`), so a record that
carries none is byte for byte what it was. Four kinds beside domain (0), deleted (1) and expiry (2):

| Kind | Name | Payload | Written by | Meaning |
|---|---|---|---|---|
| 3 | `ankka.home` | the home's name, UTF-8 | the home | the entity's home is this installation from here: the first record of a replicated entity, and every handover |
| 4 | `ankka.replication-set` | the whole set after the change, as a JSON array | the home | the replication set is now this |
| 5 | `ankka.handover` | the target's name | the home | a handover to the target is pending; the target confirms its position (R7) |
| 6 | `ankka.handover-abandoned` | the target's name | the home | the pending handover did not happen; the home stays |

A kind 3 record ends a kind 5 pending handover. Both the set and the home are written as absolute
state, not as deltas, so a reader of any one record knows the whole fact and a late joiner needs no
replay to learn it; the set record is small (a handful of installation names).

`ChangeReader.journal` (`ChangeReader.scala:32`) reads kinds 3 to 6 as `SourceChange.Skip`, as it
reads expiry: a storage fact, not a domain change. `fromJournal` in the host (`:302`) reads them
into `Journaled.Home`, `Journaled.ReplicationSet`, `Journaled.Handover`, `Journaled.HandoverAbandoned`;
an older runtime that meets kind 3 throws "written by a newer runtime" (`:313`), which is the
behaviour it has for any unknown kind and is correct: a journal with replication records is not one
an older runtime may host.

**Rationale**: the journal is "the longest-lived thing ankka writes" and flat with an integer kind
on purpose (`wire.scala:141`). Records in the journal are the only thing every copy is guaranteed to
share, so the facts every copy must agree on go there and nowhere else.

**Alternatives considered**: a side table of homes (a second truth, and not mirrored); the home in
the snapshot alone (a follower with no snapshot would not know).

## R4. The snapshot carries the mode

**Decision**: `StateRecord` (`wire.scala:170`) gains `home: Option[String]`, `replicationSet:
Option[Vector[String]]`, `pendingHandover: Option[String]` and `conflict: Option[Boolean]`, all
`NON_ABSENT`, read back as `None`, empty, `None`, `false`. `RemoteStateRecord` gains the same, since
the remote host writes it and the in-process host reads it (`:338`). Two fixtures are pinned before
the fields exist, `modules/testkit/src/test/resources/journal/journal-record.txt` and
`state-record.txt`, and a `JournalRecordCompatibilitySuite` reads today's bytes with the new case
classes and the new bytes into copies of the old case classes, as `WorkflowRecordCompatibilitySuite`
does.

**Rationale**: a snapshot is the state at a sequence number, and the mode is part of the state (R2).
The "Jackson writes an absent `Option` as a `null` property" trap (`.claude/rules/messaging.md`) is
why the inclusion annotation is on every new field and why the fixtures are pinned first.

## R5. Mirroring: a follower persists the record it was given, unchanged

**Decision**: `Journaled` gains `Mirrored(record: JournalRecord)`. The event adapter's `toJournal`
returns the record itself for a `Mirrored`; its `fromJournal` never produces one, since a mirrored
record read back is whatever kind it is. `onEvent` applies a `Mirrored` by reading the record's kind
exactly as `fromJournal` would and folding the result. A follower actor receives an internal
`EntityProtocol.ModuleCommand`, `Replicate(sequenceNr, record, ack)`, from its puller (R6), refuses
it unless `sequenceNr == lastSequenceNumber + 1` and the record's `installation` is in the
replication set or is the pending home, and otherwise persists `Mirrored(record)` and acks.

**Rationale**: a mirrored journal must be identical byte for byte (SC-001), and re-encoding a decoded
event through the entity's serializer is not: a payload a Python process wrote and a Scala serializer
re-wrote would differ. Persisting the record as it came is the only way to guarantee identity, and
it also means a follower needs no serializer for events it never handles. The sequence check is the
whole ordering guarantee at the follower: Pekko assigns `lastSequenceNumber + 1` to what is
persisted, so refusing anything else keeps positions aligned with the home's by construction.

**Alternatives considered**: making `JournalRecord` the behaviour's event type and decoding in the
event handler (one decode per persisted event on the home's write path, for no gain once `Mirrored`
exists); a classic untyped `EventAdapter` (the same re-encoding problem).

## R6. The transport seam, its provider, and the one the runtime ships

**Decision**: `modules/runtime` defines

```scala
trait ReplicationTransport:
  def name: String
  def records(installation: String, componentId: ComponentId, entityId: EntityId, from: Long): Source[Replicated, NotUsed]
  def forward(installation: String, componentId: ComponentId, entityId: EntityId, call: ForwardedCall): Future[ForwardedReply]
  def report(installation: String, componentId: ComponentId, entityId: EntityId, position: Long): Future[Done]
  def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]]
  def advanced: Source[Advanced, NotUsed]
  def reachable(installation: String): Boolean
```

(`contracts/transport-seam.md` has the types). A `ReplicationTransportProvider` is found through
`java.util.ServiceLoader`, as `RuntimeExtensionProvider` is (`RuntimeExtensionProvider.scala:30`),
so `ankka-replication` on the classpath supplies the transport with no code in the service. With no
provider, the runtime's own `DatabaseReplicationTransport` is used when `ankka.replication.installations`
names siblings with database settings, and a service naming siblings with neither provider nor
settings fails `ServiceBuilder.start` through `StartRefusal` naming "a replication transport"
(FR-003). The runtime's own transport:

- `records`: `eventsByPersistenceId` on a query plugin per sibling, `ankka.replication.installations.<name>.query`,
  rendered from the sibling's `db.*` settings as the spike's config does (`ReplicationOrderSpike.scala`,
  `start`), refresh interval `ankka.replication.poll` (default 1s).
- `advanced`: `eventsBySlices` on each sibling's query plugin for every replicated component, filtered
  to records whose `installation` is that sibling, as the wake feed; one sharded daemon process per
  sibling, offsets in the local projection store.
- `locate`: `currentEventsByPersistenceId(pid, 1, 1)` at each sibling: the first record names the home
  (kind 3) or, for a journal from before this feature, the sibling that holds it is the home.
- `forward` and `report`: within one JVM, straight to the other service's `ShardingTransport`
  (the test kit's case); between two processes on one machine, `POST` to the sibling's local
  observability endpoint (`ObservabilityEndpoint`, the JDK HTTP server on loopback) at
  `/replication/forward` and `/replication/report`, found through `~/.ankka/running`
  (`ServiceRegistration`), which every locally run service already announces itself in.

**Rationale**: the seam is the contract the private half builds against (spec, Context), so it is
in `runtime`, typed on runtime values (`JournalRecord`, `EntityProtocol`), and discovered the way
the runtime already discovers an extension nobody hands over. The runtime's own implementation is
the spike's mechanism given a name, plus the two loopback routes the local overlay needs; it is
deliberately not a network transport between installations, which is what `ankka-replication` is.

**Alternatives considered**: the seam in `sdk` (would drag `JournalRecord` and Pekko streams into
the SDK); configuration-named class (ServiceLoader is the pattern the tree has); forwarding through
`HttpServiceClients` (needs a route on the service's HTTP server, which a service without `ankka-http`
does not have).

## R7. The puller, the switch at a handover, and the wake

**Decision**: a running follower owns one puller, a child stream of its actor: `records(home, …, lastSequenceNumber + 1)`
through `ActorFlow.ask` into `Replicate`, restarted with backoff (as Pekko's own, `Running.scala:160`).
When the follower persists a kind 3 record naming a new home, its puller is stopped and restarted
against the new home from the same position. A follower that is the target of a kind 5 record calls
`report(home, …, lastSequenceNumber)` after every record it persists while the handover is pending,
so the home learns the position without asking. A follower is woken by `advanced` (R6) through an
internal `Wake` module command to the sharded entity, which starts it and so its puller; a follower
nobody has woken is behind until someone reads it, which is the staleness a read may see (spec, Edge
Cases). Pekko's passivation (120s) bounds the running pullers.

**Rationale**: one source at a time, switched only at the handover record, is the ordering argument
of the spec's Context, and a puller owned by the actor is stopped when the actor passivates. The
report is cheap and sent only while a handover is pending.

## R8. The handover: default chase, optional hold

**Decision**: a persisting effect's `.handOverTo(installation)` (contracts/scala-sdk.md) makes the
host persist the handler's events and then a kind 5 record. In the default mode
(`ankka.replication.handover.hold-writes = off`) the home goes on handling persisting commands; on
every `report` from the target the home compares the reported position with `lastSequenceNumber`
and, when equal, persists kind 3 naming the target and thereafter forwards. In hold mode (`on`) the
home stashes persisting commands from the kind 5 record on; when the target reports the position
the home persists kind 3 and forwards the stash; when `ankka.replication.handover.timeout`
(default 30s) passes first it persists kind 6, handles the stash itself, and answers the handover
command's caller `ErrorCode.Unavailable` "handover to X did not happen". The handover command's
caller is answered when kind 3 or kind 6 is persisted; while the home waits it answers `NotYet`
every half ask timeout and the client asks again, the loop `ShardingTransport.awaitEnd` already has
(`ShardingTransport.scala:112`), reused for the reserved method `ankka:handover-status`. A
handover to an installation outside the replication set is refused by the handler's effect
reduction before anything is persisted.

**Rationale**: clarification Q4. The kind 5 record makes the pending handover survive a home
restart and tells the target to start reporting; the kind 6 record ends it visibly. The chase has
no timeout because under steady load it completes at the first quiet moment and the entity is never
without a home.

## R9. Installation names and settings

**Decision**: `ankka.replication.installation` (env `ANKKA_INSTALLATION`) names this installation;
`ankka.replication.installations` is a map of sibling name to settings, each with `db.{host,port,name,user,password}`
for the runtime's own transport, rendered from `ANKKA_INSTALLATION_<NAME>_DB_*`. Both variables are
`PlatformVariables.PlatformOnly`; the `ANKKA_INSTALLATION_` prefix is in `RuntimeOnlyPrefixes`, so
a sibling's database credential never reaches a process. A name is a DNS label of at most 63
characters, which excludes `|` (the persistence id's separator) and `/`. A service whose settings
name no installation runs as it does today with every replication check short-circuited
(`installations.isEmpty`), which is what SC-005 measures.

**Rationale**: "which variables are the platform's is said once, in core's `PlatformVariables`"
(`CLAUDE.md`), and the operator compiles the same file. The map shape is the one `ankka.replication`
in the spike's config had.

## R10. Forwarding and the directory at the host

**Decision**: the host decides, not the client. An `Invoke` for a non-read-only handler at a copy
that is not the home is answered by `transport.forward(home, …)` piped back to `invoke.replyTo`
through `pipeToSelf`, so the caller's `ask` is answered once and `askQuery`'s resend rule is
untouched. A read-only `Invoke` at a follower is served; at a copy outside the set it is refused
`ErrorCode.NotHome` naming the home. An `Invoke` at a copy with `home = None`: for a read-only
handler, `locate` is asked and the answer refuses naming the home, or serves the empty state when no
installation holds the entity (today's behaviour for an unknown id); for a persisting handler,
`locate` is asked and the call is forwarded to the home found, or, when none is found, handled here
as a creation, writing kind 3 and kind 4 (the set is `{self}`, clarification Q2) before the
handler's events. The forwarded call is `Observability.calling` from the follower's host, one
`Client` span under the caller's, so the trace shows one call forwarded (FR-006).

**Rationale**: the entity actor is the one place that knows the mode from its own journal, and
`ShardingTransport` has no view of it; `pipeToSelf` is how a sharded actor answers from a future
without touching its context from another thread (`.claude/rules/runtime.md`). `locate` is asked only
when a copy has no record, so a known entity never costs a directory call.

## R11. Conflict: detected where two histories meet, marked at both

**Decision**: a copy holds a conflict when a record it is handed, or a directory answer, names a
home at sequence 1 that is not the home its own first record names. Two sites detect it: the puller,
when the first record from the other installation's journal is a kind 3 naming that installation
while the local first record names this one; and `locate`, when a home answers for an entity this
copy created as home. Either persists a local `conflict` through a `Journaled.Conflict` record (kind
7, `ankka.conflict`, payload the other home's name), which is not mirrored anywhere, since the
journals have already diverged. A copy in conflict refuses persisting commands `ErrorCode.Conflict`
"in conflict with <other>", answers reads, and reports `conflict` in the reserved `ankka:replication`
query so an operator can see it; the local console's entity view shows it. Resolution is an operator's
act outside this feature.

**Rationale**: clarification Q1. Both sides must learn of it, and the journal is where a copy's facts
live.

## R12. Projections: local or mirrored, and publish once

**Decision**: `SourceChange.Changed` gains `installation: Option[String]` from the record;
`SimpleChangeContext.localOrigin` becomes `installation.forall(_ == self)`, with `self` from the
settings, at the five sites (`ProjectionSupport.scala:107`, `ProjectionRuntime.scala:1496,1563,1613`,
`TopicHandlers.scala:54,103`, which stay `true` for topics and key value sources); `ChangeContext`
gains `installation: Option[String]`. `Consumer.Companion` gains `def mirroredChanges: Boolean = false`;
`ConsumerDescriptor` carries it; `ProjectionRuntime` skips a non-local change for a consumer whose
`produces` is set or that is a graph consumer unless it declares `mirroredChanges`; a consumer that
produces nothing is given every change, since it publishes nothing twice. Views are unchanged: a
follower's journal is the home's. Graph delta versions are already the sequence number
(`GraphConsumer.scala:85`), which is the same everywhere, so nothing changes there.

**Rationale**: FR-017 and the identical-journal property. The default protects a topic from receiving
each change once per installation (SC-004) while a consumer that only updates local state sees
everything.

## R13. Protocol: fields, a flag and the minor

**Decision**: `EventSourcedIn.Event` gains `installation` (string) and `Command` gains `installation`
and `at_home` (bool); `EventSourcedDetail` gains `replicated` (bool); `ConsumerDetail` gains
`mirrored_changes` (bool); `ConsumerRequest.metadata` gains `ankka.installation` and `ankka.local`.
`Discovery` refuses `replicated` from an SDK whose protocol is below the minor this lands at, and
each SDK refuses a runtime below it at discovery, naming the version, as a workflow source is refused
(`Discovery.scala:354`). The minor is the next free one when this merges: 1.16 is 046's; 047 and 048
are due to take 1.17 and 1.18 on their renumbering, so this is 1.19 unless they merge differently;
the number is fixed in `tasks.md` at implementation time, not here.

**Rationale**: a process declaring replication is a behaviour an older runtime must refuse
(`.claude/rules/sidecar.md`), and the declaration is the gate; the event and command fields are
information a process may ignore.

## R14. The test kit's installations

**Decision**: `AnkkaTestKit.installations(names*)` acquires one database per name from
`SharedPostgres.acquire()` up front, and its `start(name, descriptors, …)` starts that installation's
service in the same JVM with `ankka.replication.installation = name` and every other name as a
sibling with its database (a copy must know its siblings' databases before they start, which is
why the group is named first), wired by a `TestReplicationTransport` that reads the
other kits' databases through the runtime's own transport and forwards in-JVM to their
`ShardingTransport`s, and offers `pause(from, to)`, `resume(from, to)` and `unreachable(name)` for the
handover and refusal scenarios. `EventSourcedTestKit.of(companion, …, mode = Mode.Follower(home = "a"))`
sets the stored mode so a persisting command is refused `NotHome` and a query is answered.
`GherkinSuite` runs `features/replication/` from `testkit`'s tests; the languages file's Python,
TypeScript and Rust rows run in their SDK kits and report `ranElsewhere` in the Scala suite, as 048's do.

**Rationale**: "two replicas with separate journals map onto two kit databases on the same server"
was the Explore agent's observation and the spike's practice. The in-JVM forward is what lets a
scenario count a forwarded call from the topology.

## R15. Documentation and skills

**Decision**: `docs/concepts/replicated-entities.md` (new), a section in
`docs/build/event-sourced-entities.md`, the limitations page's "One region" line replaced, a
divergences entry under "What Akka has that ankka does not" moved to a new "Replication" entry
saying which mode was adopted, `docs/reference/configuration.md` for the settings, and the
`ankka-entities`, `ankka-design` and `ankka-views-consumers` skills under `tools/docs/skill/`. A
`ReplicatedEntitiesDocumentationSuite` runs `features/documentation/replicated-entities.feature`.
