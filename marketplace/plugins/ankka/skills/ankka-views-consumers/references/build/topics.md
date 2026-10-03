# Broker topics

> Read views and consumers from a Kafka topic and publish to one, with CloudEvents attributes as headers, ordering by record key, which is the subject unless a message names one, and a broker-free in-memory pair for tests.

Source: https://docs.ankka.cloud/build/topics/
A view or a consumer can read from a broker topic instead of an entity, and a consumer can publish to one.
Topics are how an ankka service exchanges messages with systems outside it, including other ankka
services and services written with no ankka at all. Kafka is the broker ankka ships with.

## Reading from a topic

Declare a topic as the source, with the serializer that decodes its messages:

**Scala**

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

**Python**

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

**TypeScript**

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

A consumer reads a topic the same way: `ChangeSource.fromTopic(...)` in Scala, `topic = "..."` in Python.

The view's row is keyed by the message's CloudEvents subject, the `ce-subject` header, falling back to the
Kafka record key when the header is absent. A message with neither is skipped by a view rather than
retried, because there is no row it could belong to.

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
platform names the broker in the environment instead. `ProjectionRuntime.fromEnv()` connects to the broker
named by `ANKKA_KAFKA_BOOTSTRAP_SERVERS` and runs entity sources only when the variable is absent:

```scala
Ankka.service
  .register(StockLevels.descriptor)
  .withExtension(ProjectionRuntime.fromEnv())
  .start()
```

Set the variable in the service descriptor's `env`. The platform provides no broker of its own, so its value
is the address of a Kafka the cluster can reach.

A service behind a sidecar reaches the broker through its sidecar, which connects to the one named by
`ANKKA_KAFKA_BOOTSTRAP_SERVERS`. Set it in the service descriptor's `env`: the platform gives it to the
sidecar and to the process as well, so a service can register a component that publishes only where
there is a broker to publish to. Without it the sidecar refuses to start a service that has such a
component, naming it. A service hosted as a WebAssembly module reads the same variable through its
configuration.

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

Partitions are assigned by Kafka consumer groups, one group per component. Two components reading one topic
each see every message; the instances of one service share each component's partitions between them, and
Kafka rebalances them as instances come and go with no configuration in ankka.

A topic is not a journal. A component reading a topic sees only what is published after it starts, and it
cannot rebuild its state from history, because a broker's retention is not a complete record. A view that
must be rebuildable belongs over an entity's events.

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

`broker.publishedTo(topic)` returns what components published, for assertions; each entry's
`message.key` is the record key a broker would have been given. `broker.failNext(topic)` makes the next
publication to a topic fail, to test what a consumer does when the broker refuses one of its messages.
`InMemoryPublisher` is the publishing half alone, for a service that only publishes; its entries have
`recordKey`.

Other brokers plug in through the same two interfaces, `MessagePublisher` and `MessageSubscriber`, in
`com.thinkmorestupidless.ankka.runtime`; pass implementations to `ProjectionRuntime.withBroker`. A
publisher implements `publish(topic, key, payload, metadata)` to publish a message under a key that is
not its subject. One that implements only `publish(topic, payload, metadata)` keys every message by its
subject, and a message that names a key fails rather than be published under the wrong one.
