---
title: Broker topics
description: Read views and consumers from a Kafka topic and publish to one — where a source starts, the group it reads under, rebuilding by version as far back as the broker retains, headers, ordering, and an in-memory broker for tests.
kind: guide
languages: [scala, python, typescript, rust]
components: [view, consumer]
related: [build/views.md, build/consumers.md, build/graph.md, concepts/consistency.md, reference/configuration.md]
---

# Broker topics

A view or a consumer can read from a broker topic instead of an entity, and a consumer can publish to one.
Topics are how an ankka service exchanges messages with systems outside it, including other ankka
services and services written with no ankka at all. Kafka is the broker ankka ships with, and an
installation of the platform provides one for every project.

## Reading from a topic

Declare a topic as the source, with the serializer that decodes its messages:

/// tab | Scala

```scala
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

final case class StockEvent(productId: String, delta: Int)
final case class StockRow(productId: String, level: Int)

final class StockLevelsView extends View[StockEvent, StockRow]:
  def onChange(event: StockEvent): Effect =
    val current = rowState.getOrElse(StockRow(updateContext.subject, 0))
    effects.updateRow(current.copy(level = current.level + event.delta))

object StockLevels
    extends View.Companion[StockLevelsView, StockEvent, StockRow](
      componentId = ComponentId("stock-levels"),
      source = ChangeSource.fromTopic("stock-events", Codecs.serializer[StockEvent]("stock-event")),
      rowSerializer = Codecs.serializer[StockRow]("stock-row")
    ):
  def create(ctx: ViewComponentContext) = new StockLevelsView
```

///

/// tab | Python

```python
from dataclasses import dataclass, replace

from ankka import json_codec
from ankka.effects.view import ViewEffect
from ankka.view import View


@dataclass(frozen=True)
class StockEvent:
    productId: str
    delta: int


@dataclass(frozen=True)
class StockRow:
    productId: str
    level: int = 0


class StockLevels(View[StockEvent, StockRow]):
    component_id = "stock-levels"
    topic = "stock-events"
    event_codec = json_codec(StockEvent, "stock-event")
    row_codec = json_codec(StockRow, "stock-row")

    def on_change(self, event: StockEvent) -> ViewEffect:
        current = self.row or StockRow(self.metadata.subject or "")
        return self.effects.update_row(replace(current, level=current.level + event.delta))
```

///

/// tab | TypeScript

```ts
export const StockEvent = s.record("StockEvent", { productId: s.string, delta: s.int })
export type StockEvent = Infer<typeof StockEvent>

export const StockRow = s.record("StockRow", { productId: s.string, level: s.int })
export type StockRow = Infer<typeof StockRow>

export class StockLevels extends View<StockEvent, StockRow> {
  static readonly componentId = "stock-levels"
  static readonly topic = "stock-events"
  static readonly events = jsonCodec(StockEvent, "stock-event")
  static readonly row = jsonCodec(StockRow, "stock-row")

  onChange(event: StockEvent) {
    const current = this.row ?? { productId: this.subject, level: 0 }
    return this.effects.updateRow({ ...current, level: current.level + event.delta })
  }
}
```

///

A consumer reads a topic the same way: `ChangeSource.fromTopic(...)` in Scala, `topic = "..."` in Python,
`static readonly topic` in TypeScript and `Source::topic("...")` in Rust. A consumer must also say where it
starts; see the next section.

The view's row is keyed by the message's CloudEvents subject, the `ce-subject` header, falling back to the
Kafka record key when the header is absent. A message with neither is skipped by a view rather than
retried, because there is no row it could belong to.

## Where a source starts

A view or consumer that reads a topic declares its **start position**: where it begins the first time its
consumer group reads the topic.

| Start position | Begins at |
|---|---|
| earliest | the oldest message the broker still holds |
| latest | after the newest: only what is published from then on |
| a time | the first message published at or after that time; after the newest when there is none |

A view that declares none starts at the earliest message. **A consumer has no default and must declare
one**: a consumer acts on each message, and starting at the earliest would put everything the broker
holds through its action, while starting at the latest would silently skip the backlog. A consumer over a
topic that declares none is refused when the service starts, naming it.

A start position applies once. The moment a partition of the topic is first assigned to the group, the
offset it names is committed; from then on a restart, a rebalance or a new instance resumes from what the
group has committed, never from the start position again. A time earlier than anything the broker holds
reads from the earliest message it holds, and a time in the future reads as latest.

A partition added to a topic after the group first read it has no committed offset either, so it begins
where the source declares. Under latest, what was published to it before the group was assigned it is not
read.

In each language, a view declared at version 2 with no start position, and a consumer that starts at the
latest message and republishes what it reads:

/// tab | Scala

<!-- include: sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/conformance/ConformanceReference.scala#topic-sources -->
```scala
/** The latest message about each subject. Declares no start, so it reads from the earliest. */
final class TopicRowsView extends View[Fanned, Fanned]:
  def onChange(message: Fanned): Effect = effects.updateRow(message)

object TopicRows
    extends View.Companion[TopicRowsView, Fanned, Fanned](
      componentId = ComponentId("topic-rows"),
      source = ChangeSource.fromTopic(Topic, Codecs.serializer[Fanned]("fanned")),
      rowSerializer = Codecs.serializer[Fanned]("fanned")
    ):
  // Raised from 1 once: every reference declares 2, which names the group it reads under.
  override def version                  = Some(2)
  def create(ctx: ViewComponentContext) = new TopicRowsView

/** Republishes what it reads, from the latest: none of what the topic held when it started. */
final class TopicRelay extends Consumer[Fanned, Fanned]:
  def onMessage(message: Fanned): Effect = effects.produce(message)

object TopicRelay
    extends Consumer.Companion[TopicRelay, Fanned, Fanned](
      componentId = ComponentId("topic-relay"),
      source =
        ChangeSource.fromTopic(Topic, Codecs.serializer[Fanned]("fanned"), StartFrom.Latest)
    ):
  def create(ctx: ConsumerContext) = new TopicRelay

  override val outputSerializer: Option[Serializer[Fanned]] =
    Some(Codecs.serializer[Fanned]("fanned"))

  override val produceTo: Option[String] = Some(Relayed)
```

The start position is the third argument of `ChangeSource.fromTopic`: `StartFrom.Earliest`,
`StartFrom.Latest` or `StartFrom.At(instant)`.

///

/// tab | Python

<!-- include: sdks/python/examples/shopping_cart/conformance.py#topic-sources -->
```python
class TopicRows(View[Fanned, Fanned]):
    """The latest message about each subject. Declares no start, so it reads from the earliest."""

    component_id = "topic-rows"
    topic = "conformance-topic"
    version = 2
    event_codec = json_codec(Fanned, "fanned")
    row_codec = json_codec(Fanned, "fanned")

    def on_change(self, message: Fanned) -> ViewEffect:
        return self.effects.update_row(message)


class TopicRelay(Consumer[Fanned, Fanned]):
    """Republishes what it reads, from the latest: none of what the topic held when it started."""

    component_id = "topic-relay"
    topic = "conformance-topic"
    start_from = StartFrom.LATEST
    message_codec = json_codec(Fanned, "fanned")
    produces_to = "conformance-topic-relayed"
    out_codec = json_codec(Fanned, "fanned")

    def on_message(self, message: Fanned) -> ConsumerEffect:
        return self.effects.produce(message)
```

`start_from` is `StartFrom.EARLIEST`, `StartFrom.LATEST` or `StartFrom.at(when)`, where `when` is a
`datetime` that says its timezone; one that does not is refused, since a time read in the machine's own
zone would start somewhere else on a laptop than in a pod.

///

/// tab | TypeScript

<!-- include: sdks/typescript/examples/shopping-cart/conformance.ts#topic-sources -->
```ts
/** The latest message about each subject. Declares no start, so it reads from the earliest. */
export class TopicRows extends View<Infer<typeof Fanned>, Infer<typeof Fanned>> {
  static readonly componentId = "topic-rows"
  static readonly topic = "conformance-topic"
  static readonly version = 2
  static readonly events = jsonCodec(Fanned, "fanned")
  static readonly row = jsonCodec(Fanned, "fanned")

  onChange(message: Infer<typeof Fanned>) {
    return this.effects.updateRow(message)
  }
}

/** Republishes what it reads, from the latest: none of what the topic held when it started. */
export class TopicRelay extends Consumer<Infer<typeof Fanned>, Infer<typeof Fanned>> {
  static readonly componentId = "topic-relay"
  static readonly topic = "conformance-topic"
  static readonly startFrom = StartFrom.latest
  static readonly message = jsonCodec(Fanned, "fanned")
  static readonly out = jsonCodec(Fanned, "fanned")
  static readonly producesTo = "conformance-topic-relayed"

  onMessage(message: Infer<typeof Fanned>) {
    return this.effects.produce(message)
  }
}
```

`startFrom` is `StartFrom.earliest`, `StartFrom.latest` or `StartFrom.at(date)`.

///

/// tab | Rust

<!-- include: sdks/rust/examples/shopping-cart/src/conformance.rs#topic-sources -->
```rust
/// The latest message about each subject. Declares no start, so it reads from the earliest.
pub struct TopicRows;

impl View for TopicRows {
    type Row = Fanned;
    type Event = Fanned;
    const COMPONENT_ID: &'static str = "topic-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("fanned");

    fn source() -> Source {
        Source::topic("conformance-topic")
    }

    fn version() -> Option<u32> {
        Some(2)
    }

    fn on_event(_: Option<Fanned>, message: Fanned, _: &Context) -> ViewEffect<Fanned> {
        ViewEffect::UpdateRow(message)
    }
}

/// Republishes what it reads, from the latest: none of what the topic held when it started.
pub struct TopicRelay;

impl Consumer for TopicRelay {
    type Message = Fanned;
    const COMPONENT_ID: &'static str = "topic-relay";

    fn source() -> Source {
        Source::topic("conformance-topic")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Latest)
    }

    fn produces_to() -> Option<&'static str> {
        Some("conformance-topic-relayed")
    }

    fn on_message(message: Fanned, _: &Context) -> ConsumerEffect {
        let (payload, _, metadata) = fanned(message.n).into_parts();
        ConsumerEffect::Produce(payload, metadata)
    }
}
```

`start_from()` returns `StartFrom::Earliest`, `StartFrom::Latest` or `StartFrom::AtMillis(millis)`, or
`StartFrom::at(system_time)`. A component is declared before any handler runs, so a start time is
stated, not read.

///

## Rebuilding by version

A view or consumer that reads a topic may declare a **version**, a whole number of 1 or more that only goes
up. None declared is version 1.

When a view starts at a higher version than the one its rows were last built at, it is **rebuilt**: its
table is emptied, the version it now declares is recorded, and it reads the topic again from its start
position, under a consumer group of its own. That is how a view whose handler changed gets rows the new
handler wrote, rather than keeping the old handler's rows and applying the new one only to what comes next.
The group it read under before is left on the broker as it was.

A rebuild reaches back only as far as the broker retains. A topic's retention is a window, not a record:
messages older than it are gone, and a rebuilt view holds only what the window still holds, which may be
fewer rows than it held before. While a rebuild runs, the view serves an empty or partial table. A view
rebuilt with a start position of latest is empty until a message is published.

Before a single row is removed, the service asks the broker when the oldest message it holds on each
partition was published, and logs the answer with the view and both versions — a line beginning
`view rebuild:`. Until the broker answers the view is left as it is, so a broker that is down does not
leave a view empty. A rebuild is never refused for what the broker holds; the log line is how a person
learns what it reached.

Old and new instances of a service run side by side during a rolling update. The rows are emptied once,
however many instances start at the new version, and an instance at a lower version than the one recorded
stops reading the topic for that view and writes nothing more to it, so no row an older handler writes
survives the rebuild. Such an instance stays ready and goes on answering queries from the table. A service
rolled back to a lower version therefore leaves the view as the higher version built it, and not updating;
going back is done by going forward, publishing the old handler under a version higher than the recorded
one.

A consumer keeps no rows. Its version changes only its group, so a consumer started at a higher version
reads the topic again from its start position and acts on every message the broker still holds. During a
rolling update both versions' groups are live, so a message published during it is delivered under each.

A view that reads entities declares a version too, and raising it rebuilds the view from every event and
state its entities recorded; see [Rebuilding by version](views.md#rebuilding-by-version). A consumer that
reads an entity declares none: it has no group to change and no rows to rebuild, and a version that did
nothing would say otherwise, so one is refused when the service starts.

## Consumer groups

Each topic source reads under a Kafka consumer group named for the service it belongs to, so two services
on one broker never share one, whatever their components are called:

| The service | Group, at version 1 | Group, at version N |
|---|---|---|
| deployed in project `shop` as `orders` | `ankka.shop.orders.view.summary` | `ankka.shop.orders.view-vN.summary` |
| run locally, stating the name `orders` | `ankka.local.orders.view.summary` | `ankka.local.orders.view-vN.summary` |
| run locally, stating no name | `ankka-view-summary` | `ankka-view.vN-summary` |

The examples are a view called `summary`; a consumer's say `consumer` where these say `view`. A deployed
service's project and name are read from the certificate the platform issued it, never from its
configuration, so no service can name its groups as another's. A service run on a developer's machine
states its name with `ANKKA_SERVICE_NAME`, or `ankka.service.name` in its configuration; a project made by
`ankka init` states it already. `local` is reserved, and no project can be called it.

## What a service says about its topic sources

When a topic source subscribes, the service logs a line beginning `topic source subscribed:` naming its
kind, component, topic, group, start position and version. A view at a lower version than the one recorded
logs `view behind its recorded version:` as a warning. These lines are the way to read a deployed
service's topic sources today, with `ankka services logs`.

The service's metrics carry the same facts, in two series: `ankka_topic_source_info`, one for each topic
source with its group, start position and version as labels, and `ankka_topic_source_behind`, 1 for a view
that is behind and 0 otherwise. A deployed service serves them on its management port, which only the
service's own instances can reach. A service run locally answers the same facts, as `topicSources`, from
the endpoint the local console reads.

## Connecting to Kafka

A Scala service names the broker with the projection runtime:

```scala
Ankka.service
  .register(StockLevels.descriptor)
  .withExtension(ProjectionRuntime.withKafka("localhost:9092"))
  .start()
```

A component that reads or publishes a topic in a service with no broker configured is refused at startup,
rather than started and never delivering anything.

A deployed service is configured by its descriptor, not by its code, so a Scala service that runs on the
platform finds the broker in its environment instead. `ProjectionRuntime.fromEnv()` connects to the broker
named by `ANKKA_KAFKA_BOOTSTRAP_SERVERS` and runs entity sources only when the variable is absent:

```scala
Ankka.service
  .register(StockLevels.descriptor)
  .withExtension(ProjectionRuntime.fromEnv())
  .start()
```

### The installation's broker

An installation of the platform has one broker, which it provides for every project, as it provides each
project a database. A service of an installation with a broker is told where it is with no descriptor
setting at all: the platform gives every service with components `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, and with
it what the service needs to connect.

A service proves which service it is to the broker with the certificate the platform already issued it.
There is no password and no second credential. What that certificate may do on the broker is decided by
the service's project:

- It may read, publish to and describe every topic of its own project.
- It may read under its own consumer groups, the ones named for it as *Consumer groups* describes.
- It may do nothing else. It cannot make a topic, and it cannot reach another project's topics: the broker
  itself refuses.

A web-hosted service has no components and is given nothing of the broker.

### Declaring a topic

A topic on the installation's broker exists because its project declares it, once, with the number of
partitions it has. A member declares it on the project, not in any service's descriptor:

```bash
ankka projects topics set transactions --partitions 12 -p money
ankka projects topics list -p money
```

The platform makes the topic as soon as it is declared, whether or not the project has a service yet.
Every service of the project publishes to it and reads it by its name, and none of them declares it, so
there is one partition count and one place to change it. Declaring the topic again with more partitions
grows it; with fewer, is refused, since a topic is never made smaller. A name the broker cannot hold, or
partitions outside 1 to 1000, are refused too, naming the topic.

A component names a topic as the project declared it, `transactions`. The broker holds it under a name
that carries the project, `money.transactions`, which is what the broker's own tools list. The platform
adds the project where a topic is handed to the broker and nowhere else, so a component's code, its
declared connections and the service's logs all use the declared name.

`ankka projects topics list` shows how far the platform has got with each topic:

| Phase | Meaning |
|---|---|
| `waiting for broker` | The broker has not yet made the topic, or not yet grown it to the partitions declared. |
| `provisioned` | The topic is ready with its declared partitions. |
| `recovered` | The same, and the topic was on the broker before it was declared: declared again after its declaration was removed. |
| `failed` | Something waiting will not clear, such as a topic that already has more partitions than declared, or an installation with no broker. The detail says which. |

`ankka services get` shows the service's own part, its credential, on the `broker` line: `waiting for
broker`, `provisioned`, `recovered` for a service applied again under its old name, `supplied`, or
`broker provisioning failed`.

### A topic nobody declared

Publishing to a topic does not make it. A component that publishes to a topic its project has not
declared waits: each attempt fails within a few seconds, the service logs `could not publish to topic`
naming the topic, and the change is tried again with the backoff its projection has. Nothing is lost,
and the service stays ready. Once the project declares the topic, the next attempt is delivered.

A component that reads a topic nobody declared waits in the same way, and reads it once it is made.

`ankka services get` names every topic a running service's components read or publish to that its
project has not declared, on the `undeclared topics` line, which is how a name typed wrongly in a
component is found. It is read from the service's running instances, so a service with none says
nothing either way.

### What the platform keeps

The platform never removes a topic, what was published to it, or a service's credential. Deleting the
service leaves them, removing a topic's declaration leaves it, and so does deleting the project. A
service applied again under its old name finds its topic as it left it, and its views and consumers read
on from where their groups had read to.
Removing a topic that is no longer wanted is for whoever runs the installation, with the broker's own
tools; see [The installation's broker](../platform/broker.md).

### A broker of the service's own

A descriptor may name a broker of its own by setting `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in its `env`. The
service then uses that broker, connecting with no certificate, and the platform makes nothing for it on
the installation's: no credential, and its project's declared topics are not made on the broker it
names. An installation with no broker works this way for every service, and a topic declared there is
reported as failed, since there is nowhere to make it.

A service behind a sidecar reaches the broker through its sidecar. The platform gives
`ANKKA_KAFKA_BOOTSTRAP_SERVERS` to the sidecar and to the process as well, whichever broker it names, so
a service can register a component that publishes only where there is a broker to publish to. Without it
the sidecar refuses to start a service that has such a component, naming it. A service hosted as a
WebAssembly module reads the same variable through its configuration.

## Message format

ankka frames messages as CloudEvents in *binary mode*: the body is the plain encoded message, and the
CloudEvents attributes travel beside it as Kafka headers. A consumer written in any language, with or
without ankka, reads an ordinary JSON body and finds the metadata in the headers.

| Header | Value when ankka publishes |
|---|---|
| `ce-specversion` | `1.0` |
| `ce-id` | a new random UUID for each publication |
| `ce-type` | `message` |
| `ce-subject` | the source entity's id |
| `content-type` | `application/json` |

A header set through the metadata passed to `effects.produce(message, metadata)` replaces the default of
the same name, and any other metadata is sent as additional headers. Each message of a consumer that
[publishes several for one change](consumers.md#publishing-several-messages-for-one-change) is framed the
same way, with a `ce-id` of its own. A [graph consumer](graph.md)'s records carry
`ce-type: ankka.graph-delta.v1`.

## The record key and the subject

A message has a **subject**, the entity it is about, and a **record key**, which decides which messages
are ordered together and which record a compacted topic keeps. They are separate. The subject is always
the `ce-subject` header. The record key is the key the message names, and when it names none, the
subject:

| Published with | Record key | `ce-subject` |
|---|---|---|
| `effects.produce(message)` | the source entity's id | the source entity's id |
| `effects.produce(message, metadata)` setting `ce-subject` | that subject | that subject |
| one of several messages, naming no key | its subject | the source entity's id, unless its metadata sets one |
| one of several messages, naming a key | the key it names | the source entity's id, unless its metadata sets one |

Naming a key never changes the subject. An ankka service that reads a keyed message back takes its
subject from the header, so a view's row or a consumer's `subject` is still the entity's id.

## Ordering

Kafka keeps order only within a partition and assigns a record key to one partition, so messages under
one key are published to one partition and read in the order they were written. By default the key is the
subject, so every message about one entity is in order. Messages under different keys have no order
relative to each other.

This is why the key matters when publishing. A consumer publishing about a cart publishes under the cart's
id by default; one that sets its own `ce-subject`, or names a key for a message, chooses the ordering unit
by doing so. The several messages of one change are handed to the broker in the order the handler returned
them, so those that share a key keep that order.

## Delivery and offsets

Reading a topic is at least once. Offsets are committed to Kafka after the handler has returned, so a
restart or a failure redelivers what was not yet committed, and a topic-sourced component must tolerate
seeing a message twice.

Partitions are assigned by Kafka consumer groups, one group per component, named as
[Consumer groups](#consumer-groups) says. Two components reading one topic each see every message; the
instances of one service share each component's partitions between them, and Kafka rebalances them as
instances come and go with no configuration in ankka.

A topic is not a journal. A component reading a topic sees what the broker still holds from where it
starts, and a rebuild reaches back no further, because a broker's retention is not a complete record. A
view that must be whole belongs over an entity's events.

## Testing without a broker

`InMemoryBroker` is a publisher and a subscriber wired to each other. It exercises the whole topic path —
headers, subject keying, decoding, view writes and consumer dispatch — and substitutes only the network:

```scala
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}

val broker  = InMemoryBroker()
val testKit = AnkkaTestKit.start(Seq(StockLevels.descriptor), Seq(ProjectionRuntime.withBroker(broker, broker)))

broker.publish("stock-events", """{"productId":"p1","delta":5}""".getBytes("UTF-8"), Metadata.empty.withSubject("p1"))
```

The in-memory broker keeps everything published to a topic, as one partition that drops nothing, and a
position for each group, so a group starts where it declares and resumes after a restart.
`broker.setClock(...)` decides the time each publication is recorded at, for a start position that is a
time, and `broker.positions(topic)` says how far each group has read.

`broker.publishedTo(topic)` returns what components published, for assertions; each entry's
`message.key` is the record key a broker would have been given. `broker.failNext(topic)` makes the next
publication to a topic fail, to test what a consumer does when the broker refuses one of its messages.
`InMemoryPublisher` is the publishing half alone, for a service that only publishes; its entries have
`recordKey`.

Other brokers plug in through the same two interfaces, `MessagePublisher` and `MessageSubscriber`, in
`com.thinkmorestupidless.ankka.runtime`; pass implementations to `ProjectionRuntime.withBroker`. A
subscriber implements `subscribe(subscription, handle)`, where the subscription names the topic, the group
and the start position, and `earliestRetained(topic)`, which says when the oldest message on each partition
was published. A
publisher implements `publish(topic, key, payload, metadata)` to publish a message under a key that is
not its subject. One that implements only `publish(topic, payload, metadata)` keys every message by its
subject, and a message that names a key fails rather than be published under the wrong one.
