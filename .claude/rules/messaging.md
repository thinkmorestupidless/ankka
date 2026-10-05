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

## Traps

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
- **`protocol/fixtures/graph-deltas/` is not generated here at all.** `keys.json` and `deltas.json` are
  ankka-flow's, copied byte for byte — its merge sink's suite reads the same rows, which is what makes
  them proof that a graph consumer writes what the sink reads — and `refused.json` is ankka's own.
  `SOURCE.md` there names the ankka-flow commit. Change neither copied file here; copy them again.
  All four SDKs test against all three.
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
- **`runtime` and `sidecar` hold no graph code.** A delta is a value in `core`
  (`core/graph`), the builder is in `sdk`, and what the runtime publishes is bytes under a key.
  If a change needs the runtime to know what a delta is, the change is wrong.
- **A newer SDK on an older runtime would lose messages silently.** A runtime reads a reply case it
  does not know as no case at all, and `Translate.fromConsumerEffect` reads that as `Ignore`. So the
  runtime states its protocol on every consumer request (`ankka.protocol`, set in
  `RemoteProjection.consumerMetadata`), and an SDK fails the change rather than answer
  `produce_all` to a request that does not carry `1.3` or later. An SDK sends an empty list as
  `done` and a single un-keyed message as `produce`, so the guard fires only where it must.
