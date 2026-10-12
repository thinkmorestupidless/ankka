# Feature Specification: Replicated Entities — One Home, Mirrored Everywhere Else

**Feature Branch**: `051-replicated-entities`

**Created**: 2026-10-12

**Status**: Draft

**Input**: User description: "Replicated entities: the open half of entity replication across
installations. Every replicated entity has exactly one home installation at a time, which alone handles
its writes; the other installations in its replication set mirror the home's journal position for
position, so every copy's journal is identical and serves reads locally; a write that reaches a copy
that is not the home is forwarded to the home; the home can be handed over, and the replication set can
change, both as events in the entity's own history. The paid half — the link between installations,
the directory, the control plane — is the private repository `ankka-replication`, which consumes the
seam this spec defines and never reaches past it."

## Context

A service today runs on one installation, and the platform says so: "One region. There is no
multi-region deployment, replication filtering or routing by origin" (`docs/reference/limitations.md`).
The event sourced host (`EventSourcedEntityHost`) builds one `EventSourcedBehavior` per entity under
the persistence id `<component>|<entity>`, adapts every event into a `JournalRecord` of a kind (domain,
deleted, expiry), a manifest and a payload, and snapshots a `StateRecord`. Views and consumers read the
service's own journal by slices (`ProjectionRuntime`), a consumer's change carries
`ChangeContext.localOrigin`, which is `true` in every one of the five places that build a change, and
`ReadOnlyEffect`'s comment anticipates "the runtime may serve it from a replica without routing to the
entity's write region". Nothing in the runtime, the SDK or the operator knows a second installation.

Pekko ships replicated event sourcing, and it was evaluated first. Three things decided against it.
Its model is several writers at once: every copy persists, version vectors mark concurrency, and the
documentation asks for commutative events. A spike in this branch (`ReplicationOrderSpike`, gated on
`-Dankka.spikes=on`) ran three copies on three databases, handed the home from A to B after five
events, and let C read A's journal late: C applied B's events 6, 7 and 8 before A's 1 to 5, with the
`concurrent` flag false on every one, and C's journal holds `6 7 8 1 2 3 4 5` for good. A buffer by
position repaired the entity's state but not its journal, so a view at C reads the wrong order. And
Pekko publishes no transport between clusters: the gRPC replication Akka has was added after the fork,
under the BSL, so any link between installations is ankka's to write whichever model it chose.

What this feature builds instead is a chain of single writers over mirrored journals. One installation
is the entity's **home** and handles every persisting command. Every other installation in the
entity's **replication set** is a **follower**: it pulls the home's journal from its own last sequence
number upwards, in order, persists each record unchanged, and so holds the same history under the
same persistence id with the same sequence numbers. The home is **handed over** by a record in the
entity's own journal, after everything the old home wrote; a follower reads that record only after
everything before it, and becomes the home only when its own journal ends with it. A third copy reads
one source at a time, switching to the new home's journal at the same sequence number, so it cannot
read a new home's record before an old home's. Order is a property of reading one ordered log in
sequence, not of timing. Reads are served by every copy from its own journal; a write that reaches a
follower is forwarded to the home and the reply returned, and the caller never sees the difference.

The work splits in two by trust and lifecycle. This spec is the open half, in ankka: what an entity
declares, what the journal records, how the host behaves as home or follower, and a
**replication transport** seam the runtime defines with one implementation of its own — in process and
database backed, reading another service's journal through a query plugin of its own, as the spike
does — for tests, the local overlay and a single installation. The private `ankka-replication` repository
supplies the real transport between installations, the directory of every entity's home, the hub for
networks where only one side may open a connection, and the control plane side. It consumes the seam
and must never need a hook inside the runtime; the seam is the contract, and this spec's test kit uses
the same seam, so the open half is proven without the paid one.

This feature makes these decisions.

- **Replication is declared by the component.** An event sourced entity's companion declares itself
  replicated. Nothing else is replicable in this feature: a key value entity or a workflow that asks is
  refused when the descriptor is validated, at the service and at the control plane alike.
- **One home, and the home alone writes.** A persisting command is handled only by the home; a
  read-only command is answered by any copy in the replication set from its own journal, and may be
  behind the home by the replication lag. Anywhere else a read is refused, naming the home. The type
  system already says which is which.
- **Forwarding is the client's job, invisible to the caller.** A persisting command that reaches a
  follower, or an installation outside the replication set, is forwarded to the home through the
  transport and its reply returned as if handled here. The forwarded call is one call in the trace.
- **The home is handed over in the entity's own history.** A handover is a record the home writes
  naming the new home; the old home refuses writes from then on and forwards them. The new home is
  effective only once its journal ends with that record, and the old home writes it only after the
  new home has confirmed it holds everything before it, so the gap at a handover is one record. While
  it waits the old home goes on handling writes by default and asks again; a service may instead have
  it hold writes and abandon the handover after a configurable timeout.
- **The replication set is in the history too.** A new entity is held by its home alone. Adding an
  installation starts it mirroring from the beginning; removing one stops it mirroring and leaves its
  copy as it was, which no query reads again.
- **An entity is created where it is first written.** Each installation that has never seen an entity
  creates it as its home on first write. Two creations of one id that meet are a conflict, marked at
  both, with writes refused until an operator resolves it. Ids a service replicates are chosen so this
  cannot happen, as a vehicle's is.
- **Every copy's journal is identical.** Same persistence id, same sequence numbers, same records,
  platform records included. Views and consumers at every installation read their own journal and see
  the home's order. A record carries the installation that wrote it, and a change tells its handler
  whether it is local or mirrored.
- **Nothing is pushed.** A follower pulls by sequence number from a durable ordered log, from one
  source at a time, and resumes from its own last record after any interruption. Who opens the
  connection is the transport's concern, not the entity's.
- **Honest when the home is away.** A write at a follower whose home cannot be reached is refused
  with an error naming the home; reads go on. There is no forced takeover in this feature.
- **An existing entity can be made replicated.** Its persistence id does not change; the installation
  it lives on is its home; records written before this feature are read as written there.
- **A single installation needs nothing new.** An entity declared replicated whose service names one
  installation behaves exactly as today, and its journal bytes are unchanged for records that carry no
  replication information.

What this feature is not: the link between installations, its dial direction or its hub; the
directory fed from other installations; a project spanning installations in the control plane, the
operator or the console; entitlement; a concurrent-writers mode with merge rules; a forced takeover
while a home is unreachable; or replication of key value entities, workflows, agents or timers.

## Clarifications

### Session 2026-10-12

- Q: Two installations each receive the first write for one entity id before either has heard of the
  other. What happens? → A: Local creation, conflict marked. Each creates the entity as its home at
  once; when the histories meet, the entity is marked in conflict at both, writes are refused with an
  error saying so, reads go on, and an operator resolves it. Coordinated creation is not in this feature.
- Q: Which installations hold a newly created replicated entity before a handler changes its
  replication set? → A: Its home alone.
- Q: How is a read-only command answered at an installation outside the replication set, or at one
  removed from it that still holds an old copy? → A: Refused, with an error naming the home. Only the
  home and its followers answer a read from their own copy; a removed installation's copy is never read
  for a query.
- Q: What does the old home do with writes while a handover waits for the new home to confirm, and if
  it never confirms? → A: By default it goes on handling writes and confirms again until the new home
  holds everything, so a handover under steady load may take time. A service may configure a handover
  to hold writes while it waits and to be abandoned after a configurable timeout, leaving the old home as
  home and telling a caller waiting for the handover. The handover command itself is answered once
  the handover is pending, in either mode.
- Q: Settle the eight proposed glossary terms as written? → A: Yes: replicated entity, home, follower,
  replication set, handover, local change, mirrored change and replication transport are settled, with
  leader, read replica, replay, copy over, migration and failover refused.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A replicated entity is written at its home and read anywhere in its replication set (Priority: P1)

A car maker runs an installation at each factory. A `vehicle` entity is declared replicated. A car is
created at the Leipzig factory, which is its home; its build steps are commands handled there, each
answered as today. The Bratislava factory, in the car's replication set, holds the same history in the
same order and answers queries about the car from its own copy without crossing to Leipzig.

**Why this priority**: This is the feature: writes at one place, the same history everywhere it is
wanted, reads local.

**Independent Test**: In the test kit, start two installations through the in-process transport,
create and update an entity at the first, and assert the second answers queries with the same state and
holds the same records at the same sequence numbers; assert no call crossed to the first for a read.

**Acceptance Scenarios**:

- added `features/replication/home.feature`: a persisting command at the home is handled and recorded as today
- added `features/replication/home.feature`: a follower holds the same history in the same order as the home
- added `features/replication/home.feature`: a read at a follower is answered from its own copy
- added `features/replication/home.feature`: a replicated entity on one installation behaves as it did before
- added `features/replication/creation.feature`: an entity is created as the home of the installation that first writes it
- added `features/replication/creation.feature`: two creations of one id at two installations are marked in conflict when they meet
- added `features/replication/creation.feature`: an entity in conflict answers reads and refuses writes

---

### User Story 2 - A write that reaches a follower is handled by the home (Priority: P1)

A technician in Bratislava records an inspection on a car whose home is Leipzig. The command reaches
the Bratislava installation, is forwarded to Leipzig, handled there, and the reply comes back to the
technician's endpoint. If Leipzig cannot be reached, the technician is told so, and can still read the
car's state in Bratislava.

**Why this priority**: Without forwarding, every caller would have to know where an entity lives; with
it, the component client is the one door it is today.

**Independent Test**: Send a persisting command to the follower installation and assert the reply is
the home's and the home's journal grew; make the home unreachable through the transport and assert the
same command is refused naming the home while a query is still answered.

**Acceptance Scenarios**:

- added `features/replication/forwarding.feature`: a persisting command at a follower is answered with the home's reply
- added `features/replication/forwarding.feature`: a persisting command at an installation outside the replication set is forwarded to the home
- added `features/replication/forwarding.feature`: a write at a follower whose home cannot be reached is refused naming the home
- added `features/replication/forwarding.feature`: a read at a follower whose home cannot be reached is still answered
- added `features/replication/forwarding.feature`: a read at an installation outside the replication set is refused naming the home
- added `features/replication/forwarding.feature`: a forwarded command is one call in the trace

---

### User Story 3 - The home is handed over, and every copy keeps the home's order (Priority: P1)

A car built in Leipzig is shipped to a dealership in Vienna. A handler hands the car's home to the
Vienna installation. Vienna handles the next write; Leipzig forwards anything it is still sent. The
Bratislava copy, which was behind when the handover happened, still ends up with every change in the
order Leipzig and then Vienna wrote them.

**Why this priority**: The handover is what makes the home useful rather than a fixed pin, and the
lagging third copy is the case the spike showed Pekko getting wrong.

**Independent Test**: Three installations; write at the first, hand over to the second while the
third's transport is paused, write at the second, resume the third, and assert all three journals are
identical in order and content, on every run; assert a write sent to the old home after the handover
is answered by the new home.

**Acceptance Scenarios**:

- added `features/replication/handover.feature`: the home is handed over and the new home handles the next write
- added `features/replication/handover.feature`: the old home forwards a write it is sent after the handover
- added `features/replication/handover.feature`: a copy that lagged through a handover holds every change in the home's order
- added `features/replication/handover.feature`: a handover waits until the new home holds everything before it
- added `features/replication/handover.feature`: the old home goes on handling writes while a handover waits
- added `features/replication/handover.feature`: a handover configured to hold writes is abandoned when the new home does not confirm in time
- added `features/replication/handover.feature`: a handover to an installation outside the replication set is refused
- added `features/replication/handover.feature`: a handover is recorded in the entity's own history

---

### User Story 4 - The replication set changes (Priority: P2)

A car is created in Leipzig and replicated only there. When it is allotted to the Vienna dealership, a
handler adds Vienna to its replication set, and Vienna mirrors the whole history from the first record.
When the car is sold, Leipzig is removed: it stops mirroring and keeps what it has.

**Why this priority**: Not every installation needs every entity; the replication set is how a service
keeps data where it is used, and the second half of the factory story.

**Independent Test**: Add an installation to an entity's replication set after a dozen writes and assert
it reaches the same history; remove one and assert its copy no longer grows while the home's does;
assert both changes are records in the journal at every copy.

**Acceptance Scenarios**:

- added `features/replication/replication-set.feature`: a newly created entity is held by its home alone
- added `features/replication/replication-set.feature`: an installation added to the replication set mirrors the whole history
- added `features/replication/replication-set.feature`: an installation added late holds the same history as the home
- added `features/replication/replication-set.feature`: an installation removed from the replication set stops mirroring and keeps its copy
- added `features/replication/replication-set.feature`: a read at an installation removed from the replication set is refused naming the home
- added `features/replication/replication-set.feature`: a change to the replication set is recorded in the entity's own history

---

### User Story 5 - Views and consumers at every installation see the home's order and publish once (Priority: P2)

Each factory's installation keeps a view of the cars on its floor, built from its own journal, and a
consumer that publishes build events to the group's broker. The view at Bratislava shows a car's state
in the order Leipzig wrote it. The consumer publishes each event once across all installations, from
the one that wrote it, unless it asks to react to mirrored changes too.

**Why this priority**: Replicated history that the read side cannot use, or that publishes every
event once per installation, would be worse than none.

**Independent Test**: With two installations, write at the home and assert the follower's view shows
the change; assert a topic receives each change once; mark a consumer as reacting to mirrored changes
and assert it is given them with the change saying so.

**Acceptance Scenarios**:

- added `features/replication/projections.feature`: a view at a follower shows a change written at the home
- added `features/replication/projections.feature`: a consumer that publishes to a topic publishes each change once across installations
- added `features/replication/projections.feature`: a consumer that asks for mirrored changes is given them
- added `features/replication/projections.feature`: a change tells its handler whether it is local or mirrored

---

### User Story 6 - The transport is a seam, and a single installation needs nothing (Priority: P2)

A developer runs two copies of a service on one machine, each with its own database, named as two
installations, and watches an entity replicate between them through the runtime's own transport. The
same service deployed to one installation runs as before. A service that names several installations
with no transport able to reach them fails to start and says what is missing.

**Why this priority**: The seam is what the private half builds against and what the test kit proves
the open half with; a single installation must be unaffected.

**Independent Test**: Start a service naming two installations with the in-process transport
configured and assert replication; start one naming two with none and assert startup fails naming the
transport; start one naming one and assert nothing in its journal or behaviour differs from before.

**Acceptance Scenarios**:

- added `features/replication/transport.feature`: two services on one machine replicate through the runtime's own transport
- added `features/replication/transport.feature`: a service naming several installations without a transport refuses to start, naming what is missing
- added `features/replication/transport.feature`: a key value entity or a workflow declared replicated is refused when the descriptor is validated

---

### User Story 7 - An entity in every language (Priority: P2)

A Python service declares a `vehicle` entity replicated. Its event handler is told where each event was
written and its command handler whether this installation is the home; its consumer is told whether a
change is local or mirrored. Beside a runtime from before this feature, the process is refused at
discovery, naming the protocol version replication needs.

**Why this priority**: The polyglot SDKs are half of ankka's users, and a feature only Scala has is
not a platform feature.

**Independent Test**: The conformance suite declares a replicated entity in each language and asserts
the handler sees the installation and the origin; a process declaring replication against an older
protocol is refused with the version named.

**Acceptance Scenarios**:

- added `features/replication/languages.feature`: an entity in every language is told where a change was written and whether it is at the home
- added `features/replication/languages.feature`: a process declaring replication beside an older runtime is refused naming the protocol version

---

### User Story 8 - An existing entity is made replicated with no migration (Priority: P2)

A service that has run on one installation for a year declares its `order` entity replicated and names
a second installation. Every existing order keeps its history and its home is the installation it has
always been on; the second installation mirrors each order from its first record on demand.

**Why this priority**: A feature that required rewriting a year's journal would not be adopted.

**Independent Test**: Write to a non-replicated entity, redeclare it replicated with a second
installation, and assert the history is intact, the first installation is the home, and the second
reaches the same history; assert a record written before this feature reads as written at the home.

**Acceptance Scenarios**:

- added `features/replication/history.feature`: an existing entity made replicated keeps its history and its installation as its home
- added `features/replication/history.feature`: a record written before this feature is read as written at the home

---

### User Story 9 - The test kit drives several installations (Priority: P3)

A service author writes a test that starts three installations, writes at one, hands over to another,
and asserts the third's view, without any infrastructure but the test kit's own database server. The
pure entity test kit drives an entity as home or as follower.

**Acceptance Scenarios**:

- added `features/replication/testkit.feature`: the test kit starts several installations of one service and replicates between them
- added `features/replication/testkit.feature`: the entity test kit drives an entity as its home or as a follower

---

### User Story 10 - The documentation says what replication is and is not (Priority: P3)

The entities guide and a concepts page explain the home, followers, the replication set, the handover
and what a follower's read may be behind by; the limitations page says what a single installation
offers, that the link between installations is `ankka-replication`, and that there is no forced
takeover; the divergences page says which of Akka's modes was adopted and which was not.

**Acceptance Scenarios**:

- added `features/documentation/replicated-entities.feature`: the documentation describes the home, followers, the replication set and the handover
- added `features/documentation/replicated-entities.feature`: the documentation says what replication across installations needs and what is not offered
- added `features/documentation/replicated-entities.feature`: the documentation says which of Akka's replication modes ankka adopted

---

### Edge Cases

- **The same id created at two installations.** Two creations of one id at two installations before
  either has heard of the other are two homes. When the histories meet, the entity is marked in
  conflict at both: writes are refused with an error saying so, reads go on from each copy, and an
  operator resolves it. The documentation says a replicated entity's id must be chosen so that this
  cannot happen, as a vehicle's is. Coordinated creation is the private half's to offer.
- **A handover while a command is in flight.** A command the old home has accepted before writing the
  handover record is handled there; one that arrives after is forwarded. The reply reaches the caller
  either way. A handover configured to hold writes holds them from the handover command until the record
  is written or the handover abandoned, and then handles or forwards them.
- **A handover that never completes.** By default the old home asks the new home again each time it
  has written more, so under steady load the handover completes at the first quiet moment and the
  entity is never without a home. Configured to hold writes, the handover is abandoned at its timeout,
  the old home stays home, and a caller waiting for the handover is told it did not happen.
- **The old home's instance stops after writing the handover.** The record is in its journal; the new
  home reads it from the durable journal when the transport can, and becomes effective then. Until it
  does, nobody is the home and writes are refused honestly.
- **A follower restarts mid-mirror.** It resumes from its own last sequence number; nothing is skipped
  and nothing is written twice, since a record whose sequence number is not the next is refused.
- **Deletion and expiry.** They are platform records like any other and mirror like any other; a
  deleted entity is deleted everywhere, in the home's order. An expiry is written at the home and
  observed from the mirrored record at every copy; observing it persists nothing, so a follower never
  writes a record of its own.
- **Snapshots.** Each copy snapshots its own journal on its own cadence; a snapshot is never
  replicated, since the journal is.
- **A follower nobody reads.** A copy is woken to mirror when the transport reports the home moved
  on; a passivated follower is behind until then, which is the staleness a read may see. An
  installation newly added to a replication set holds no copy yet; it is woken by the record that
  added it and asks where the entity lives before it starts mirroring.
- **What a mirrored change's handlers see.** A consumer at a follower sees the change with the writer's
  installation and `mirrored`; its message id is the same at every installation, since the persistence
  id and sequence number are. A graph delta's version is the sequence number, the same everywhere.
- **An installation name.** Installation names cannot contain `|`, which separates the persistence id.
- **Order of what a follower pulls.** A follower holds one source at a time and switches only at a
  handover record; a source it cannot reach is retried, never replaced by another source that might be
  further ahead on a different history.
- **The transport lies.** A record from the transport whose installation is not in the replication set,
  or whose sequence number is not the next, is refused and logged; the follower does not advance.

## Requirements *(mandatory)*

### Functional Requirements

**Declaring**

- **FR-001**: An event sourced entity MUST be able to declare itself replicated on its companion, in
  every language; the declaration MUST be part of the component's descriptor and visible to the
  control plane's validation.
- **FR-002**: A key value entity, a workflow, an agent or a timer declared replicated MUST be refused
  when the descriptor is validated, at the service and at the control plane, naming the component.
- **FR-003**: A service MUST name the installation it is, and the installations it may replicate with,
  as platform settings; a service naming more than one with no transport able to reach them MUST refuse
  to start with a message naming the transport that is missing.

**Home and followers**

- **FR-004**: A replicated entity MUST have exactly one home at a time; only the home MAY handle a
  persisting command. A read-only command MUST be answered by any copy in the replication set from its
  own journal, and MUST be refused with an error naming the home at an installation outside the
  replication set, including one removed from it.
- **FR-004a**: An installation that has never seen an entity MUST create it as its home on the first
  persisting command. When two histories for one id meet, the entity MUST be marked in conflict at both,
  persisting commands MUST be refused with an error that says so, read-only commands MUST go on being
  answered, and the conflict MUST be visible to an operator.
- **FR-005**: A follower MUST pull the home's journal from its own last sequence number upwards, in
  order, from one source at a time, and persist each record unchanged under the same persistence id and
  sequence number; a record that is not the next MUST be refused.
- **FR-006**: A persisting command that reaches a follower, or an installation outside the replication
  set, MUST be forwarded to the home through the transport and its reply returned to the caller; the
  forwarded call MUST be one call in the trace and the topology.
- **FR-007**: A persisting command at a copy whose home cannot be reached MUST be refused with an error
  that names the home; read-only commands MUST go on being answered from the copy.
- **FR-008**: A command handler MUST be able to learn the local installation and whether it is the home;
  an event handler and a change's handler MUST be able to learn the installation that wrote the record,
  and whether the change is local or mirrored, truthfully: a change written here is local, a mirrored
  one is not.

**Handover and the replication set**

- **FR-009**: A persisting handler MUST be able to hand the entity's home to another installation in the
  replication set, composed with what it persists; the handover MUST be a record in the entity's own
  journal after everything the old home wrote, and the old home MUST refuse persisting commands from
  then on and forward them.
- **FR-010**: The new home MUST become effective only once its own journal ends with the handover
  record; the old home MUST write it only after the new home has confirmed it holds every record before
  it. The handler's caller is answered once the handover is pending, in either mode; a caller that
  wants the outcome waits for it. By default the old home MUST go on handling persisting commands while it
  waits and ask the new home to confirm again, until it does. A service MAY configure a handover to hold
  persisting commands while it waits and to be abandoned after a configurable timeout, after which the
  old home MUST stay home, handle what it held, and tell a caller waiting for the handover that it did
  not happen. A handover to an installation outside the replication set MUST be refused.
- **FR-011**: A newly created entity's replication set MUST be its home alone. A persisting handler
  MUST be able to add an installation to the replication set or remove one, composed with what it
  persists; both MUST be records in the journal. An added installation MUST mirror from the first
  record; a removed one MUST stop mirroring and keep its copy, which no query reads again.
- **FR-012**: A copy MUST switch its source to the new home at the handover record's sequence number and
  nowhere else, so that no copy ever holds a record of a newer home before every record of an older one.

**The journal**

- **FR-013**: The journal record MUST carry the installation that wrote it, and MUST gain record kinds
  for a handover, a change to the replication set, and the entity's creation as replicated; a record
  written before this feature MUST read as written at the entity's home: it stays unstamped in the
  home's own journal, where an unstamped record is one this installation wrote, and a follower that
  mirrors it MUST stamp it with the installation it pulled it from, so that no follower holds an
  unstamped record. A record carrying no replication information MUST be byte for byte what it was,
  pinned by a fixture.
- **FR-014**: An existing entity made replicated MUST keep its persistence id and history, with the
  installation it lives on as its home and nothing to migrate.

**The transport seam**

- **FR-015**: The runtime MUST define a replication transport with these operations and no others the
  entity depends on: stream an entity's records from a sequence number from a named installation; forward
  a command to a named installation and return its reply; report the sequence number this installation
  holds for an entity; and tell this installation of an entity it has not seen, its home and its
  replication set.
- **FR-016**: The runtime MUST ship one implementation of the transport, in process and database backed,
  reading another installation's journal and forwarding to it, enough for the test kit and for two
  services on one machine; a transport supplied from outside the runtime MUST need nothing the runtime
  does not expose through the seam.

**Projections and publishing**

- **FR-017**: Views and consumers at every installation MUST read their own journal and see the home's
  order. A consumer that publishes to a topic, and a graph consumer, MUST act only on local changes
  unless it declares that it reacts to mirrored changes too.

**Languages**

- **FR-018**: A process's entity MUST be told the installation that wrote each event and whether this
  installation is the home; its consumer MUST be told whether a change is local or mirrored; a process
  declaring replication MUST be refused by a runtime at an earlier protocol version, naming the version.

**Testing**

- **FR-019**: The Scala test kit MUST start several installations of one service on separate databases
  through the runtime's own transport, pause and resume the transport between any two, and make a home
  unreachable; the pure entity test kit MUST drive an entity as home or as follower; the conformance suite
  MUST hold a replicated entity in every language.

**Documentation**

- **FR-020**: The entities guide and a concepts page MUST describe the home, followers, the replication
  set, the handover, and what a read at a follower may be behind by; the limitations page MUST say what
  one installation offers, that the link between installations is `ankka-replication`, and that there is
  no forced takeover and no coordinated creation here; the divergences page MUST say that Akka's
  replicated reads with a moving primary and replication filters is the model adopted, and its replicated
  writes with merged state is not offered; the entities skill MUST say the same.

### Key Entities

- **Replicated entity**: an event sourced entity whose history is held at more than one installation.
- **Home**: the one installation that handles a replicated entity's writes.
- **Follower**: a copy of a replicated entity at an installation in its replication set that is not
  its home; it mirrors the home's journal and answers reads.
- **Replication set**: the installations that hold a replicated entity: its home and its followers.
- **Handover**: the home passing from one installation to another, recorded in the entity's history.
- **Conflict**: the state of an entity created at two installations before either had heard of the
  other, marked at both when the histories meet; writes refused, reads answered, resolved by an operator.
- **Local change** and **mirrored change**: a change written at this installation, and one written at
  another and mirrored here.
- **Replication transport**: what carries records and forwarded commands between installations, behind
  the seam the runtime defines.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Three installations, a handover from the first to the second while the third lags, and a
  late joiner: every copy's journal is identical to the home's in order and content, on every run of the
  suite, with no merge rule and no buffering anywhere.
- **SC-002**: A read at a follower is answered with no call crossing to the home; a write at a follower
  costs exactly one forwarded call.
- **SC-003**: A change written at the home is visible in a follower's view within two seconds on the
  runtime's own transport on one machine.
- **SC-004**: Each change reaches a topic once across three installations, counted at the broker.
- **SC-005**: An entity not declared replicated writes the same journal bytes as before this feature,
  pinned by the record fixture; every existing suite passes unchanged.
- **SC-006**: A service that has run on one installation declares an entity replicated and names a second
  installation, and the second holds every existing entity's full history with no migration step.

## Assumptions

- A follower's wait between pulls, and so its staleness on the runtime's own transport, is a platform
  setting with a default of one second; the private transport chooses its own.
- The entity's sequence numbers are the canonical positions; no second numbering is introduced.
- The runtime's own transport reads another installation's journal through a query plugin configured per
  installation from platform variables, and forwards a command by calling that service directly; it is
  what the test kit, the local overlay and the spike use.
- The handover's hold-writes timeout, where a service configures it, is a platform setting with a
  default the plan chooses; the default handover holds nothing and has no timeout.
- Coordinated creation, and a directory that could refuse a duplicate before two histories meet, are
  the private half's.
- The protocol gains the fields on the next free minor version after features 046 to 048 settle.
- A snapshot's stored form is unchanged; the follower's sequence number is the journal's.

## Dependencies

- 041-postgres-backup-recovery: the journal as "nothing is ever removed", and message ids from the
  persistence id and sequence number, which are what make a mirrored change's id the same everywhere.
- 046-workflow-sources: `ChangeReader` is the one place a record becomes a change; this feature adds
  what a record says about where it was written.
- 037-pipelines and 019-graph-delta-publisher: graph consumers and their versions.
- 009-polyglot-runtimes and 016-wasm-hosting: the entity and consumer messages gain fields; the
  protocol minor version refuses an older runtime.
- 040-cross-project-access: nothing consumed; a forwarded call is a service calling itself at another
  installation, which the private half authenticates.

## Open Questions

- Whether a service should be able to ask for coordinated creation as a per-component policy in a later
  feature, and whether the directory can refuse a duplicate before the histories meet.
- Whether personal data erasure (042) must be applied at every installation of an entity, and by whom.
- A concurrent-writers mode with merge rules for entities that genuinely need every installation
  writing at once, as a later feature; nothing here forecloses it.
- Whether a removed installation's copy should be deleted after a time rather than kept.
