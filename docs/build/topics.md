---
title: Broker topics
description: Read views and consumers from a Kafka topic and publish to one, with CloudEvents attributes as headers, per-entity ordering by subject, and a broker-free in-memory pair for tests.
kind: guide
languages: [scala, python]
components: [view, consumer]
related: [build/views.md, build/consumers.md, concepts/consistency.md, reference/configuration.md]
---

# Broker topics

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

A Python service's sidecar connects to the broker named by `ANKKA_KAFKA_BOOTSTRAP_SERVERS`. Set it in the
service descriptor's `env`, where the platform routes it to the sidecar. Without it the sidecar refuses to
start, naming the component that needs a broker.

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
the same name, and any other metadata is sent as additional headers.

## Ordering

`ce-subject` is also the Kafka record key. Kafka keeps order only within a partition and assigns a key to
one partition, so every message about one entity is published to one partition and read in the order it
was written. Messages about different entities have no order relative to each other.

This is why the subject matters when publishing. A consumer publishing about a cart publishes under the
cart's id by default; one that sets its own `ce-subject` chooses the ordering unit by doing so.

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

`broker.publishedTo(topic)` returns what components published, for assertions. `InMemoryPublisher` is the
publishing half alone, for a service that only publishes.

Other brokers plug in through the same two interfaces, `MessagePublisher` and `MessageSubscriber`, in
`com.thinkmorestupidless.ankka.runtime`; pass implementations to `ProjectionRuntime.withBroker`.
