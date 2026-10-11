---
paths:
  - "modules/core/**"
  - "modules/sdk/**"
  - "modules/runtime/**"
  - "modules/testkit/**"
  - "sidecar/**"
  - "protocol/**"
  - "sdks/**"
---

# Topics, brokers, consumers and graph deltas

## A topic source reads under a group of its own, from where it says, at a version

Feature 024. Every topic subscription's consumer group comes from one function, `ConsumerGroups.name`:
`ankka.<project>.<service>.<kind>[-vN].<id>` for a deployed service, `local` in the project's place for a
local run that states `ankka.service.name`, and the old `ankka-<kind>-<id>` only for one that states
nothing. A deployed service's project and name come from its own certificate (`ServiceIdentity`), never
from configuration, which the service could write itself; `local` is a reserved project id for the same
reason `platform` is. The version sits beside the kind because a component id may contain `.`, and
`ConsumerGroupsSuite` holds distinct inputs to distinct names.

`MessageSubscriber.subscribe` takes a `TopicSubscription` (topic, group, `StartFrom`) and returns a
`Subscribed`. Kafka resolves the start position in a `PartitionAssignmentHandler` and **commits it at
assignment**, so it applies once per partition: without the commit a `latest` group that had read nothing
restarted at the new end. A consumer over a topic must declare a start (`TopicSourceRules`), except one
discovered from an SDK below protocol 1.7, which could not, and starts at `earliest` with a warning.

A view's version is recorded in `ankka_view_versions`, created by the runtime beside the view tables.
A higher declared version empties the table and records itself under the view's exclusive advisory lock;
every topic-view write runs under the same lock, shared, and writes only if the recorded version is its
own (`ViewGuard`). That pair is the whole of "no row an older handler writes survives a rebuild", and
`ViewVersionSuite`'s race case fails without the shared lock. A view is not emptied until
`earliestRetained` has answered. What a topic source is shows in the log (`topic source subscribed:`,
`view rebuild:`, `view behind its recorded version:`), in two metric series and in the local console's
`topicSources`; not yet in `services get`, which a later change can carry over the observe port.

## Views come in two shapes, and both declare their queries

Feature 031. A **plain view** (`View`) reads one source and keeps one row per source id, sliced across
instances. A **keyed view** (`KeyedView`) reads one or more entities, each through a handler of its own,
names every row it writes by key, and reads its own rows (`change.rows.get` / `.ask`); it is a separate
shape because it must handle one change at a time, and the runtime has to know that before it starts.
"One at a time" is the view's advisory lock (`ViewVersions.lockFragment`, the lock a rebuild takes)
taken exclusively in the change's own transaction by every source's handler on every instance; a plain
view takes it shared. Effects are `KeyedViewEffect` in `core`, reduced by `RowChanges.reduce` for the
hosts and every test kit.

A **declared query** is a named SQL statement over the view's own table, checked once at startup by
`QueryCheck` (JSqlParser, in `runtime` only) for Scala and discovered views alike, and run by
`ViewQueries.ask` in a read-only transaction with a statement timeout: the check decides what a statement
says, the database what it does and that it stops. A view that reads entities may declare a version (024
gave topic views theirs): its projections' ids carry the version (`ViewProjections.name`, the one place a
view's projection is named), and every write of such a view is guarded by the recorded version
(`EntityViewGuard`), which pauses a projection that finds the view behind.

## Traps

- **A projection's `R2dbcSession` reads a row count from every statement, and a `SET` reports none.**
  `SET LOCAL statement_timeout` through `session.updateOne` failed every keyed view change with a
  `NullPointerException` deep in pekko-persistence-r2dbc's `updateOneInTx`. Set a transaction-local setting
  as a query, `SELECT set_config('statement_timeout', '…', true)`, through `selectOne`.
- **Every instance must start the same sharded daemon processes.** A sharded daemon process's
  coordinator is a cluster singleton on the oldest node, and runs only if that node started the same
  name. A daemon named for a view's version was one the oldest instance never knew, and the newer
  instances' regions logged "Trying to register to coordinator" forever with nothing running. A version
  goes in the projection id (what offsets are stored under), never in the daemon's name
  (`ViewProjections.daemon`), and an instance behind the recorded version starts the daemon all the same.
- **A view's projection id must not be spellable by another view's id.** Component ids may contain
  `.`, `-` and `_`, so `summary` at version 2 and `summary-v2` at version 1 would share offsets if the
  version were appended to the id. `ViewProjections.name` puts it before the id, as `ConsumerGroups` does
  for groups, and `ViewProjectionsSuite` pins today's version-1 name as a literal: a name that moved would
  make every existing view re-read its source on upgrade, which no suite on an empty database can see.
- **A test fixture shared by several views shares their counters.** Three keyed views reading one entity
  through one handler class made a per-script retry counter reach 2 on the first delivery, and their begin
  and end lines interleave legitimately — each view has its own lock. Key fixture state by the view
  (`ctx.componentId`), and log "end" in a `finally`, or a handler that throws looks like one that overlaps.

- **The Kafka producer's `send` blocks its caller while it waits for a topic's metadata**, a minute by
  default, and a consumer's publish runs on the projection's dispatcher thread. A topic nobody declared held
  a thread a minute per attempt; `KafkaPublisher.TopicWait` bounds it to five seconds, and the change is
  retried with the projection's backoff. `KafkaConnectionSuite` fails at Kafka's default.
- **A component names a topic as declared; the broker holds it under the project's prefix.**
  `KafkaConnection.qualified` is applied where a topic is handed to Kafka and nowhere else — the publisher,
  the subscriber and `earliestRetained` — so declared connections, the topology and the logs keep the
  component's name.

- **`protocol/fixtures/` belongs to `core`'s `EncodingFixturesSuite`**, which refuses any file it did not
  generate. The autonomous agent's fixtures live in `protocol/fixtures/autonomous/`, written by
  `AutonomousFixturesSuite` in `testkit`.
- **`protocol/fixtures/graph-deltas/` is ankka's own** (feature 037): `keys.json` and `deltas.json`
  are written by `GraphFixturesSuite` in `core` from their own elements through the builder
  (`-Dankka.fixtures.regenerate=on`) and refused when a row is not what the builder writes;
  `refused.json` is authored by hand. The graph sink's suites (`modules/graph-sink`) read the same
  rows into the in-memory store, which is what makes them proof that a graph consumer writes what the
  sink reads; ankka-contrib's Neo4j store copies them and proves the same against a Neo4j.
  All four SDKs test against all three; after a change, run each SDK's copy script.
- **A kill switch downstream of `Committer.flow` cancels the commit it was about to flush.** The Kafka
  subscriber's switch sat after the committer, so stopping a subscription cancelled the batch in hand,
  and the same group, subscribed again, was handed everything since the last flush — the subscriber
  contract's resume case read `a1..a5` again. The switch is shared and placed between the source and
  the handler: shutting it completes what is downstream, so the message in hand finishes and the
  committer flushes on completion.
- **Pekko's `SendProducer` does not keep sends in order.** Its `send` is `producerFuture.flatMap(_.send(…))`
  on a multi-threaded dispatcher, so sends issued in order are separate tasks that may reach Kafka in
  either order — and a key only orders what reaches the producer in order. A consumer's several messages
  under one key landed `3, 1` on CI, in a suite that had passed every local run. `KafkaPublisher` calls the
  Kafka producer directly; `KafkaSuite`'s ordering case fails on every run against the old publisher.
- **The in-memory broker's publication future is its groups' delivery.** It completes when every group on
  the topic has caught up, and fails with a handler that failed; a failed message stays at the head of its
  group until the next publication or `redeliver(topic)`. A test that republished to simulate redelivery
  now delivers twice; ask for `redeliver` instead.
- **A record's key and its subject are two things.** `ce-subject` says which entity a message is
  about; the record key says which messages are ordered together and which one a compacted topic
  keeps. They are the same unless a message names a key (`Outgoing.withKey`, and every graph delta
  does). `MessagePublisher`'s keyed `publish` fails by default, on purpose: a publisher that keyed a
  named-key message by its subject would hand a reader a different record and say nothing.
- **`ProjectionSupport.publishAll` is the one place several messages are published**, for a
  consumer in process, behind a sidecar or in a module, and the Scala `ConsumerTestKit` applies an
  effect with `applyConsumer` too. A rule about keys, the subject default, the 4 MiB bound or
  redelivery belongs there or nowhere.
- **A consumer that must fail forever cannot be tested in a running service.** A change that
  cannot be handled is redelivered for ever and stalls its slice of the projection for every test
  after it in the suite. The size bound is held by the pure `PublishAllSuite`; a refused
  publication is tested with `InMemoryBroker.failNext`, which refuses once.
- **A contract is checked at start, from a file, never per message.** The operator writes the project's
  declarations (topics with partitions, `compacted`, contract name and fingerprint; declared brokers) as
  the `ankka-project` ConfigMap, mounted at `/var/run/ankka/project` and named by
  `ANKKA_PROJECT_DECLARATIONS`; `ProjectDeclarations` reads it once and `ProjectionRuntime.rejectUndeclared`
  compares every `DeclaredSource.Topic` and `Publication` with it, refusing through `StartRefusal` (the
  termination log, which the operator reports as `detail`). Without the variable nothing is checked. A
  `Contract` is a name and `sha256:` of the RFC 8785 form (`Contract.Canonical`); every SDK must match
  `protocol/fixtures/contracts/fingerprints.json`, written by `ContractFixturesSuite`. The name rides as
  `ce-type` through `ProjectionSupport.typed`; a message naming its own type keeps it.
- **A declared broker is a second `KafkaConnection`, chosen per topic.** `KafkaConnection.declaredFromEnv`
  reads `ANKKA_TOPIC_BROKER_<NAME>_*` (runtime-only: the prefix is in `PlatformVariables.RuntimeOnlyPrefixes`,
  never `ANKKA_KAFKA_`, which would make the service "supply its own broker" and reach the process);
  `KafkaCredential` is `Certificate(dir)` through `KafkaTls` or `Sasl(dir)` as `SASL_SSL` with a PEM
  truststore. `ProjectionRuntime.subscriberFor`/`publisherFor` pick by `TopicOptions.broker` and
  `Publication.broker`; a declared broker's topics have no prefix and are not "undeclared".
- **Parallel partitions are a partitioned source with one actor per lane.** `TopicSubscription.parallel`
  switches `KafkaSubscriber` to `committablePartitionedSource`, each lane `.async` with its own handler
  instance (`subscribeTopic`'s `lanes`), and a failed message retried in its lane (`RestartSource` around
  `Source.lazyFuture`) so it holds its partition alone. Without `.async` every lane fused into one actor
  and a sleeping handler serialised them; without the per-lane retry one poison message restarted every
  lane. The committer is `.async` too, or a blocked handler holds the commit of what came before it.
- **Lag is polled, never on the message path.** `MessageSubscriber.lag` (a raw consumer under the group:
  `endOffsets` minus `committed`) every `ProjectionRuntime.LagInterval`, onto `TopicSourceStatus.lag`;
  `failing` is set and cleared around every handled message. Both ride on the topology document
  (`topicSources`), which the control plane sums over instances into `ServiceStatus.topicSources`.
- **`runtime` and `sidecar` hold no graph code.** A delta is a value in `core`
  (`core/graph`), the builder is in `sdk`, and what the runtime publishes is bytes under a key.
  If a change needs the runtime to know what a delta is, the change is wrong.
- **A newer SDK on an older runtime would lose messages silently.** A runtime reads a reply case it
  does not know as no case at all, and `Translate.fromConsumerEffect` reads that as `Ignore`. So the
  runtime states its protocol on every consumer request (`ankka.protocol`, set in
  `RemoteProjection.consumerMetadata`), and an SDK fails the change rather than answer
  `produce_all` to a request that does not carry `1.3` or later. An SDK sends an empty list as
  `done` and a single un-keyed message as `produce`, so the guard fires only where it must.
- **A message published from a journal event has an id the event decides** (feature 041):
  `<line>/<persistence id>/<sequence>`, with `/<n>` for each of several, unless the handler declared a
  `ce-id`. The line is the cluster the database was on when the event was written (`HistoryLines`), so an
  event published again after a restore keeps its id. Nothing on the platform deduplicates by it: every
  view and consumer reads a message published again as a new one, which a restore's report says.
- **A restore lists what the broker holds past its moment, asked of the services.** Only a service holds
  a credential for its topics, so `Divergence` (each topic it publishes to or reads, and its groups) is
  served on the observe port and asked by the control plane as a restore is read.
