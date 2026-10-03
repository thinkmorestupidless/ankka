# Contract: `MessageSubscriber`

The seam between the runtime and a broker, in `runtime`. It is public: a service passes one to
`ProjectionRuntime.withBroker`, and a broker other than Kafka is an implementation of it.

## The interface

```scala
trait MessageSubscriber:

  /** Starts reading `subscription.topic` under `subscription.group`. */
  def subscribe(
      subscription: TopicSubscription,
      handle: IncomingMessage => Future[Done]
  ): Subscribed

  /** For each partition of `topic`, when its earliest retained message was published. */
  def earliestRetained(topic: String): Future[Map[Int, Option[Instant]]]

  def stop(): Unit

final case class TopicSubscription(topic: String, group: String, startFrom: StartFrom)

trait Subscribed:
  /** Stops this subscription. Offsets already committed stay committed. */
  def stop(): Unit
```

The three-argument `subscribe(topic, groupId, handle)` is **removed**, not kept beside the new
one. An implementation that kept compiling against it would be handed no start position and would
start wherever it always had, which is the silent difference this feature exists to remove; a
compile error names the method.

## What an implementation owes

1. **One group, one delivery.** A message is handed to one subscriber of a group, and to every
   group. Two subscriptions under one group share the topic; they do not each see all of it.
2. **A group resumes.** A group that has been handed a message, or has been assigned a partition,
   starts again from where it stopped. `startFrom` applies only to what the group has never been
   assigned, as [topic-sources.md](topic-sources.md) says.
3. **A failed handler is not acknowledged.** The message is delivered again. A handler that
   stopped its own subscription and failed is not delivered to again by this instance.
4. **`earliestRetained` answers for every partition**, `None` for one that holds nothing, and
   fails when the broker cannot be asked. It reads nothing under any group and commits nothing.

## Kafka

`KafkaSubscriber` keeps `Consumer.committableSource` inside a `RestartSource`, as today. What
changes:

- The subscription carries a `PartitionAssignmentHandler`. `onAssign` asks `committed` for the
  assigned partitions, and for each with none resolves the start position
  (`beginningOffsets`, `endOffsets` or `offsetsForTimes`), `seek`s to it and `commitSync`s it,
  all through the `RestrictedConsumer` it is handed.
- `auto.offset.reset` stays `earliest`. It no longer decides where a new group starts; it is what
  recovers a group whose committed offset has aged out of the topic.
- `earliestRetained` uses a consumer with no group, assigned every partition, sought to the
  beginning and polled once: the first record of each partition carries the timestamp. It is
  closed when it has answered.
- `Subscribed.stop` shuts that subscription's kill switch, which also ends its restarts.

## `InMemoryBroker`

It keeps every publication already (`published`). It gains what makes a start position and a
group mean something without a container:

- a **position for each group** on each topic, so a group resumes and a new one starts where it
  declares: `earliest` at the first publication, `latest` after the last, a time at the first
  publication at or after it, by the instant recorded when each was published;
- **one delivery for each group**: the subscribers of a group take messages in turn;
- **order within a group**: a subscription that starts behind is handed its backlog first, and
  what is published meanwhile queues behind it;
- `earliestRetained`, answering for its single partition from its first publication;
- `stop()` keeps positions, so `restartService()` resumes, as a broker would;
- a clock a test can set, for a start position that is a time.

It has one partition and never drops a message, so nothing about retention, rebalancing or a
partition added later can be shown with it. Those are `KafkaSuite`'s.

A publication's future completes when every group on the topic has caught up with it, and fails with
the first handler that failed. A failed message stays at the head of its group until the next
publication to the topic, or `redeliver(topic)`. `positions(topic)` says how far each group has read.

The four obligations are one shared set of cases, `SubscriberContract` in `testkit`'s tests, mixed into
`InMemoryBrokerSuite` and `KafkaSuite`. It lives in `testkit` rather than `runtime` because `runtime`'s
test classes are not visible to `testkit`'s.

## Kafka: where the kill switch goes

The shared kill switch sits between the committable source and the handler, not after the committer.
Shut there, it completes what is downstream: the message in hand finishes and the committer flushes its
batch. After the committer, it cancelled the batch, and a group subscribed again was handed everything
since the last flush. (Found by the contract's resume case.)
