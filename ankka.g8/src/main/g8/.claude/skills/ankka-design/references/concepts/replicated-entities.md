# Replicated entities

> An event sourced entity held at several installations and written at one, its home — followers, the replication set, the handover, forwarded writes, stale reads, conflicts, and the replication transport.

Source: https://docs.ankka.cloud/concepts/replicated-entities/
A replicated entity is an event sourced entity held at several installations of one service and written
at exactly one of them at a time. Every installation that holds it has the same journal for it: the same
records, in the same order, under the same sequence numbers. Each installation answers queries from its
own copy, so a reader near any installation reads locally, and every write still goes through one place,
so the entity keeps the guarantee every event sourced entity has: one command at a time, each decided on
the state the last one left.

An installation is one place a service runs: its own cluster and its own database, such as a factory, a
dealership or a region. A service names the installations it may replicate with in its configuration,
and an entity is replicated only when its companion declares it. Key value entities and workflows are
not replicated; a descriptor that declares one replicated is refused when the service starts.

## The home

The home is the one installation that writes the entity. It runs every command, persisting and read-only
alike, exactly as an unreplicated entity does. The installation where an entity is first written becomes
its home, and the entity's journal records it there: the home is a fact in the entity's own history, not
a setting.

A command handler can ask which installation it runs at and whether that is the home:
`commandContext.installation` and `commandContext.isHome` in Scala, `self.context.installation` and
`self.context.is_home` in Python, `this.context.installation` and `this.context.isHome` in TypeScript,
`ctx.installation()` and `ctx.is_home()` in Rust. A command that may persist runs only at the home, so
`isHome` is false only in a query a follower answers.

## Followers

A follower is an installation that holds a copy of the entity and does not write it. It reads the home's
journal by position, record after record, and persists each record unchanged, so its copy is the home's
history and nothing else. A follower answers every query from its own copy.

A command that may persist and reaches a follower is forwarded to the home, and the home's answer comes
back to the caller as if the follower had handled it. The caller does not choose where to send a write:
it calls the installation it is near, and the entity's home handles it. A reply carries the metadata
entry `ankka.handled-at`, naming the installation that handled the command. A forwarded command is never
forwarded again: when the installation it was forwarded to is no longer the home, the caller is answered
`NotHome` and may send it again.

A read at a follower may be behind the home. With the runtime's own transport a follower looks for new
records every `ankka.replication.poll`, one second by default, so a query there answers what the home
had written up to about one poll before; a write the caller made through the follower is forwarded and
then mirrored back, and a query sent straight after it may not see it yet. A caller that must read its
own write reads at the home.

An event handler can tell where each event it applies was written. Scala's `eventContext`, Python's
`self.event_context`, TypeScript's third `applyEvent` argument and Rust's `apply_at` carry the
installation that wrote the event, whether that is this installation, and the event's sequence number,
the same at every installation. An event written before the entity was replicated reads as written at its
home.

## The replication set

The replication set is the list of installations that hold the entity: its home and its followers. A new
entity's set is its home alone. A handler adds an installation with `replicateTo` and removes one with
`stopReplicatingTo`, beside any events it persists:

```scala
effects.persist(Allotted(dealer)).replicateTo(dealer).thenReply(_ => Done)
```

An installation added to the set copies the whole history from the first record and is a follower once
it has caught up. An installation removed from the set is no longer written to and keeps the copy it has.
It answers no query from that copy: a query there is refused `NotHome`, naming the home, and a write
there is still forwarded. The home cannot remove itself; it hands the home over first. A name the service's
configuration does not list is refused `BadRequest`, and so is one command that adds and removes the same
installation, or asks for a handover and a change to the set at once.

## The handover

A handover makes another installation in the replication set the home. A handler asks for it with
`handOverTo(installation)`, persisting events of its own beside it or none:

```scala
effects.handOverTo("vienna").thenReply(_ => Done)
```

The old home records in the entity's journal that a handover is waiting, and answers the caller then. By
default it goes on handling writes while the new home catches up, and completes the handover at the first
moment the new home holds everything it has written: it records the new home, and from that record on it
writes nothing more. The new home becomes the home once its own copy holds that record, and every
installation reads the new home from the same record, so no two installations are ever the home at the
same position. A handover to an installation outside the set is refused; add it first. One handover waits
at a time.

With `ankka.replication.handover.hold-writes = on` the old home instead holds every write from the
moment the handover is recorded until it completes, and abandons the handover, recording that it did,
after `ankka.replication.handover.timeout`, thirty seconds by default. The writes it held are then
forwarded to the new home, or handled at the old home when the handover was abandoned. Holding gives the
new home a journal that stops growing while it catches up, at the price of writes waiting; the default
never makes a write wait, and completes as soon as the new home keeps up.

A caller that needs the handover complete waits for it. In Scala,
`componentClient.forEventSourcedEntity(id).awaitHandover(Vehicles, "vienna", 30.seconds)`, asked at the
old home, returns once its copy names `vienna` as the home, and throws `Unavailable` when the handover was
abandoned or never asked for, or `Timeout` when it is still waiting.

## How a copy stands

Any copy answers how it stands. In Scala, `componentClient.forEventSourcedEntity(id).replication(Vehicles)`
answers a `ReplicationStatus` from that installation's own journal: the home, the replication set, a
handover waiting to complete, a conflict, and the position its history reaches. Comparing the position
at the home and at a follower says how far behind the follower is.

## Changes at every installation

Views and consumers at every installation read the local journal, which is the same everywhere, so a view
at a follower holds the rows the home's view holds, in the home's order, behind it by what the follower's
copy is behind.

A change is a local change at the installation that wrote it and a mirrored change everywhere else. A
consumer that publishes to a topic, and a graph consumer, are given only local changes by default, so
each change is published once across installations, by the installation that wrote it. One that should
publish at every installation declares `mirroredChanges`. A consumer that publishes nothing is given
every change at every installation. A change's context names the installation that wrote it and whether
that was here: `installation` and `localOrigin` in Scala and TypeScript, `installation` and
`local_origin` in Python, `written_at()` and `local_origin()` in Rust.

## Conflict

Creation is local: the first write at an installation where no other installation holds the entity
creates it there, with that installation as its home. Two installations that each create the same id
before either has heard of the other hold two histories. When they meet, the entity is in conflict at
both: writes are refused `Conflict`, naming the other installation, reads are answered from each copy,
and the status names the conflict. Choose the ids of a replicated entity so that this cannot happen,
for example a vehicle's identification number, which one factory issues.

## When the home cannot be reached

A follower that cannot reach the home goes on answering queries from what it holds. A write there is
refused `NotHome`, naming the home, rather than queued: nothing writes the entity until the home can be reached again. There is no forced takeover:
a home is handed over only by the home itself, so a partition leaves the entity unwritable rather than
written in two places. A write forwarded to a home that did not
answer in time is answered `Timeout`, saying the write may or may not have happened.

## The replication transport

The replication transport is what carries an entity's records, forwarded commands and positions between
installations. The runtime defines it as a seam, `ReplicationTransport`, and ships one implementation:
each installation reads the other installations' journals from their databases, named under
`ankka.replication.installations`, and reaches them for a forwarded command through their local
endpoints. It serves installations on one machine and tests. The replication transport between
installations on a network is `ankka-replication`, licensed separately, which a service gains by having it
on its classpath. A service that names other installations and has no transport for them is refused at
start, naming what is missing.

An existing entity becomes replicated with no migration: declare it replicated and name the
installations, and its history stays where it is, read as written at the installation that held it, which
is its home.
