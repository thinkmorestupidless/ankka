# Tasks: Replicated Entities — One Home, Mirrored Everywhere Else

**Input**: Design documents from `/specs/051-replicated-entities/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature. The scenarios are already written in `features/replication/`
(40 scenarios across ten files; two are outlines, of four rows each, so 46 tests when run) and
`features/documentation/replicated-entities.feature` (3); quickstart says which suite runs each file
and what makes it red. Where a task says "bind", it means a `Given`/`When`/`Then` step in the named
`GherkinSuite` so the scenario runs; where it says "case", a `test(...)` named for the rule it holds.
A bound scenario must be red before the implementation task that makes it green: run it once and
read the failure.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (written at the home, read anywhere in the set), US2 (a write at a follower is
  handled by the home), US3 (the handover, every copy in the home's order), US4 (the replication set
  changes), US5 (views and consumers see the home's order and publish once), US6 (the transport is a
  seam; one installation needs nothing), US7 (an entity in every language), US8 (an existing entity
  made replicated with no migration), US9 (the test kit drives several installations), US10 (the
  documentation)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK`/`SDKT` = `modules/sdk/src/{main,test}/scala/…/sdk`;
`RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`; `REPL` = `RT/replication`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`;
`PY` = `sdks/python`; `TS` = `sdks/typescript`; `RS` = `sdks/rust`; `API` =
`controlplane-api/src/main/scala/…/controlplane/api`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`;
`FEAT` = `features/replication`. "R*n*" is a section of `research.md`; a contract is named by its
file under `contracts/`.

Work on branch `051-replicated-entities` in the worktree `.claude/worktrees/051-replicated-entities`,
or in an `051-replicated-entities-impl` worktree off it, as the project's habit is. Every `sbt`
command takes `-Dankka.cluster.tests=off`; nothing here needs k3s. Long suites run under
`caffeinate -i`. sbt's project ids are `core`, `sdk`, `runtime`, `testkit`, `sidecar`,
`controlPlaneApi`, `operator`. munit's `--` filter needs a leading `*`. The protocol minor is 1.19:
the branch is on 1.18 (046 took 1.16, 047 1.17, 048 1.18); renumber if anything merges first. The
installation names in the features are `leipzig`, `vienna` and `bratislava` (and `a`, `b` in the
transport and languages files); the entity is `vehicle`, the service `fleet`.

Two words the glossary settles and every identifier here follows: **installation** (never
replica, region, member), **home** and **follower**, **replication set**, **handover** (never move),
**local change** and **mirrored change**. Code may say `Mirrored` for the journaled case, since it is
not a feature step.

---

## Phase 1: Setup — the names, the fixtures pinned first, the protocol

**Purpose**: the one error code, the declaration flag, the variables, the settings and the wire
fields every later task spells the same way; and the record bytes pinned *before* a field is added.

- [X] T001 Pin the bytes before anything changes: in `TKT/JournalRecordCompatibilitySuite.scala`
  (new, modelled on `TKT/StateRecordCompatibilitySuite.scala` and `TKT/WorkflowRecordCompatibilitySuite.scala`)
  write a domain, a deleted and an expiry `JournalRecord` through the host's CBOR codec and compare
  each, in hex, with one line of `modules/testkit/src/test/resources/journal/journal-record.txt`,
  generated now from `main`'s `wire.scala` and committed as the fixture. Extend
  `StateRecordCompatibilitySuite` with the assertion that a `StateRecord` and a `RemoteStateRecord`
  from today still match their lines after T010 adds fields. Run both: green on the branch as it is;
  that is the baseline SC-005 measures against
- [X] T002 Add `NotHome` as a case of `enum ErrorCode` in `CORE/CommandError.scala` (`Conflict`
  exists at `:45`; `retryable` is unchanged); a scaladoc line saying a `NotHome` names the home in
  its message. Run `sbt core/compile`: every exhaustive `match` over `ErrorCode` that fails is the
  list for T003
- [X] T003 Give each exhaustive match its case, as 048's T002 did: `HttpProblem.from` in
  `modules/http/src/main/scala/…/http/HttpEndpoint.scala` maps `NotHome` to 421 (misdirected
  request) with the home in the body; `GrpcStatus` in `modules/grpc/src/main/scala/…/grpc/GrpcStatus.scala`
  maps it to `OUT_OF_RANGE` (`FAILED_PRECONDITION` is `Conflict`'s and the map is one to one); `Translate.toCode` in `SC/Translate.scala` maps it to
  `pb.ErrorCode.NOT_HOME` (compiles after T006); `Observability.outcomeOf` in `RT/Observability.scala`
  reads `NotHome` and `Conflict` as `Refused` (plan's gate "a refusal is not a failure"). Cases in
  the suites that hold each mapping (`GrpcStatusSuite`'s `codes == ErrorCode.values` goes red before
  and green after)
- [X] T004 [P] Add `def replicated: Boolean = false` to `trait ComponentDescriptor` in
  `CORE/ComponentDescriptor.scala`; `EventSourcedEntityDescriptor` in `SDK/EventSourcedEntity.scala:188`
  gains `replicated: Boolean` as a field and overrides it; `Companion` (`:107` region, beside
  `snapshotEvery`) gains `def replicated: Boolean = false` and `descriptor` (`:168`) passes it. Case
  in `SDKT`: a companion overriding `replicated = true` has a descriptor saying so; every other
  descriptor says `false`
- [X] T005 [P] In `CORE/PlatformVariables.scala` add `ANKKA_INSTALLATION` to `PlatformOnly` (`:128`)
  and `ANKKA_INSTALLATION_` to `RuntimeOnlyPrefixes` (`:173`), each with the one-line comment the
  others have (R9). `PlatformDeclarationSuite` and the operator's compile of the same file stay
  green; a case in `CORET` that `platformOnly("ANKKA_INSTALLATION")` and
  `runtimeOnly("ANKKA_INSTALLATION_B_DB_PASSWORD")` hold. In `API/descriptors.scala`
  `ServiceSpec.problems` (`:18`) refuses both as it refuses every platform-only variable; a case in
  `controlplane-api`'s `DescriptorSuite`
- [X] T006 [P] Protocol: in `PROTO/discovery.proto` add `bool replicated = 2;` to
  `EventSourcedDetail` (`:54`) and `bool mirrored_changes = 5;` to `ConsumerDetail` (`:125`); in
  `PROTO/event_sourced.proto` add `string installation = 3;` to `EventSourcedIn.Event` and
  `string installation` and `bool at_home` at the next free numbers of `EventSourcedIn.Command`;
  add `NOT_HOME` to `ErrorCode` in `PROTO/payload.proto`; each with the `// 1.19:` comment
  contracts/protocol.md gives. Document the two `ConsumerRequest.metadata` entries in
  `PROTO/consumer.proto`'s comment, and add `optional ReplicationChange replication = 6` to
  `EventSourcedOut.Reply` with the `ReplicationChange` message (contracts/protocol.md "Replication
  effects from a process"). Copy to the three SDKs with `PY/scripts/proto.py`,
  `npm run proto` in `TS`, `RS/scripts/proto.sh`; `diff -r` all three against `PROTO`
- [X] T007 [P] Bump the version to 1.19: `WireProtocol.Version` in `RT/remote/Conversation.scala:183`;
  `ProtocolVersion(1, 19)` and a scaladoc line in `API/Compatibility.scala:77`;
  `PROTOCOL_VERSION` in `PY/src/ankka/service.py:28`, `TS/src/spec.ts:28`,
  `RS/ankka/src/service.rs:22`; `## Version` in `protocol/README.md:16` and its SDK copies. Move the
  pins: `SCT/ProtocolSuite.scala:158`, `controlplane-api/src/test/…/HostingSuite.scala:120` and
  `CompatibilitySuite.scala:58` (1.19 supported, 1.20 not), `TS/test/contracts.test.ts:124`,
  `RS/ankka/tests/contracts_discovery.rs:119`. `AWAIT_SINCE` in `PY/tests/test_await_old_runtime.py:96`
  stays 1.18: it names when awaiting arrived, not the current version
- [X] T008 [P] Settings: in `modules/runtime/src/main/resources/reference.conf` add under `ankka`
  a `replication` block: `installation = ""` with `${?ANKKA_INSTALLATION}`, `installations {}`
  (a map of name to `db { host, port, name, user, password }`, documented as rendered from
  `ANKKA_INSTALLATION_<NAME>_DB_*` by the `ClusterConfig` overlay that renders the other `ANKKA_*_DB_*`
  variables — find where `ANKKA_DB_HOST` is read and add the prefixed form beside it),
  `poll = 1s`, `handover { hold-writes = off, timeout = 30s }` (data-model "Settings"). Add
  `final case class ReplicationSettings(installation: Option[String], installations: Set[String], poll: FiniteDuration, holdWrites: Boolean, handoverTimeout: FiniteDuration)`
  with `def from(config: Config)` in `REPL/ReplicationSettings.scala`, refusing a name containing
  `|` or `/` or longer than 63 characters with a message naming the setting, and a `self` that is
  `None` when `installation` is empty. Case in `RTT/ReplicationSettingsSuite.scala`: the defaults,
  a two-sibling map, an empty `installation`, a refused name
- [X] T009 [P] Effects as values: in `CORE/effect/EventSourcedEffect.scala` add
  `enum ReplicationChange { case HandOver(to: String); case SetChange(add: Set[String], remove: Set[String]) }`;
  `Persisting` (`:30`) gains `replication: Option[ReplicationChange] = None`; `PersistBuilder`
  (`:116`) gains `handOverTo(installation: String)`, `replicateTo(installation: String)`,
  `stopReplicatingTo(installation: String)`, each returning a `PersistBuilder` carrying the change:
  several `replicateTo`/`stopReplicatingTo` on one builder accumulate into one `SetChange` (the
  same name in both sets throws `IllegalArgumentException`), and a `handOverTo` beside a set change
  throws too (contracts/scala-sdk.md "Effects"); `Effects` (where `persist` is offered to a handler)
  gains the three without events, each a `PersistBuilder` over `Vector.empty`; `Materialised` (`:44`)
  gains `replication: Option[ReplicationChange]` and `materialise` (`:61`) carries it through. Cases
  in `CORET`: each builder materialises its change beside its events; two `replicateTo` accumulate;
  a handover beside a set change is refused; `thenReply` after `handOverTo` keeps both

---

## Phase 2: Foundational — the records, the seam, the host's mode, the puller, the kit

**Purpose**: a follower that mirrors the home, in one JVM, with the fixture every feature file
drives. Nothing here is a story on its own; everything after it is.

**⚠️ CRITICAL**: no story phase begins until T010–T030 are green.

### The records (`runtime`)

- [X] T010 In `RT/wire.scala`: `JournalRecord` (`:181`) gains `installation: Option[String] = None`
  with `@JsonInclude(JsonInclude.Include.NON_ABSENT)` as `WorkflowRecord.standing` has (`:233`
  region); constants `KindHome = 3`, `KindReplicationSet = 4`, `KindHandover = 5`,
  `KindHandoverAbandoned = 6`, `KindConflict = 7` beside `KindDomain` (`:189`) with constructors
  `home(name, at)`, `replicationSet(names, at)`, `handover(target, at)`, `handoverAbandoned(target, at)`,
  `conflict(other)` writing the manifests and payloads data-model gives (UTF-8 text; the set as a
  JSON array through the shared codec); `StateRecord` (`:202`) and `RemoteStateRecord` (`:218`)
  gain `home: Option[String]`, `replicationSet: Option[Vector[String]]`, `pendingHandover: Option[String]`,
  `conflict: Option[String]`, all `NON_ABSENT`, defaults `None`. Run T001's suites: the fixtures
  must still match; if a line differs, an annotation is missing
- [X] T011 Case in `RTT/JournalRecordSuite.scala` (new, `pekko.actor.provider = local` if a system
  is needed; otherwise plain): a record with `installation = Some("leipzig")` round-trips; each of
  the five constructors reads back its kind, manifest and payload; a kind 4 record's set is read
  as a `Set[String]`; a `StateRecord` written without the four fields decodes with `None`, empty,
  `None`, `None`

### The seam (`runtime/replication`)

- [X] T012 [P] Write `REPL/ReplicationTransport.scala` exactly as contracts/transport-seam.md gives
  it: the trait with `name`, `records`, `forward`, `report`, `locate`, `advanced`, `reachable`,
  `stop`, and the values `Replicated`, `ForwardedCall`, `ForwardedReply` (`Succeeded`, `Rejected`),
  `Located`, `Advanced`; `trait ReplicationTransportProvider` with
  `transport(system, config, settings): Option[ReplicationTransport]`. Scaladoc on each operation
  is the contract's text: what the runtime guarantees and what it relies on. Nothing in the file is
  `private[ankka]`
- [X] T013 [P] Write `REPL/LocalInstallations.scala`: a JVM-wide registry
  `register(installation: String, service: AnkkaService): Unit`, `unregister`, `lookup(name): Option[AnkkaService]`,
  under a lock, so the runtime's own transport reaches a sibling in the same JVM straight through
  its `ShardingTransport` (R6, the test kit's case). `AnkkaService` (`RT/Ankka.scala:452`) registers
  itself at start when `settings.installation` is set and unregisters on stop; a second
  registration of one name in one JVM is an `IllegalStateException` naming it
- [X] T014 Resolution at start: in `RT/Ankka.scala` `ServiceBuilder` (`:97`) gains
  `private[ankka] def withReplicationTransport(t: ReplicationTransport): ServiceBuilder` (for the
  test kit, as `withIdentity` at `:131` is) and `start` resolves the transport as the contract's
  "Resolution at start" lists: settings; empty `installations` → `None`; the first
  `ReplicationTransportProvider` from `java.util.ServiceLoader` (as `RuntimeExtensionProvider.scala:30`
  loads) returning `Some`; else `DatabaseReplicationTransport` when every sibling has `db.*`; else
  `StartRefusal.refuse(…)` (`:204` is the pattern) with the message the contract quotes, naming
  "a replication transport" and the installations. Hand the resolved
  `Option[Replication]` (a small `final case class Replication(settings, transport, self: String)`
  in `REPL/Replication.scala`) to `EventSourcedEntityHost.behavior` (`:40`) and to the remote host,
  the projection runtime and the observability endpoint through the service's existing wiring.
  `ComponentRegistry.from` (`:166`) refuses a descriptor whose `replicated` is true and whose kind is
  not an event sourced entity, naming the component (FR-002)
- [X] T015 Case in `RTT/ReplicationResolutionSuite.scala`: no installations → no transport and the
  service starts; two installations with `db.*` for each → `DatabaseReplicationTransport` by name;
  two installations, one without `db.*`, no provider → `StartRefusal` whose message contains
  "replication transport" and both names (assert the message, not the exception type alone:
  quickstart's red condition); a provider on the test classpath (a `META-INF/services` file under
  `modules/runtime/src/test/resources`) returning `Some` wins over `db.*`; a key value entity
  descriptor with `replicated = true` is refused naming it
- [X] T016 Write `REPL/DatabaseReplicationTransport.scala` (R6, the contract's table):
  `records` through `eventsByPersistenceId` on a query plugin per sibling rendered at construction
  into the system's config as `ankka.replication.installations.<name>.query` with its own
  `connection-factory` (the spike's `start` in `TKT/ReplicationOrderSpike.scala` is the config to
  copy), `refresh-interval = settings.poll`, mapping each envelope to `Replicated(seqNr, record)`;
  `locate` through `currentEventsByPersistenceId(pid, 1, 1)` at each sibling in turn (a kind 3 first
  record names the home and the following kind 4 the set; another kind means the sibling holding
  it is the home and the set is `{sibling}`); `forward` and `report` through
  `LocalInstallations.lookup(name)` to that service's `ShardingTransport` (`forward` →
  `askWithMetadata`-shaped call of `Invoke`; `report` → a `Reported` module command, T019), falling
  back to T057's loopback routes when the name is not in this JVM; `reachable` true while the last
  pull or forward of that sibling succeeded within two polls; `advanced` is T022's feed. `name` is
  `"the runtime's own database transport"`
- [X] T017 Case in `RTT/DatabaseReplicationTransportSuite.scala` (two databases from the runtime
  tests' Postgres fixture, `pekko.actor.provider = local`): records written into one database read
  from the other side in order from a position; `locate` finds a kind 3 first record and reports a
  pre-feature journal's holder as home; `records` from a position beyond the end stays open and
  emits the next write within two polls; an unknown name fails `forward` and `reachable` is false

### The host's mode and the mirrored record (`runtime`)

- [X] T018 In `RT/EventSourcedEntityHost.scala`: `Stored` (`:31`) gains `home: Option[String]`,
  `replicationSet: Set[String]`, `pendingHandover: Option[String]`, `conflict: Option[String]`
  (data-model "Stored"); `Journaled` (`:35`) gains `Home`, `ReplicationSet`, `Handover`,
  `HandoverAbandoned`, `Conflict`, `Mirrored(record)`; `toJournal` (`:293`) writes each platform
  case as T010's constructor stamped with `self` and returns a `Mirrored`'s record unchanged;
  `fromJournal` (`:307`) reads kinds 3–7 into the cases (never `Mirrored`); `onEvent` (`:248`)
  folds each case into `Stored` and folds a `Mirrored` by reading its record's kind as `fromJournal`
  would (a domain record through the entity's `_applyEvent`, with T026's event context set from the
  record's `installation`); a domain record a handler persists is stamped `installation = self`. Add
  `enum Mode { Unreplicated, Unknown, Home, Follower, Outside }` and `def modeOf(stored, replication)`
  as data-model derives it, in `REPL/Mode.scala` so the remote host (T063) uses the same function.
  `toJournal`/`fromJournal` of the snapshot (`:327`, `:335`) carry the four fields
- [X] T019 Module commands: in `RT/wire.scala` add `Replicate(sequenceNr, record, source: String, ack: ActorRef[ReplicateAck])`,
  `enum ReplicateAck { case Persisted(sequenceNr); case Refused(reason) }`, `Wake`,
  `Reported(installation, position)`, `Forwarded(replyTo, result: Try[ForwardedReply])`,
  `Locate(result: Try[Option[Located]])`, all `EntityProtocol.ModuleCommand` and
  `AnkkaSerializable` (contracts/runtime-wire.md "Sharded messages"). In the host's `onCommand`
  (`:86`): `Replicate` at a follower persists `Mirrored(record)` when `sequenceNr == lastSequenceNumber + 1`
  and the record's `installation` is in the set or is the pending home, acking `Persisted`; a
  record with no `installation` is persisted with `installation = Some(source)` and nothing else
  changed (R5: a legacy record, stamped once it leaves the home's own journal); otherwise `Refused`
  with the reason, logged. `Wake` is handled as "start, and start the puller if a follower; at a
  copy with no record (`home = None`) ask `transport.locate` once, piped back as `Locate`, and start
  the puller when the answer's set contains `self`" (R7). `WorkflowHost`, `KeyValueEntityHost`,
  the remote hosts and the agent hosts answer the five `unhandled`, with a reply where the command
  carries a `replyTo` (CLAUDE.md, "every host must handle unexpected commands explicitly")
- [X] T020 Case in `RTT/MirroringSuite.scala` (`EventSourcedBehaviorTestKit` over the host's
  behaviour with a `Replication` of two installations and a stub transport; `pekkoPersistenceTestkit`
  is declared in `project/Dependencies.scala:119`, so add it to `runtime`'s `% Test` dependencies
  in `build.sbt` if no runtime suite has it yet): five `Replicate`s in order are persisted and the
  journal holds the records byte for byte; an unstamped record arrives stamped with the source and
  its kind, manifest and payload untouched; the sixth with a gap is `Refused` and nothing is
  persisted; a record from an installation outside the set is `Refused`; a `Wake` at a copy with
  no record calls `locate` once and starts pulling when the set names `self`, and does nothing when
  it does not;
  a `Replicate` at the home is `Refused`; a persisting command at a follower is not handled
  locally (it is T036's forward; here assert nothing is persisted); the mode after a kind 3 naming
  self is `Home`, after a kind 4 without self `Outside`

### The puller and the wake (`runtime/replication`)

- [X] T021 Write `REPL/Puller.scala` (R7): a child stream of the follower's actor,
  `transport.records(home, componentId, entityId, lastSequenceNumber + 1)` through `ActorFlow.ask`
  into `Replicate`, restarted with backoff (`RestartSource.withBackoff`, 250ms to 5s) on completion
  or failure, failing the stream on a `Refused` ack so it restarts from the copy's position; stopped
  when the actor passivates; `switch(newHome)` stops and restarts it from the same position. The
  host starts it when its mode is `Follower` at recovery completion or on `Wake`, and calls
  `switch` when it persists a kind 3 naming another installation (FR-012)
- [X] T022 Write `REPL/ReplicationRuntime.scala`: a `RuntimeExtension` the service builder adds when
  a transport was resolved, which runs `transport.advanced` and sends `Wake` to the sharded entity
  each element names (once per entity per poll, deduplicated); for the runtime's own transport
  `advanced` is one `ShardedDaemonProcess` per sibling over `eventsBySlices` on that sibling's query
  plugin for every replicated component (as `RT/ProjectionRuntime.scala:1243` runs one), filtered
  to records whose `installation` is that sibling, offsets in the local projection offset store
  under the projection id `replication-wake-<sibling>-<component>`. A `Wake` for an entity this
  installation holds no record of is sent only when the record that advanced is a kind 4 naming
  `self` (R7: otherwise every write elsewhere would make an outside copy `locate`); whether a
  record exists locally is one `currentEventsByPersistenceId(pid, 1, 1)` on the local journal,
  cached per entity for the daemon's life. Stops the transport on shutdown, after the hosts
- [X] T023 Case in `RTT/PullerSuite.scala`: a scripted `records` source of ten records ends with
  the follower holding ten; a source that fails after three is reopened from four (assert the
  `from` the second open was asked with); `switch` reopens against the new name from the same
  position; a `Refused` ack reopens from the copy's position, not from the refused one

### The test kit (`testkit`)

- [X] T024 In `TK/AnkkaTestKit.scala` add `object AnkkaTestKit.installations(names: String*): Installations`
  which acquires one database per name from `SharedPostgres` up front, and `final class Installations`
  with `start(name, descriptors, extensions = Nil, configure = identity, database: Option[Database] = None): AnkkaTestKit`
  (`database` lets a name take over a database an earlier, unreplicated kit wrote, for the history
  feature; the service
  configured with `ankka.replication.installation = name` and every other name's `db.*` from its
  database, through `configure` as `start`'s `settings` are set at `:274`, and
  `withReplicationTransport(transport)`), `apply(name): AnkkaTestKit`, `transport: TestReplicationTransport`
  (one per group) and `stopAll()`. Releases every database when stopped (R14). The group handle,
  not a `siblings` parameter, because a copy must know its siblings' databases before they start
  (contracts/scala-sdk.md "Test kits")
- [X] T025 Write `TK/TestReplicationTransport.scala`: wraps one `DatabaseReplicationTransport` per
  installation of the group (each built against that installation's system and config) and offers
  `pause(from, to)`, `resume(from, to)` (a paused pair's `records` source stalls without completing,
  and its `report`s are held and delivered on resume), `unreachable(name, Boolean)` (`forward` and
  `report` to it fail at once, `reachable` false, `records` from it stalls) and
  `inProcessForward: Boolean = true` (false makes `forward` go through T057's loopback route even
  within the JVM, for the transport feature). Case in `TKT/TestReplicationTransportSuite.scala`:
  pause holds, resume delivers in order, unreachable fails a forward with a message naming the
  installation
- [X] T026 In `SDK/contexts.scala`: `CommandContext` (`:59`) gains `installation: Option[String]`
  and `isHome: Boolean`; `SimpleCommandContext` (`:94`) carries them (defaults `None`, `true`);
  add `trait EventContext { def installation: Option[String]; def isLocal: Boolean; def sequenceNumber: Long }`
  and `SimpleEventContext`. In `SDK/EventSourcedEntity.scala` add `protected final def eventContext: EventContext`
  beside `commandContext` (`:49`), throwing outside `applyEvent` with the same wording, and
  `private[ankka] def _setEventContext`; `_applyEvent` (`:60`) is unchanged in signature. The host
  (T018) sets it around every apply and replay from the record: `installation` is the record's, or
  `Some(self)` when the record carries none and the service names installations (FR-013: an
  unstamped record is one this installation wrote, since a follower stamps what it mirrors, T019),
  `None` on an unreplicated service; `isLocal` is `installation.forall(_ == self)`. Cases in `SDKT`:
  `eventContext` outside `applyEvent` throws; inside, a kit-set context is read
- [X] T027 Write the fixture service in `TKT/replication/FleetComponents.scala`: `VehicleEntity`
  (`replicated = true`; state `Vehicle(id, steps: Vector[String], inspections: Vector[String], sold: Boolean, seenAtHome: Vector[Boolean], writtenAt: Vector[Option[String]])`;
  commands `create` (input: the installations to add to the replication set, applied as one
  `replicateTo` per name on the same builder after a `Created` event, which T009 accumulates into
  one kind 4; the first handler run creates the entity as home), `add-step`,
  `record-inspection`, `record-sale` (persists `Sold`), `hand-over` (input: the target;
  `handOverTo`), `replicate-to`, `stop-replicating-to`, query `get`, query `context` answering
  `(commandContext.installation, commandContext.isHome)`; `applyEvent` appends
  `eventContext.installation` and `isLocal` into the state so a scenario can read what the handler
  was told), `FleetView` (vehicles by id), `BuildsConsumer` (`produceTo = Some("vehicle-builds")`,
  default `mirroredChanges`), `MirroredBuildsConsumer` (`mirroredChanges = true`, records each
  change's `installation` and `localOrigin` into a shared `ConcurrentLinkedQueue`), and
  `FleetEndpoint` with a `post("/vehicles/{id}/steps")` route calling `add-step` through the
  component client, marked `// docs:start …` for the pages (R15); and `object FleetMain` starting
  the service from the environment (`ANKKA_INSTALLATION`, the `_DB_*` variables, `ANKKA_HTTP_PORT`)
  for quickstart's "Two installations on one machine"
- [X] T028 Write `TKT/replication/ReplicationSteps.scala`:
  `abstract class ReplicationSteps(features: String) extends GherkinSuite(features) with LogCapturing`
  that reads the Background's installation names from the step
  `"fleet" runs at the installations {string}, {string} and {string}` and starts
  `AnkkaTestKit.installations(...)` with T027's descriptors, one `start` per name; shared steps for
  every file: `Given a service "fleet" with a replicated entity "vehicle"`, `the vehicle {string} was created at {string} with {string} and {string} in its replication set`
  (and the one-name and no-name forms), `a caller at {string} sent five commands to {string}`,
  `When a caller at {string} sends the command {string} to {string}` (keeps the reply and the
  installation it was handled at, read from the reply's metadata `ankka.handled-at` which T036 sets),
  `the command is handled at {string}`, `the caller is answered as the handler of {string} at {string} replied`,
  `the journal of {string} at {string} holds the same records at the same sequence numbers as at {string}`
  (reads both databases' `event_journal` rows for the persistence id through each kit's `Database`
  and compares, per sequence number, the whole `event_payload` bytes from the first kind 3 record
  onward and the decoded kind, manifest and payload before it, where a follower's copy of a legacy
  record carries a stamp the home's does not (R5): bytes, not counts), the transport
  steps `the replication transport to {string} is paused`/`resumed`, `{string} cannot be reached`,
  and the trace step `no call crossed from {string} to {string}` reading the topology as
  `TKT/awaiting/AwaitSteps.scala:467` does. Every `Then` reads the value its `When` produced; a
  wait is an `eventually` on the thing asserted (CLAUDE.md)
- [X] T029 Write the ten final classes in `TKT/replication/`: `HomeFeatures extends ReplicationSteps("../../features/replication/home.feature")`,
  `CreationFeatures`, `ForwardingFeatures`, `HandoverFeatures`, `ReplicationSetFeatures`,
  `ProjectionsFeatures`, `TransportFeatures`, `HistoryFeatures`, `LanguagesFeatures` (its
  `ranElsewhere` names the Python, TypeScript and Rust rows of the outline, as `AwaitSteps.scala:797`
  does), `TestkitFeatures`. Run `sbt 'testkit/testOnly *replication.*'`: every scenario red with a
  step unbound or an assertion, and the count is 46 tests, 43 run and 3 ignored by name
- [X] T030 In `TK/EventSourcedTestKit.scala`: `object EventSourcedTestKit` gains
  `enum Mode { case Home; case Follower(home: String); case Unreplicated }`, `of` (`:170`) takes
  `mode: Mode = Mode.Unreplicated` and `installation: String = "test"`; `callRaw` (`:112`) sets the
  command context's `installation` and `isHome` from the mode, answers a persisting command
  `Left(CommandError(…, NotHome))` naming the home under `Follower`, and reads the
  `Materialised.replication` change into the kit's recorded events as `Journaled` cases so
  `kit.events` shows a handover or a set change beside the domain events. Cases in
  `TKT/EventSourcedTestKitSuite.scala` (or the kit's existing suite): a follower refuses a write
  naming the home and answers a query; a home's `handOverTo` shows in the events; `Unreplicated`
  behaves as today

**Checkpoint**: `sbt core/test sdk/test runtime/test` green; `sbt 'testkit/testOnly *replication.*'`
compiles with 46 tests and every scenario red; T001's fixtures unchanged in the diff.

---

## Phase 3: User Story 1 — written at the home and read anywhere in the replication set (Priority: P1) 🎯 MVP

**Goal**: a write at the home is handled as today; a follower holds the same bytes at the same
sequence numbers and answers reads from its own copy; an entity is created as the home of the
installation that first writes it; two creations meet as a conflict.

**Independent Test**: `sbt 'testkit/testOnly *HomeFeatures *CreationFeatures'` (7 scenarios).

- [X] T031 [US1] In `RT/EventSourcedEntityHost.scala` `onCommand`: the mode gate. `Unreplicated`
  skips every check (`installations.isEmpty` first, before anything else: SC-005). `Home` handles
  as today, stamping records with `self`. `Follower` answers a read-only `Invoke` from its state and
  passes a persisting one to T036 (until then, `Rejected(NotHome)` naming the home). `Outside`
  refuses a read-only `Invoke` `Rejected(NotHome)` naming the home. `Unknown` asks
  `transport.locate` once, piped back as T019's `Locate`, the `Invoke` stashed meanwhile:
  `Some(located)` folds nothing and answers as `Outside` would (a read refused naming the home; a
  write forwarded, T036), or starts the puller when the set names `self`; `None` and a persisting
  command creates: persist `Home(self)`, `ReplicationSet(Set(self))`, then the handler's events
  (R10, clarification Q2); `None` and a read answers the empty state as today. A follower observing
  a mirrored deletion or expiry shows the empty state and persists nothing of its own (spec, Edge
  Cases "Deletion and expiry"); assert it in T020's suite
- [X] T032 [US1] The reserved query: `private[ankka] val Replication: MethodName = MethodName("ankka:replication")`
  in `SDK/EventSourcedEntity.scala`'s companion object beside the descriptor's refusal of the
  `ankka:` prefix (a case in `SDKT` that a user handler named so is refused, as `ankka:lifecycle`
  is); `final case class ReplicationStatus(home, replicationSet, pendingHandover, conflict, position)`
  in `SDK/ReplicationStatus.scala` with a jsoniter codec; the host answers it from `Stored` and
  `lastSequenceNumber`, at any mode, counting it `handled`; `EventSourcedEntityCalls` in
  `SDK/ComponentClient.scala:110` gains `replication: Future[ReplicationStatus]` and a blocking
  `replicationStatus`. Case in `SDKT`: the client decodes a scripted answer
- [X] T033 [US1] Conflict (R11): in the host, a `Replicate` whose record is a kind 3 at sequence 1
  naming another installation while this copy's own first record names `self`, and a `Located`
  answer whose home is not `self` for a copy whose `home` is `self`, each persist `Conflict(other)`
  (kind 7, never handed to a puller: the puller is stopped). A copy with `conflict` set refuses a
  persisting `Invoke` `Rejected(Conflict)` "in conflict with <other>", answers reads, and reports
  it in `ankka:replication`. The puller (T021) is not started for a copy in conflict. Case in
  `RTT/MirroringSuite.scala`: a kind 3 at sequence 1 from another installation against a copy that
  created itself persists kind 7 and the next write is `Conflict`
- [X] T034 [US1] Bind `FEAT/home.feature` in `ReplicationSteps`: `the command is handled and recorded as today`
  (reply equal to a single-installation run's, journal grew by one domain record stamped
  `leipzig`), `a follower holds the same history in the same order as the home` (T028's bytes
  comparison, `eventually` within 5s), `a read at a follower is answered from its own copy` (the
  state, and `no call crossed`), `a replicated entity on one installation behaves as it did before`
  (a kit started with no installations: the journal record carries no `installation` and the
  bytes equal T001's domain line). Green; then make it red once as quickstart says (skip the
  sequence check: the bytes comparison must still fail)
- [X] T035 [US1] Bind `FEAT/creation.feature`: `created as the home of the installation that first writes it`
  (`ankka:replication` at both says `home = leipzig`, set `{leipzig}`, and the journal's first two
  records are kinds 3 and 4), `two creations of one id at two installations are marked in conflict when they meet`
  (pause the pair, create `v9` at both with each other in the set, resume, `eventually` both report
  `conflict`), `an entity in conflict answers reads and refuses writes` (the write's error code is
  `Conflict` and the message names the other; the read answers)

**Checkpoint**: US1 green: 7 scenarios; the MVP is one home, one follower, reads local.

---

## Phase 4: User Story 2 — a write that reaches a follower is handled by the home (Priority: P1)

**Goal**: a persisting command at a follower or outside the set is forwarded and answered with the
home's reply; an unreachable home refuses the write naming itself while reads go on; the forwarded
call is one call in the trace.

**Independent Test**: `sbt 'testkit/testOnly *ForwardingFeatures'` (6 scenarios).

- [X] T036 [US2] Forwarding in `RT/EventSourcedEntityHost.scala` (R10, contracts/runtime-wire.md
  "Forwarding"): a persisting `Invoke` at `Follower` or `Outside` (or `Unknown` after `locate`
  found a home) becomes `ForwardedCall(method, payload, metadata)` captured on the actor's thread,
  sent through `transport.forward(home, …)` under `Observability.calling(home, method)`
  (`RT/Observability.scala:201`) and piped back as `Forwarded(replyTo, result)`; `Success(Succeeded)`
  answers `EntityProtocol.Succeeded` with the reply's metadata plus `ankka.handled-at = <home>`;
  `Success(Rejected(e))` answers `Rejected(e)`; `Failure` answers `Rejected(NotHome)` "home <x>
  cannot be reached" and counts `unanswered` once; the forwarding host never counts `handled`.
  The home's host handles a forwarded call as an `Invoke` from a local caller with the forwarded
  metadata (the `ShardingTransport` path T016's `forward` takes) and sets `ankka.handled-at = self`
  on every reply it gives. `reachable(home) == false` short-circuits to the refusal without a
  call. A forwarded command is never resent (the first rule in `docs/reference/limitations.md`)
- [X] T037 [US2] Case in `RTT/ForwardingSuite.scala` (the behaviour test kit with a scripted
  transport): a persisting command at a follower calls `forward` once with the home's name and the
  caller's metadata and answers the scripted reply; a failed forward answers `NotHome` naming the
  home; the forwarding host's recorder holds one `Client` span and zero `handled`; `reachable =
  false` makes no call; a read at a follower makes no call
- [X] T038 [US2] Bind `FEAT/forwarding.feature`: `answered with the home's reply` (reply equal,
  `ankka.handled-at = leipzig`, the home's journal grew), `outside the replication set is forwarded`
  (a fourth installation in the group not in the set), `whose home cannot be reached is refused naming the home`
  (`transport.unreachable("leipzig", true)`; error code `NotHome`, message contains `leipzig`),
  `a read at a follower whose home cannot be reached is still answered`, `a read at an installation outside the replication set is refused naming the home`,
  `a forwarded command is one call in the trace` (the topology at `bratislava` shows one observed
  call to `leipzig` for the method, handled ok; `leipzig`'s shows it `handled` once). Red first:
  handle the write locally at the follower and watch the second assertion fail

**Checkpoint**: `forwarding.feature` whole is green; a write anywhere lands at the home.

---

## Phase 5: User Story 3 — the home is handed over, and every copy keeps the home's order (Priority: P1)

**Goal**: a handler hands the home over; the new home becomes effective only once its journal ends
with the handover record, the old home writes it only after the new home confirmed, by default
chasing, optionally holding and abandoning; a copy that lagged ends in the home's order.

**Independent Test**: `sbt 'testkit/testOnly *HandoverFeatures'` (8 scenarios, the spike's case
among them).

- [X] T039 [US3] The handover at the home, in `REPL/Handover.scala` and the host (R8): reducing a
  `Materialised.replication = Some(HandOver(x))` at the home checks `x != self`, `x` in
  `settings.installations`, `x` in `replicationSet`, no `pendingHandover`, else `Rejected(BadRequest)`
  naming `x` and why, before anything is persisted; otherwise persists the handler's events then
  `Handover(x)` (kind 5) and answers the caller as the effect says, in both modes (contracts/scala-sdk.md
  "Effects": a plain `Invoke` cannot be held open with `NotYet`). On `Reported(x, position)`
  while `pendingHandover == Some(x)`: `position == lastSequenceNumber` → persist `Home(x)` (kind 3),
  clear, stop handling writes (the mode is now `Follower` or `Outside`), start the puller against
  `x`; else keep waiting. The follower that is the pending target calls `transport.report(home, …, lastSequenceNumber)`
  after every record it persists while its `pendingHandover` names `self`, and once on recovery.
  `Reported` arrives at the home through T016's `report` (in-JVM) or T057's route. Hold mode
  (`settings.holdWrites`): from kind 5 the home stashes persisting `Invoke`s; kind 3 unstashes
  them (they are then forwarded); a timer at `settings.handoverTimeout` persists
  `HandoverAbandoned(x)` (kind 6), unstashes and handles them. A caller learns the outcome through
  T040's `ankka:handover-status`, which answers `Rejected(Unavailable)` "handover to x did not
  happen" once kind 6 is the last platform record
- [X] T040 [US3] `ankka:handover-status`: `private[ankka] val HandoverStatus = MethodName("ankka:handover-status")`
  beside T032's; the host answers `NotYet` while `pendingHandover` is set, `Succeeded` once the
  last platform record is a kind 3 naming the former target, `Rejected(Unavailable)` once it is a
  kind 6. `EventSourcedEntityCalls` in `SDK/ComponentClient.scala` gains
  `awaitHandover(timeout): Future[Done]` looping through `CallTransport.awaitEnd`'s shape
  (`RT/ShardingTransport.scala:112` is the loop to reuse: extract its `NotYet` retry into a
  function both call). Case in `RTT/ShardingTransportSuite.scala`: `NotYet, NotYet, Succeeded`
  ends; `Rejected(Unavailable)` surfaces
- [X] T041 [US3] The switch at the follower (FR-012): in the host, persisting a `Mirrored` whose
  record is kind 3 naming `x != self` calls `puller.switch(x)`; naming `self` stops the puller and
  the mode is `Home`; kind 4 without `self` stops the puller (US4 proves it). The `Replicate`
  check (T019) admits a record whose `installation` is the pending home even before kind 3. Case
  in `RTT/MirroringSuite.scala`: records A1..A5, kind 5 (target B), B-written records are refused
  until kind 3 names B, then admitted; the puller was switched exactly once, at the kind 3
- [X] T042 [US3] Bind `FEAT/handover.feature`: `hands its home to {string}` (sends `hand-over`),
  `the home of {string} was handed to {string}` (sends it and `awaitHandover`), `the journal of {string} at {string} holds the handover and then the sale`,
  `holds no record written at {string} after the handover`, `the five commands from {string} come before the handover and the three from {string} after it`
  (positions of the stamped installations around the kind 3's sequence number),
  `the handover is not recorded while {string} is behind` (no kind 3 within 2s while paused; kind 5
  present), `recorded once … resumed and {string} holds every record before it`, `refused naming {string} as outside the replication set`,
  `holds a handover record naming {string} after every record {string} wrote`, `at the same sequence number`,
  `"fleet" configures a handover to hold writes and to be abandoned after {string}` (the group
  started with `hold-writes = on, timeout = 5s` through `configure`), and the abandonment's
  `Then`s (`a caller waiting for the handover is told it did not happen after {string}`: the step's
  `awaitHandover` fails `Unavailable` naming `vienna` within the duration; `leipzig` is still the
  home; the held `add-step` was handled at `leipzig` after kind 6 was written). The lagged-copy scenario asserts the three journals byte-identical and the
  order; run it ten times in a row once (`sbt 'testkit/testOnly *HandoverFeatures -- "*lagged*"'`
  in a loop) for SC-001's "on every run"

**Checkpoint**: `handover.feature` green, including the spike's scenario; `ReplicationOrderSpike`
still prints Pekko's wrong order for contrast.

---

## Phase 6: User Story 4 — the replication set changes (Priority: P2)

**Goal**: a new entity is held by its home alone; `replicateTo` adds an installation that mirrors
from the first record; `stopReplicatingTo` removes one that stops and keeps its copy; both are
records in the journal.

**Independent Test**: `sbt 'testkit/testOnly *ReplicationSetFeatures'` (6 scenarios).

- [X] T043 [US4] Reduce `SetChange(add, remove)` at the home in `REPL/Handover.scala`'s sibling
  `REPL/SetChange.scala` and the host: every name in `settings.installations`, none of `remove` the
  home, else `Rejected(BadRequest)` naming the first offender; persist the handler's events then
  `ReplicationSet(set ++ add -- remove)` (kind 4, the whole set). A copy that persists a `Mirrored`
  kind 4 without `self` stops its puller, keeps its journal, and is `Outside` (reads refused naming
  the home: T031 already does this from the mode). A copy added learns it through `Wake` (T022's
  feed sees the home's kind 4 naming `self`, and T019's `Wake` at a copy with no record asks
  `locate`) or through `locate` on its first command, and pulls from 1
- [X] T044 [US4] Case in `RTT/MirroringSuite.scala`: a kind 4 adding C at the home and a fresh copy
  at C pulling from 1 ends identical; a kind 4 removing B stops B's puller (the scripted transport
  sees the source cancelled) and B's next read is `NotHome`; `stopReplicatingTo(home)` is
  `BadRequest`
- [X] T045 [US4] Bind `FEAT/replication-set.feature`: `held by its home alone` (`ankka:replication`
  says `{leipzig}` and a read at `vienna` is `NotHome`), `added to the replication set mirrors the whole history`
  (after a dozen writes, `replicate-to vienna`; `eventually` bytes identical from sequence 1),
  `added late holds the same history as the home`, `removed … stops mirroring and keeps its copy`
  (its journal length is fixed while the home's grows by three; the rows are still there),
  `a read at an installation removed … is refused naming the home`, `a change to the replication set is recorded in the entity's own history`
  (a kind 4 at the same sequence number at every copy in the set)

**Checkpoint**: `replication-set.feature` green; the factory story's second half holds.

---

## Phase 7: User Story 5 — views and consumers see the home's order and publish once (Priority: P2)

**Goal**: a follower's view shows a change written at the home; a producing consumer publishes
each change once across installations; a consumer that asks for mirrored changes is given them
and told which is which.

**Independent Test**: `sbt 'testkit/testOnly *ProjectionsFeatures'` (4 scenarios).

- [X] T046 [US5] In `RT/ChangeReader.scala`: `SourceChange.Changed` (`:17`) gains
  `installation: Option[String]`; `journal` reads kinds 3–7 as `Skip` (`:41`, beside expiry) and
  stamps a domain change with the record's `installation`. In `SDK/ChangeSource.scala`:
  `ChangeContext` (`:199`) gains `installation: Option[String]`; `SimpleChangeContext` (`:216`)
  carries it. At the six sites (`RT/ProjectionSupport.scala:107`, `RT/ProjectionRuntime.scala:1496,1563,1613`,
  `RT/TopicHandlers.scala:54,103`) `localOrigin` becomes `installation.forall(_ == self)` where the
  change has one, with `self` from the service's `Replication` (`None` keeps `true`); the topic and
  key value sites pass `installation = None` and stay `true`. Delete the hard-coded `true`s: a grep
  for `localOrigin = true` in `RT` finds nothing afterwards (CLAUDE.md, "re-grep for what a failed
  edit was supposed to remove")
- [X] T047 [US5] In `SDK/Consumer.scala` `Companion` gains `def mirroredChanges: Boolean = false`
  beside `produceTo` (`:53`) and `ConsumerDescriptor` carries it; in `RT/ProjectionRuntime.scala`
  where a consumer's handler is built (`:911`, `typed.produces`, and the graph consumer's handler),
  a change with `installation` set and not `self` is skipped (offset advanced, handler not called)
  when the consumer `produces` or is a graph consumer and `mirroredChanges` is false; a consumer
  that produces nothing is given every change (R12). `TK/ConsumerTestKit.scala` lets a test set
  the change's `installation` and `localOrigin`
- [X] T048 [US5] Cases in `RTT/ChangeReaderSuite.scala` (or the existing reader suite): kinds 3–7
  are `Skip`; a domain record's installation is on the change. In `TKT/ConsumerTestKitSuite.scala`:
  a mirrored change's context says `localOrigin = false` and names the installation
- [X] T049 [US5] Bind `FEAT/projections.feature`: `a view at a follower shows a change written at the home`
  (`FleetView` at `bratislava` `eventually` within 2s, SC-003), `publishes each change once across installations`
  (three installations in the set; count the messages at the kit's broker for the topic, as
  `TKT/InMemoryBrokerSuite.scala` reads it: exactly one per change after a 3s settle),
  `a consumer that asks for mirrored changes is given them` (`MirroredBuildsConsumer`'s queue at
  `bratislava` holds the change with `installation = leipzig`), `a change tells its handler whether it is local or mirrored`
  (`localOrigin` true at `leipzig`, false at `bratislava`). Red first: put one `localOrigin = true`
  back and watch the broker count double

**Checkpoint**: `projections.feature` green; `grep -rn 'localOrigin = true' modules/runtime/src/main` is empty.

---

## Phase 8: User Story 6 — the transport is a seam, and a single installation needs nothing (Priority: P2)

**Goal**: two services on one machine replicate through the runtime's own transport over loopback;
a service naming installations with no transport refuses to start naming it; a non-entity declared
replicated is refused at validation.

**Independent Test**: `sbt 'testkit/testOnly *TransportFeatures'` (3 scenarios, one an outline of
4 rows) and `sbt 'controlPlaneApi/testOnly *DescriptorSuite'`.

- [X] T050 [US6] Loopback routes in `RT/ObservabilityEndpoint.scala` (`:68` region, local mode
  only): `POST /replication/forward` reads a CBOR `ForwardedCall` plus the component and entity ids
  from the path `/replication/forward/<component>/<entity>` and answers the CBOR of a
  `ForwardedReply` after calling this service's `ShardingTransport`; `POST /replication/report/<component>/<entity>`
  reads `(installation, position)` and sends `Reported` to the entity. `DatabaseReplicationTransport`
  (T016) reaches a sibling not in `LocalInstallations` through `ServiceRegistration`
  (`RT/ServiceRegistration.scala:30`, `~/.ankka/running`) by the sibling's installation name, which
  `AnkkaService` adds to its registration record when set. Neither route is served under
  Kubernetes (`ObservabilityEndpoint.start`'s local-mode guard at `:49`)
- [X] T051 [US6] Case in `RTT/ObservabilityEndpointSuite.scala` (or a new `ReplicationRoutesSuite`):
  a `ForwardedCall` posted to the route reaches a scripted `ShardingTransport` and its reply comes
  back as CBOR; a malformed body is 400; a report lands as `Reported`; the routes are absent when
  the endpoint is not local
- [X] T052 [US6] Bind `FEAT/transport.feature`: `started twice on one machine as the installations {string} and {string}, each with its own database`
  (a group of two with `transport.inProcessForward = false`, so the forward and the report go
  through the loopback routes found in a registration directory the suite isolates by setting the
  system property `ankka.running.dir` (`RT/ServiceRegistration.scala:32`) to a temporary directory
  in the test JVM before the group starts, since a forked test does not inherit sbt's `-D`s),
  `creates the vehicle {string} with {string} in its replication set and sends it a command`,
  `holds the same records as at {string} within {string}`; `that names the installations {string} and {string}`
  plus `no replication transport can reach {string}` (a `ServiceBuilder` with the two names and no
  `db.*` for `b`, no provider), `When "fleet" starts` (captures the `StartRefusal`),
  `"fleet" refuses to start`, `the reason names the replication transport as missing` (the
  message contains "replication transport" and `b`); the outline's `declares a <component> replicated`
  through a descriptor built by hand for each row (a key value entity, a workflow, an agent and a
  timer: `ComponentRegistry.from` for the service side, and `API/descriptors.scala`'s `problems`
  for the control plane side, both asserted naming the component)
- [X] T053 [US6] Control plane validation (FR-002): where the service descriptor lists its
  components with a `kind` (`API/descriptors.scala`, the component case classes near `:1086` and
  `:1397`), add `replicated: Boolean = false` (jsoniter: absent reads false) and a problem in
  `problems` for a replicated component whose kind is not the event sourced entity's, naming it;
  the runtime's descriptor export writes the flag. Case in `controlplane-api`'s `DescriptorSuite`:
  the four kinds refused, an entity accepted, an old descriptor without the field accepted

**Checkpoint**: `transport.feature` green; `sbt operator/compile` still compiles `PlatformVariables`
unchanged.

---

## Phase 9: User Story 7 — an entity in every language (Priority: P2)

**Goal**: a process's entity is told where each event was written and whether it is at the home;
its consumer whether a change is local; a process declaring replication beside an older runtime is
refused naming 1.19. Conformance holds every reference.

**Independent Test**: `sbt 'testkit/testOnly *LanguagesFeatures'` (the Scala row and the
old-runtime scenario), `sbt 'sidecar/testOnly *ConformanceSuite'`, and the three SDK kits
(quickstart "The living features", the four languages rows).

### The sidecar (`sidecar`, `runtime/remote`)

- [X] T054 [US7] In `SC/Discovery.scala` add `ReplicationSince = 19` beside `GrantsSince` and
  `WorkflowSourcesSince` and refuse `replicated = true` from a spec below it with
  `s"entity '$id' is replicated, which needs protocol 1.$ReplicationSince; the SDK speaks ${spec.protocolVersion}"`
  (`:354` is the pattern); `SC/Translate.scala` reads `EventSourcedDetail.replicated` into the
  remote entity descriptor and `ConsumerDetail.mirrored_changes` into `ConsumerDescriptor`.
  Case in `SCT/ProtocolSuite.scala`: a 1.18 spec with `replicated` is refused naming 1.19; a 1.19
  one is accepted
- [X] T055 [US7] In `RT/remote/RemoteEventSourcedHost.scala`: `fromJournal` (`:463`) reads kinds
  3–7 into the same `Journaled` cases the in-process host has (share them: move the platform cases
  and `Mode` into `REPL/Mode.scala`, T018), `onEvent` (`:372`) folds them into `RemoteStored`, the
  snapshot carries the four fields (`:490`), the mode gate and the forward are the in-process host's
  (T031, T036; extract both into `REPL/HostReplication.scala` functions over a small `HostView`
  trait both hosts implement, so the two hosts cannot disagree), and `Replicate`/`Wake`/`Reported`/
  `Forwarded` are handled. The process is never sent a platform record as an event; a domain
  record's `installation` goes on `EventSourcedIn.Event`, and `Command` carries `installation` and
  `at_home`, in `SC/GrpcConversation.scala` (`:184`, `:265`, `:349`: the `event(sequence, payload)`
  signature gains `installation: Option[String]`; a `command` gains the two). `RT/remote/RemoteProjection.scala`
  (`:79`, beside `SequenceKey`) adds `ankka.installation` and `ankka.local` to a consumer request's
  metadata
- [X] T056 [US7] Conformance in `SCT/conformance/ConformanceSuite.scala` and the Scala reference
  `SCT/conformance/ConformanceReference.scala`: `es.replicated-declares` (discovery carries the
  flag; the target run as 1.18 by `-Dankka.conformance.shape`'s mechanism is refused naming 1.19),
  `es.replicated-context` (the reference entity `replicated-vehicle` records `at_home` and the
  event's `installation` into its state; the sidecar is started with `installation = "a"` and no
  siblings in the test, so `at_home` is true and `installation` is `a`), `es.replicated-effects`
  (the reference entity's `hand-over`, `replicate-to` and `stop-replicating-to` commands each
  answer with the reply's `ReplicationChange` of contracts/protocol.md "Replication effects from a
  process", and the sidecar's host writes kind 5 or kind 4; a reply carrying `ankka.replication`
  from an entity not declared replicated is `BadRequest`), `consumer.mirrored-declares` (a consumer
  declared `mirrored_changes` discovers so). Each case names the SDK rule it holds in its
  description, as the others do

### Python (`sdks/python`)

- [X] T057 [P] [US7] In `PY/src/ankka/event_sourced_entity.py` the class attribute `replicated: bool = False`;
  `PY/src/ankka/context.py` gains `EventContext(installation: str | None, is_local: bool, sequence: int)`
  and `CommandContext.installation`, `.is_home`; `self.event_context` set around `apply_event`;
  `PY/src/ankka/effects/event_sourced.py` gains `hand_over_to`, `replicate_to`, `stop_replicating_to`
  on the persist builder (one per effect); `PY/src/ankka/consumer.py` gains `mirrored_changes = False`
  and the change context `installation`, `local_origin` from the request metadata;
  `PY/src/ankka/server.py` sends `replicated` and `mirrored_changes` in discovery and refuses a
  runtime below 1.19 at discovery when any entity is replicated, naming it; `PY/src/ankka/testkit/unit.py`
  drives an entity as home or follower (`mode="follower", home="a"`). The effects reach the runtime
  as the reply's `ReplicationChange` field contracts/protocol.md "Replication effects from a process"
  names, which `RemoteEventSourcedHost` (T055) reduces as the in-process host reduces
  `Materialised.replication`
- [X] T058 [P] [US7] Tests `PY/tests/test_replicated.py`: the declaration in discovery; the unit
  kit's follower refusing a write `NOT_HOME` and answering a read; the context inside
  `apply_event`; the integration kit against `ANKKA_SIDECAR_IMAGE` with `installation = "a"`
  proving `at_home` and the event's installation (the Python row of the languages outline); the
  old-runtime refusal naming `1.19` (as `test_await_old_runtime.py` does). `uv run mypy` clean

### TypeScript (`sdks/typescript`)

- [X] T059 [P] [US7] In `TS/src/eventSourcedEntity.ts` `replicated?: boolean` on the definition;
  `applyEvent(state, event, ctx?)` gains the optional third `EventContext { installation?: string; isLocal: boolean; sequence: number }`
  in `TS/src/context.ts`, with `CommandContext.installation`, `.isHome`; `TS/src/effects/eventSourced.ts`
  gains `handOverTo`, `replicateTo`, `stopReplicatingTo`; `TS/src/consumer.ts` gains
  `mirroredChanges?: boolean` and the change context fields; `TS/src/server/discovery.ts` sends
  both flags and refuses a runtime below 1.19 naming it; `TS/src/server/eventSourced.ts` sets the
  context from the message's fields and writes the effect's `ReplicationChange` as T057's;
  `TS/src/testkit/unit.ts` drives home or follower
- [X] T060 [P] [US7] Tests `TS/test/replicated.test.ts` (unit: discovery flag, follower refusal,
  the context) and the slow integration case under `npm run test:slow` against `ANKKA_SIDECAR_IMAGE`
  (the TypeScript row); the old-runtime refusal. `npm run typecheck` clean; a two-argument
  `applyEvent` still compiles (a case that does not pass `ctx`)

### Rust (`sdks/rust`)

- [X] T061 [P] [US7] In `RS/ankka/src/components/event_sourced.rs` `const REPLICATED: bool = false`
  on the trait with a default, and a provided `apply_at(state, &event, &EventContext)` that
  defaults to `apply`, so no existing module changes (`apply`'s signature is kept: a breaking change
  to every module bought nothing a provided method does not); `EventContext { installation(),
  is_local(), sequence() }` and `Context::installation()`, `is_home()`, `written_at()`,
  `local_origin()` in `RS/ankka/src/context.rs`; `RS/ankka/src/effects/event_sourced.rs` gains
  `ReplicationChange` and `hand_over_to`, `replicate_to`, `stop_replicating_to` (two changes, or one
  installation both added and removed, answer an `Internal` fault and persist nothing),
  materialised into the reply's `ReplicationChange` (`RS/ankka/src/effects/materialise.rs`);
  `Consumer` and `GraphConsumer` gain `fn mirrored_changes() -> bool`; the module ABI is the
  protocol's own messages, so the command carries `installation` and `at_home` and `FoldRequest`
  carries `installation` and a new `mirrored = 7` (a module does not know where it runs; false, the
  default, reads as local from an older host); `RS/ankka/src/service.rs` refuses a runtime below
  1.19 naming it; `WasmConversation` fills `mirrored`. `ErrorCode::NotHome` (421)
- [X] T062 [P] [US7] Tests in `RS/ankka/tests/replicated.rs` (the descriptor flag, the unit kit's
  follower refusal, the context) and `RS/conformance.sh` passing the four new cases (the Rust
  row); `cargo test --workspace` and `./conformance.sh` green

### Binding

- [X] T063 [US7] Bind `FEAT/languages.feature` in `ReplicationSteps`: the Scala row (`written in "Scala"`:
  the group of two with T027's entity; `was told it is at the home` reads the `context` query's
  answer kept by the `add-step` handler into the state; `the event handler … was told the event was written at {string}`
  reads `writtenAt` at both) and the old-runtime scenario (`written in "Python" whose entity declares replication`
  and `runs beside a runtime at a protocol version before replication` build a discovery spec by
  hand at 1.18 with `replicated = true` and run it through `Discovery`'s check as `SCT/ProtocolSuite`
  does, in-process, with no Python started; `the service says why, naming the protocol version replication needs`
  asserts `1.19` in the message). `ranElsewhere` names the three SDK rows with the command that
  runs each (quickstart's table)

**Checkpoint**: every language green; protocol 1.19 pinned everywhere; the conformance suite has
four more cases and each SDK runs them.

---

## Phase 10: User Story 8 — an existing entity is made replicated with no migration (Priority: P2)

**Goal**: an entity written before this feature keeps its persistence id and history; its
installation is its home; a second installation mirrors it from the first record; a record with no
installation reads as written at the home.

**Independent Test**: `sbt 'testkit/testOnly *HistoryFeatures'` (2 scenarios).

- [X] T064 [US8] In the host (T031's `Unknown` branch): a copy whose journal is non-empty and has
  no kind 3 is the legacy case. At the installation holding it, the next persisting command
  persists `Home(self)` and `ReplicationSet(Set(self))` at `lastSequenceNumber + 1` and `+ 2`,
  *before* the handler's events, never at sequence 1 (quickstart's red condition for `history`);
  a read answers the state without writing anything. At another installation, `locate` (T016)
  names the holder as home with set `{holder}`, and the puller stamps each unstamped record it
  mirrors with the holder's name (T019), so at the holder `eventContext.installation` reads
  `Some(self)` for an unstamped record (T026) and at a follower the record carries the holder's
  name: both read as written at the home with no lookahead and no snapshot. Case in
  `RTT/MirroringSuite.scala`: a journal of ten domain records without `installation`, then one
  command, holds kinds 3 and 4 at 11 and 12 and the domain record at 13; a replay at the holder
  reads `installation = Some(self)` for all ten; the ten mirrored at a follower carry the holder's
  name and their kind, manifest and payload bytes are unchanged
- [X] T065 [US8] Bind `FEAT/history.feature`: `an existing entity made replicated keeps its history and its installation as its home`
  (a kit started with no installations writes ten records to `v1`; stop it; start a group of two
  on the *same* database for `leipzig` (T024's `database` parameter) and a fresh one for `vienna`;
  `replicate-to vienna`; `eventually` the records at `vienna` equal `leipzig`'s from sequence 1
  under T028's comparison (whole bytes from the kind 3 on, kind, manifest and payload before it),
  and `ankka:replication` says `home = leipzig`), `a record written before this feature is read as written at the home`
  (the entity's `writtenAt` for the first ten reads `leipzig` at both, `leipzig`'s ten rows'
  `event_payload` bytes are unchanged from before the group started, and `BuildsConsumer` at
  `vienna` published nothing for them: the publish-once rule holds for a migrated history)

**Checkpoint**: `history.feature` green; SC-006 holds.

---

## Phase 11: User Story 9 — the test kit drives several installations (Priority: P3)

**Goal**: what Phase 2 built is a public, documented surface: a service author starts several
installations and drives an entity as home or follower.

**Independent Test**: `sbt 'testkit/testOnly *TestkitFeatures'` (2 scenarios).

- [X] T066 [US9] Bind `FEAT/testkit.feature`: `the test kit starts several installations of one service and replicates between them`
  (the Background's group; a write at one, bytes at another; `transport.pause` then `resume`
  observable through the second's lag) and `the entity test kit drives an entity as its home or as a follower`
  (T030's `Mode.Home` handles `add-step` and shows the event; `Mode.Follower("leipzig")` refuses it
  `NotHome` naming `leipzig` and answers `get`). Scaladoc on `AnkkaTestKit.installations`,
  `Installations`, `TestReplicationTransport` and `EventSourcedTestKit.Mode` says what each proves
  and what it cannot (two kits in one JVM are not two installations on a network)
- [X] T067 [P] [US9] `DOCS/build/testing.md`: a section "Several installations" with the group
  handle, pause and resume, `unreachable`, and the pure kit's `Mode`, each sample included from
  `TKT/replication/` regions (`// docs:start …`) so `just docs` checks they compile (R15,
  `.claude/rules/docs.md`)

**Checkpoint**: `testkit.feature` green; `sbt 'testkit/testOnly *replication.*'` reports 46 tests,
43 run, 3 ignored by name, all green.

---

## Phase 12: User Story 10 — the documentation says what replication is and is not (Priority: P3)

**Goal**: a concepts page, the entities guide, the limitations and divergences pages, the
configuration and error pages, the SDK references and three skills say the same thing in the
glossary's words.

**Independent Test**: `sbt 'testkit/testOnly *ReplicatedEntitiesDocumentationSuite'` (3
scenarios) and `just docs`.

- [X] T068 [US10] Write `TKT/ReplicatedEntitiesDocumentationSuite.scala` as
  `TKT/AwaitingWorkflowsDocumentationSuite.scala:10` is written, running
  `features/documentation/replicated-entities.feature`: `describes the home, followers, the replication set and the handover`
  (each of the four words heads a section of `DOCS/concepts/replicated-entities.md` and appears in
  `DOCS/build/event-sourced-entities.md`'s replication section), `says what replication across installations needs and what is not offered`
  (`DOCS/reference/limitations.md` names `ankka-replication`, says "no forced takeover" and "no
  coordinated creation" and no longer contains "One region"), `says which of Akka's replication modes ankka adopted`
  (`DOCS/reference/akka-divergences.md` has a "Replication" section naming replicated reads as
  adopted and replicated writes as not offered). Red before the pages exist
- [X] T069 [P] [US10] `DOCS/concepts/replicated-entities.md` (new): what a replicated entity is,
  the home, followers, the replication set, the handover (chase by default, hold as an option),
  what a read at a follower may be behind by (one poll on the runtime's own transport), conflict
  and what an operator sees, the transport seam and that the link between installations is
  `ankka-replication`; the Pekko evaluation in one paragraph without history ("tells no history":
  what is, not what was tried). Added to the docs navigation where `concepts/consistency.md` is
- [X] T070 [P] [US10] `DOCS/build/event-sourced-entities.md`: a "Replication" section with
  `replicated`, the three effects, `commandContext.installation`/`isHome`, `eventContext`, the
  reserved status query and `awaitHandover`, the errors, each sample included from
  `TKT/replication/FleetComponents.scala` regions; `DOCS/build/consumers.md`: `mirroredChanges`
  and `ChangeContext.installation`; `DOCS/build/views.md`: one paragraph that a follower's view is
  the home's order
- [X] T071 [P] [US10] `DOCS/reference/limitations.md`: replace the "One region" bullet (`:15`) and
  the sentence at `:215` with what one installation offers, that the cross-installation transport
  is the licensed `ankka-replication`, no forced takeover during a partition (writes stay
  unavailable), no coordinated creation, no concurrent-writers mode. `DOCS/reference/akka-divergences.md`:
  a "Replication" section (before "What Akka has that ankka does not", `:213`, from which the
  replication line is removed) saying Akka's replicated reads with a moving primary and
  replication filters is the model adopted, under the names home, follower and replication set,
  and that its replicated writes with merged state is not offered. The family's
  `akka-divergences` list rule (principles 2) is the reason both halves are named
- [X] T072 [P] [US10] `DOCS/reference/configuration.md`: the `ankka.replication.*` keys and the
  two variables, with the platform-only and runtime-only marks the table uses;
  `DOCS/reference/error-codes.md`: `NotHome` with its HTTP and gRPC mappings (T003) and the
  `Conflict` and `Unavailable` meanings replication adds; `DOCS/reference/sidecar-protocol.md`:
  the 1.19 fields and the consumer's two metadata entries; `DOCS/reference/scala-sdk.md`, `python-sdk.md`,
  `typescript-sdk.md`, `rust-sdk.md`: the declaration, the effects and the contexts in each
  language's idiom
- [X] T073 [P] [US10] Skills: `SKILL/ankka-entities/SKILL.md` (declaring, the effects, the
  contexts, the errors, in the glossary's words), `SKILL/ankka-design/SKILL.md` (when to replicate,
  where to put the home, what to keep in the set), `SKILL/ankka-views-consumers/SKILL.md`
  (`mirroredChanges` and publish-once), `SKILL/ankka-python`, `ankka-typescript`, `ankka-rust`
  (the surface and, for Rust, the `apply_event` migration to the `ctx` parameter). Then
  `just docs-sync` and `just docs`; commit what they rewrite

**Checkpoint**: the documentation suite green; `just docs` green; `just features` green (every
scenario named by the spec exists and every word is in the glossary).

---

## Phase 13: Polish — the rules, the whole run, the family

**Purpose**: what every later session needs to know, and the proof the branch is whole.

- [X] T074 [P] `.claude/rules/runtime.md`: a "Replication" section with the traps this feature
  found (the inclusion annotation and the pinned fixtures; `Mirrored` persisted unchanged, never
  re-encoded; `pipeToSelf` for every transport answer; the mode read from `Stored`, never from
  actor memory; `installations.isEmpty` is the only switch an unreplicated service pays for; the
  sequence check is the whole ordering argument at a follower). `.claude/rules/testing.md`: the
  group handle, that two kits in one JVM share `LocalInstallations` so installation names must be
  unique per JVM, and the `inProcessForward = false` switch for the loopback routes.
  `.claude/rules/sidecar.md`: `ReplicationSince`, and that platform records never reach a process
- [X] T075 [P] `.github/ci-coverage.py`: confirm `modules/runtime/src/main/scala/…/replication/`
  and `modules/testkit/src/test/resources/journal/journal-record.txt` are claimed by `build`'s
  filter (they are under existing patterns; the script fails CI if not) and that `sdks/*` changes
  route to their jobs; run `python3 .github/ci-coverage.py`
- [X] T076 `GLOSSARY.md` and `features/`: run `just features`; every word a new step introduced in
  T028–T066 is a term or an everyday word; no `*Proposed.*` remains
- [X] T077 The whole run: `caffeinate -i sbt -Dankka.cluster.tests=off test` green (the hour is
  acceptable; read every suite's duration for an absurd one: CLAUDE.md), then
  `sbt 'sidecar/testOnly *ConformanceSuite'`, the three SDK end-to-end commands from CLAUDE.md,
  `sbt scalafmtCheckAll scalafmtSbt`, `sbt compile` warning-free (`-Werror`), and the quickstart
  "Two installations on one machine" walk-through by hand once with `ANKKA_INSTALLATION=a` and
  `=b` against two `createdb`s. SC-005: compare the suite durations of `sbt testkit/test` on
  `main` and on the branch; a slower existing suite is a short-circuit missed
- [X] T078 [P] `ankka-brain/repos/ankka.md` and the private brain's `repos/ankka-replication.md`:
  the seam's shape as merged, the protocol minor, and that the private half can start against the
  branch's `ankka-runtime` snapshot; the pin row in `ankka-brain-private/family.md` moves from
  "not yet pinned" to the snapshot version. Outside this repository: a note, not a commit here
- [X] T079 Open the pull request against `main` with the spec's summary, the seam named as the
  contract `ankka-replication` builds against, the protocol minor, and the pages added; the
  cluster workflow is not needed (nothing here runs on k3s); squash-merge is the project's habit

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 first and alone (the fixtures must be pinned from the unchanged
  `wire.scala`); then T002 → T003; T004–T009 in parallel with T003.
- **Foundational (Phase 2)**: T010 → T011 (the records); T012 and T013 in parallel after T010;
  T014 → T015 after T012–T013; T016 → T017 after T012–T014; T018 → T019 → T020 after T010 and
  T012; T021 → T023 and T022 after T018–T019; T024 → T025 after T016; T026 after T018; T027 after
  T009 and T026; T028 → T029 after T024–T027; T030 after T009 and T026. The three tracks (records
  and host; seam and transport; kit) are three groups of files and run in parallel once T010 and
  T012 exist.
- **US1 (Phase 3)**: after Phase 2; T031 → T032, T033 → T034, T035.
- **US2 (Phase 4)**: after US1's T031 (the mode gate); T036 → T037 → T038.
- **US3 (Phase 5)**: after US2 (the old home forwards); T039 → T040, T041 → T042.
- **US4 (Phase 6)**: after US1 (reads refused outside the set); independent of US3; shares
  `ReplicationSteps`, so sequence the binding.
- **US5 (Phase 7)**: after Phase 2 only; T046 → T047 → T048 → T049. Independent of US2–US4.
- **US6 (Phase 8)**: T050 → T051 after T016; T053 after T004–T005; T052 after US2 (a forward over
  the loopback route) and T053.
- **US7 (Phase 9)**: T054 after T006–T007; T055 after US2 and T046; T056 after T054–T055; T057–T062
  in parallel after T006–T007 and T055; T063 after T056 and the SDK rows.
- **US8 (Phase 10)**: T064 after US1 and US4 (the set change); T065 after T064 and T024.
- **US9 (Phase 11)**: T066 after US3 (pause and resume observable); T067 any time after T030.
- **US10 (Phase 12)**: T068 first (red); T069–T073 in parallel after the regions they include
  exist (T027, T030); T073 last of them.
- **Polish (Phase 13)**: after every story; T074, T075, T078 in parallel; T076 → T077 → T079.

### User Story Dependencies

- **US1**: Phase 2 only. The MVP.
- **US2**: US1's mode gate.
- **US3**: US2's forward (the old home forwards after the handover) and US1's puller switch.
- **US4**: US1. Proves the puller stops on a kind 4.
- **US5**: Phase 2. Independent of the write-side stories.
- **US6**: US2 for the loopback forward; Phase 1 for the descriptor refusal.
- **US7**: US2 and US5 for the remote host and the consumer metadata; Phase 1's protocol tasks.
- **US8**: US1 and US4.
- **US9**: US3 for pause and resume.
- **US10**: the regions the pages include; otherwise independent.

### Parallel Opportunities

- Phase 1: T004–T009 together after T001.
- Phase 2: the records and the host (T010–T011, T018–T023), the seam and the transport (T012–T017)
  and the kit (T024–T030) are three groups of files.
- Phases 4 and 7 (US2 and US5) touch different files and can run side by side after US1.
- Phase 9: T057–T058, T059–T060, T061–T062 are three languages in three trees.
- Phase 12: T069–T073 together after T068 is red.

---

## Parallel Example: Phase 2, the three tracks

```bash
# After T010 (the record fields and kinds) and T012 (the seam trait):
Task: "T018–T020 the host: Stored, Journaled, Mirrored, Mode, Replicate and Wake; MirroringSuite"
Task: "T013–T017 the seam's resolution, LocalInstallations, DatabaseReplicationTransport and its suite"
Task: "T024–T025 AnkkaTestKit.installations and TestReplicationTransport"
# Then T026–T030: the contexts, the fixture service, the steps, the ten final classes, the pure kit's Mode.
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1: pin the fixtures before a byte moves, then the names.
2. Phase 2: a follower mirrors the home in one JVM; 46 red scenarios.
3. Phase 3: seven scenarios green; break the sequence check once and watch the bytes comparison
   go red.
4. Stop and demo: two installations, a write at one, the same bytes and a local read at the other.

### Incremental Delivery

1. US2 makes the component client the one door: a write anywhere lands at the home.
2. US3 is the handover, with the spike's scenario green by construction and the hold mode.
3. US4 and US5 finish the factory story: the set changes; views and consumers at every
   installation, publishing once.
4. US6 proves the seam from both sides: the loopback transport, the refusal, the validation.
5. US7 carries it to the three SDKs under protocol 1.19 with four conformance cases.
6. US8 proves the year-old journal; US9 and US10 write it down for authors.
7. Phase 13 records the traps and runs everything.

### Notes

- A bound scenario is red before its implementation task; a scenario that passes before the code
  exists is bound to nothing.
- `sbt 'testkit/testOnly *replication.*'` must report 46 tests, 43 run and 3 ignored by name; a
  green run with fewer matched nothing.
- The journal and snapshot fixtures are pinned in T001 from the unchanged `wire.scala`; a diff in
  them after T010 is a missing inclusion annotation, not a fixture to regenerate.
- Installation names are unique per JVM (`LocalInstallations`): the features use `leipzig`,
  `vienna`, `bratislava`, and `a`, `b`; a suite needing more picks new names.
- The protocol minor is 1.19 until something merges first; then every pin in T007 moves.
- Commit after each phase at least; never `git stash` bare in this worktree.
