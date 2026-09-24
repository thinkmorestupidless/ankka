# Consumers

> React to every change from an entity or a topic, call other components or publish onward to a topic, and make the reaction safe to repeat under at-least-once delivery.

Source: https://docs.ankka.cloud/build/consumers/
A consumer runs code for each change from one source. Where a [view](views.md) turns changes into rows to
query, a consumer turns them into actions: calling another component, or publishing a message to a topic
for something outside the service. The runtime reads the source's changes in order and hands each one to
the consumer, then records how far it has read.

Delivery is at least once. The runtime records progress only after the handler returns, so a crash, a
restart or a handler that throws means the same change is delivered again. Whatever a consumer does must be
safe to do twice.

## Sources

A consumer reads one source, declared the same way as a view's:

| Source | Scala | Python |
|---|---|---|
| An event sourced entity's events | `ChangeSource.eventsOf(ShoppingCartEntity)` | `source = ShoppingCartEntity` |
| A key value entity's state | `ChangeSource.stateOf(PreferencesEntity)` | `source = CheckoutLog` |
| A broker topic | `ChangeSource.fromTopic("stock-events", serializer)` | `topic = "stock-events"` |

From a key value entity a consumer sees the latest value, and intermediate values can be skipped. Use an
event sourced source for anything that must react to every change.

## Effects

A consumer's handler returns one of three effects. All three advance the consumer past the change.

| Scala | Python | Meaning |
|---|---|---|
| `effects.produce(message)` | `self.effects.produce(message)` | Publish `message` to the consumer's topic. |
| `effects.done()` | `self.effects.done()` | Handled; nothing to publish. |
| `effects.ignore()` | `self.effects.ignore()` | Not interesting to this consumer. |

Failure is not an effect. A handler that throws or raises does not advance, and the change is delivered
again. That is the mechanism for retrying a call that failed, and it is also why a handler that fails the
same way every time stops its consumer at that change until the code or the data is fixed.

## Publishing onward to a topic

A consumer that publishes declares an output type, a serializer for it and a topic. The shopping cart's
notifier decides which of the cart's internal events are worth telling the outside world about, and in what
shape, so the cart's own event type stays free to change without breaking anyone downstream:

```scala
package shoppingcart.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

/** What leaves the service when a cart is checked out. */
final case class CheckoutNotice(cartId: String, at: Long)

/**
 * Turns an internal event into a published one.
 *
 * The cart's own event type is an implementation detail; this consumer decides which events are
 * worth telling the outside world about and in what shape — so the domain stays free to change
 * without breaking downstream consumers.
 */
final class CheckoutNotifier extends Consumer[ShoppingCartEvent, CheckoutNotice]:

  def onMessage(event: ShoppingCartEvent): Effect = event match
    case CheckedOut =>
      effects.produce(CheckoutNotice(messageContext.subject, System.currentTimeMillis()))
    case ItemAdded(_) | ItemRemoved(_) =>
      effects.ignore()

object CheckoutNotifier
    extends Consumer.Companion[CheckoutNotifier, ShoppingCartEvent, CheckoutNotice](
      componentId = ComponentId("checkout-notifier"),
      source = ChangeSource.eventsOf(ShoppingCartEntity)
    ):
  def create(ctx: ConsumerContext) = new CheckoutNotifier

  override val outputSerializer: Option[Serializer[CheckoutNotice]] =
    Some(Codecs.serializer[CheckoutNotice]("checkout-notice"))

  override val produceTo: Option[String] = Some("cart-checkouts")
```

In Scala, `produceTo` names the topic and `outputSerializer` encodes the message; a companion with a
`produceTo` and no `outputSerializer` is refused when its descriptor is built. In Python the same are the
class attributes `produces_to` and `out_codec`, and a class with one and not the other is refused at
registration.

Each published message carries the source entity's id as its CloudEvents subject, which is also the
message's key on Kafka. Every message about one entity therefore lands on one partition, in order. To set
other CloudEvents attributes, pass metadata: `effects.produce(message, metadata)`. See
[Broker topics](topics.md).

A service with a publishing consumer needs somewhere to publish to. In Scala that is
`ProjectionRuntime.withKafka(bootstrapServers)`, or `ProjectionRuntime.withPublisher(publisher)` with a
publisher of your own. A Python service's sidecar needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, and refuses to start
without it, naming the consumer.

## Calling other components

A consumer can act on other components through the component client. The Python sample records each
checkout in a key value entity:

```python
"""Turns an internal event into an action elsewhere: the cart's own events are an implementation
detail, and this consumer decides which are worth acting on. Here it records the checkout in
the checkout log through the client — a consumer that publishes to a topic instead declares
``produces_to`` and an ``out_codec``, and the sidecar needs a broker (``ANKKA_KAFKA_BOOTSTRAP_SERVERS``)."""

from __future__ import annotations

import time

from ankka import Done
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect

from examples.shopping_cart.domain import CheckedOut, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


class CheckoutNotifier(Consumer[ShoppingCartEvent, None]):
    component_id = "checkout-notifier"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec

    async def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:  # type: ignore[override]
        if not isinstance(event, CheckedOut):
            return self.effects.ignore()
        cart_id = self.metadata.subject or ""
        assert self.client is not None
        await self.client.for_key_value_entity("checkout-log", cart_id).call("record").invoke(int(time.time() * 1000), reply=Done)
        return self.effects.done()
```

In Scala the client arrives in the context the companion's `create` receives. Consumers run on virtual
threads, so a blocking `invoke` inside `onMessage` is cheap:

```scala
import com.thinkmorestupidless.ankka.core.{ComponentId, Done, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

final class CheckoutRecorder(client: ComponentClient) extends Consumer[ShoppingCartEvent, Nothing]:

  def onMessage(event: ShoppingCartEvent): Effect = event match
    case CheckedOut =>
      client
        .forKeyValueEntity(EntityId(messageContext.subject))
        .call(CheckoutLog.record)
        .invoke(System.currentTimeMillis()): Unit
      effects.done()
    case _ => effects.ignore()

object CheckoutRecorder
    extends Consumer.Companion[CheckoutRecorder, ShoppingCartEvent, Nothing](
      componentId = ComponentId("checkout-recorder"),
      source = ChangeSource.eventsOf(ShoppingCartEntity)
    ):
  def create(ctx: ConsumerContext) = new CheckoutRecorder(ctx.componentClient)
```

`CheckoutLog` here stands for any key value entity with a `record` command taking a `Long`. A consumer that
only reacts, and publishes nothing, has `Nothing` as its output type in Scala and `None` in Python.

The source entity's id is `messageContext.subject` in Scala and `self.metadata.subject` in Python.

## Making a consumer safe to repeat

Because a change can arrive more than once, design the reaction so a repeat does no harm. Three ways work
well:

- **Make the target idempotent.** Setting a value is safe to repeat; adding to one is not. The notifier
  above writes the checkout time into the log, and writing it twice leaves the same record.
- **Key the effect by the change.** When the target is an entity, use an id derived from the source's
  id, so a repeated delivery addresses the same instance and the entity can refuse what it has already
  done.
- **Let the receiver deduplicate.** Put what identifies the change into the published message: the
  source's id and, in Scala, `messageContext.sequenceNumber`. A receiver can then drop a message it has
  already processed. Do not rely on the CloudEvents `ce-id` for this: it is generated each time a message
  is published, so a redelivered change is published under a new one.

Only the developer knows whether a repeat is harmless, which is why deduplication is not done for you.

## When the source is deleted

When a source entity is deleted, the consumer's deletion handler runs: `onDelete` in Scala and
`on_delete` in Python. By default it ignores the deletion. Override it to react, for example to clean up
something keyed by the deleted entity's id.

## Registering a consumer

Like a view, a consumer runs only in a service with the projection runtime:

**Scala**

```scala
val service = Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CheckoutNotifier.descriptor)
  .withExtension(ProjectionRuntime.withKafka("localhost:9092"))
  .start()
```

**Python**

```python
service = Ankka.service().register(ShoppingCartEntity).register(CheckoutLog).register(CheckoutNotifier)
```

A consumer's work is spread across the service's instances, and each change is handled on one of them.
`parallelism` on the Scala companion, 4 by default, sets how many slices of the source's changes are read
at once.

## Testing

Python's `ConsumerTestKit` hands a consumer a message with no sidecar and collects what it produced:

```python
kit = ConsumerTestKit.of(CheckoutNotifier)
effect = kit.on_message(ItemAdded(LineItem("p1", "Pen", 2)), subject="c1")
assert effect.__class__.__name__ == "Ignore"
```

A consumer that calls other components needs them running, so it is tested with the integration testkit.
In Scala, `InMemoryPublisher` captures what a consumer published, with no broker:

```scala
val publisher = InMemoryPublisher()
val testKit = AnkkaTestKit.start(
  Seq(ShoppingCartEntity.descriptor, CheckoutNotifier.descriptor),
  Seq(ProjectionRuntime.withPublisher(publisher))
)
// ... check out a cart, then poll until the notice arrives:
publisher.publishedTo("cart-checkouts")
```

See [Testing](testing.md).
