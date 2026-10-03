# Consumers

> React to every change from an entity or a topic, call other components or publish one or several messages onward to a topic, and make the reaction safe to repeat under at-least-once delivery.

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

| Source | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|
| An event sourced entity's events | `ChangeSource.eventsOf(ShoppingCartEntity)` | `source = ShoppingCartEntity` | `static readonly source = ShoppingCartEntity` | `Source::of(ShoppingCart)` |
| A key value entity's state | `ChangeSource.stateOf(CheckoutLog)` | `source = CheckoutLog` | `static readonly source = CheckoutLog` | `Source::of(CheckoutLog)` |
| A broker topic | `ChangeSource.fromTopic("stock-events", serializer)` | `topic = "stock-events"` | `static readonly topic = "stock-events"` | `Source::topic("stock-events")` |

From a key value entity a consumer sees the latest value, and intermediate values can be skipped. Use an
event sourced source for anything that must react to every change.

Every change arrives with the source entity's id and a sequence number: an event's sequence number, or a
key value entity's revision. A topic's message has no sequence number, and reads as zero.

| | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|
| The source entity's id | `messageContext.subject` | `self.metadata.subject` | `this.subject` | `ctx.entity_id()` |
| The sequence number | `messageContext.sequenceNumber` | `self.metadata.sequence_number` | `this.sequenceNumber` | `ctx.sequence()` |

## Effects

A consumer's handler returns one of four effects. All four advance the consumer past the change.

| Scala | Python | TypeScript | Rust | Meaning |
|---|---|---|---|---|
| `effects.produce(message)` | `self.effects.produce(message)` | `this.effects.produce(message)` | `consumer::produce(message)` | Publish `message` to the consumer's topic. |
| `effects.produceAll(messages)` | `self.effects.produce_all(messages)` | `this.effects.produceAll(messages)` | `consumer::produce_all(messages)` | Publish several messages, in order, each under its own record key if it names one. |
| `effects.done()` | `self.effects.done()` | `this.effects.done()` | `consumer::done()` | Handled; nothing to publish. |
| `effects.ignore()` | `self.effects.ignore()` | `this.effects.ignore()` | `consumer::ignore()` | Not interesting to this consumer. |

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
    case ItemAdded(_) | ItemRemoved(_) | Discarded =>
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
class attributes `produces_to` and `out_codec`, and in TypeScript the statics `producesTo` and `out`; a
class with one and not the other is refused at registration. In Rust `produces_to()` names the topic and
the message is encoded as any value of its type is.

Each published message carries the source entity's id as its CloudEvents subject, which is also the
message's record key on Kafka unless the message names a key of its own. Every message about one entity
therefore lands on one partition, in order. To set other CloudEvents attributes, pass metadata:
`effects.produce(message, metadata)`. See [Broker topics](topics.md).

A service with a publishing consumer needs somewhere to publish to. In Scala that is
`ProjectionRuntime.withKafka(bootstrapServers)`, or `ProjectionRuntime.withPublisher(publisher)` with a
publisher of your own. A Python, TypeScript or Rust service needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in its
descriptor's `env`, and is refused at startup without it, naming the consumer.

## Publishing several messages for one change

A handler that returns several messages has each one published to the consumer's topic, in the order
returned. Use it when one change matters to more than one thing: a message per line item, or the several
elements of a graph. Each message may name its own **record key**; one that names none is keyed by its
subject, the source entity's id, as a single message is.

This consumer answers a checkout with three messages, the second under a key of its own and the third
with a header; it answers an item added with no messages at all, and an item removed with a single one:

**Scala**

```scala
/** What `checkout-fanout` publishes: the n-th message of a change. */
final case class Fanned(n: Int)

/**
 * Several messages for one change: three for a checkout — the second under a key of its own, the
 * third with a header — none for an item added, and a single one, the old way, for an item
 * removed.
 */
final class CheckoutFanout extends Consumer[ShoppingCartEvent, Fanned]:
  def onMessage(event: ShoppingCartEvent): Effect = event match
    case _: ItemAdded   => effects.produceAll(Nil)
    case _: ItemRemoved => effects.produce(Fanned(0))
    case CheckedOut =>
      effects.produceAll(
        Seq(
          effects.message(Fanned(1)),
          effects.message(Fanned(2)).withKey(s"second:${messageContext.subject}"),
          effects.message(Fanned(3)).withMetadata(Metadata.empty.set("x-n", "3"))
        )
      )
    case Discarded => effects.ignore()

object CheckoutFanout
    extends Consumer.Companion[CheckoutFanout, ShoppingCartEvent, Fanned](
      componentId = ComponentId("checkout-fanout"),
      source = ChangeSource.eventsOf(ShoppingCartEntity)
    ):
  def create(ctx: ConsumerContext) = new CheckoutFanout

  override val outputSerializer: Option[Serializer[Fanned]] =
    Some(Codecs.serializer[Fanned]("fanned"))

  override val produceTo: Option[String] = Some("conformance-fanout")
```

**Python**

```python
@dataclass(frozen=True)
class Fanned:
    n: int


class CheckoutFanout(Consumer[ShoppingCartEvent, Fanned]):
    """Three messages for a checkout — the second under a key of its own, the third with a header —
    none for an item added, and a single one, the old way, for an item removed."""

    component_id = "checkout-fanout"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec
    produces_to = "conformance-fanout"
    out_codec = json_codec(Fanned, "fanned")

    def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:
        match event:
            case ItemAdded():
                return self.effects.produce_all([])
            case ItemRemoved():
                return self.effects.produce(Fanned(0))
            case CheckedOut():
                return self.effects.produce_all(
                    [
                        self.effects.message(Fanned(1)),
                        self.effects.message(Fanned(2), key=f"second:{self.metadata.subject}"),
                        self.effects.message(Fanned(3), metadata=Metadata().set("x-n", "3")),
                    ]
                )
        return self.effects.ignore()
```

**TypeScript**

```ts
export const Fanned = s.record("Fanned", { n: s.int })

/**
 * Three messages for a checkout — the second under a key of its own, the third with a header — none
 * for an item added, and a single one, the old way, for an item removed.
 */
export class CheckoutFanout extends Consumer<ShoppingCartEvent, Infer<typeof Fanned>> {
  static readonly componentId = "checkout-fanout"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly out = jsonCodec(Fanned, "fanned")
  static readonly producesTo = "conformance-fanout"

  onMessage(event: ShoppingCartEvent) {
    switch (event.type) {
      case "ItemAdded":
        return this.effects.produceAll([])
      case "ItemRemoved":
        return this.effects.produce({ n: 0 })
      case "CheckedOut":
        return this.effects.produceAll([{ payload: { n: 1 } }, { payload: { n: 2 }, key: `second:${this.subject}` }, { payload: { n: 3 }, metadata: { "x-n": "3" } }])
      default:
        return this.effects.ignore()
    }
  }
}
```

**Rust**

```rust
/// What `checkout-fanout` publishes: the n-th message of a change.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Fanned {
    pub n: i32,
}

/// One fanned message, encoded under the manifest every reference gives it.
fn fanned(n: i32) -> Outgoing {
    Outgoing::of(Auto::<Fanned>::named("fanned").to_payload(&Fanned { n }))
}

/// Three messages for a checkout — the second under a key of its own, the third with a header —
/// none for an item added, and a single one, the old way, for an item removed.
pub struct CheckoutFanout;

impl Consumer for CheckoutFanout {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "checkout-fanout";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn produces_to() -> Option<&'static str> {
        Some("conformance-fanout")
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> ConsumerEffect {
        match event {
            ShoppingCartEvent::ItemAdded { .. } => consumer::produce_all([]),
            ShoppingCartEvent::ItemRemoved { .. } => {
                let (payload, _, metadata) = fanned(0).into_parts();
                ConsumerEffect::Produce(payload, metadata)
            }
            ShoppingCartEvent::CheckedOut => consumer::produce_all([
                fanned(1),
                fanned(2).key(format!("second:{}", ctx.entity_id())),
                fanned(3).metadata(Metadata::new().set("x-n", "3")),
            ]),
            ShoppingCartEvent::Discarded => consumer::ignore(),
        }
    }
}
```

Each is an excerpt of the reference service the SDKs are tested against. The Rust one builds its messages
with `Outgoing::of` because it names the payload's manifest itself; `consumer::message(value)` builds one
under the manifest of the value's type, and `.key(…)` and `.metadata(…)` set the rest.

The rules are the same in every language:

- **The record key and the subject are separate.** Naming a key changes which messages are ordered
  together and which record a compacted topic keeps. It does not change `ce-subject`, which stays the
  source entity's id unless the message's metadata sets it. An empty key is refused.
- **The change counts as handled when the broker has accepted every message.** If one is refused, the
  change is delivered again and all of its messages are published again. The ones already accepted are
  not withdrawn, so a reader may see them twice and never sees one missing.
- **A failure redelivers more than one change.** The runtime saves a consumer's progress through an
  entity's changes in batches, so a consumer that fails is handed every change since its progress was
  last saved, not only the one that failed. A topic's offset is committed after each message is handled.
- **An empty list publishes nothing** and the change is handled at once, as with `done`.
- **The messages of one change may be at most 4 MiB together.** A larger result fails the change, with an
  error naming the consumer, and none of it is published.
- **A consumer has one topic and one message type.** All of a change's messages go to that topic.

A [graph consumer](graph.md) is built on this: each element of a graph is one message under the key of its
element.

## Calling other components

A consumer can act on other components through the component client. Each of these records every
checkout in the `CheckoutLog` key value entity:

**Scala**

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

**Python**

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

**TypeScript**

```ts
export class CheckoutNotifier extends Consumer<ShoppingCartEvent> {
  static readonly componentId = "checkout-notifier"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events

  async onMessage(event: ShoppingCartEvent) {
    if (event.type !== "CheckedOut") return this.effects.ignore()
    await this.client.of(CheckoutLog, this.subject).call(CheckoutLog.handlers.record).invoke(BigInt(Date.now()))
    return this.effects.done()
  }
}
```

**Rust**

```rust
pub struct CheckoutNotifier;

impl Consumer for CheckoutNotifier {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "checkout-notifier";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> ConsumerEffect {
        let ShoppingCartEvent::CheckedOut = event else {
            return consumer::ignore();
        };
        let cart_id = ctx.metadata().subject().unwrap_or_default();
        let at = ctx.now().epoch_millis();
        // A panic sends the message again, which is what a consumer that could not act wants.
        let recorded: Result<Done, CommandError> =
            ctx.client().invoke(CheckoutLog, cart_id, "record", at);
        recorded.expect("the checkout log answers");
        consumer::done()
    }
}
```

The client reaches the consumer differently in each. In Scala it arrives in the context the companion's
`create` receives; in Python it is `self.client`, in TypeScript `this.client` and in Rust `ctx.client()`.
Scala consumers run on virtual threads, so a blocking `invoke` inside `onMessage` costs nothing but the
wait. A Rust consumer that cannot act panics, which has the change delivered again.

A consumer that only reacts, and publishes nothing, has `Nothing` as its output type in Scala; Python,
TypeScript and Rust simply declare no output.

## Making a consumer safe to repeat

Because a change can arrive more than once, design the reaction so a repeat does no harm. Three ways work
well:

- **Make the target idempotent.** Setting a value is safe to repeat; adding to one is not. The notifier
  above writes the checkout time into the log, and writing it twice leaves the same record.
- **Key the effect by the change.** When the target is an entity, use an id derived from the source's
  id, so a repeated delivery addresses the same instance and the entity can refuse what it has already
  done.
- **Let the receiver deduplicate.** Put what identifies the change into the published message: the
  source's id and the change's sequence number. A receiver can then drop a message it has already
  processed. Do not rely on the CloudEvents `ce-id` for this: it is generated each time a message is
  published, so a redelivered change is published under a new one.

Only the developer knows whether a repeat is harmless, which is why deduplication is not done for you.

A [graph consumer](graph.md) is the exception: it is safe to repeat as it stands. Each element it
publishes is a whole state at the version of the change, a change handled twice publishes equal deltas,
and the reader passes over a delta that is not newer than what it holds.

## When the source is deleted

When a source entity is deleted, the consumer's deletion handler runs: `onDelete` in Scala and
TypeScript, `on_delete` in Python and `on_deleted` in Rust. By default it ignores the deletion. Override
it to react, for example to clean up something keyed by the deleted entity's id. The handler may publish,
one message or several, as `onMessage` may.

A deletion is a change like any other, of an event sourced entity and of a key value entity alike. It
arrives after every earlier change to the entity, with a sequence number above theirs, and an entity
created again under the same id goes on from it. An entity whose state has expired is not deleted, and
no deletion is delivered for it.

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
service = (
    Ankka.service()
    .register(ShoppingCartEntity)
    .register(CheckoutLog)
    .register(CheckoutNotifier)
)
```

**TypeScript**

```ts
const service = Ankka.service()
  .register(ShoppingCartEntity)
  .register(CheckoutLog)
  .register(CheckoutNotifier)
```

**Rust**

```rust
pub fn build() -> Service {
    Service::new("cart")
        .register(ShoppingCart)
        .register(CheckoutLog)
        .register(CheckoutNotifier)
}
```

A consumer's work is spread across the service's instances, and each change is handled on one of them.
`parallelism` on the Scala companion, 4 by default, sets how many slices of the source's changes are read
at once.

## Testing

A consumer is tested with nothing started: its unit test kit hands it a change with a subject and a
sequence number and gives back what it would publish.

| | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|
| The kit | `ConsumerTestKit.of(Companion)` | `ConsumerTestKit.of(Cls)` | `ConsumerTestKit.of(Cls)` | `ConsumerTestKit::<C>::new()` |
| A change | `onMessage(message, subject, sequenceNumber)` | `on_message(message, subject, sequence=…)` | `onMessage(message, subject, metadata)` | `.at(sequence).on_message(subject, message)` |
| What it published | the result's `messages`: `payload`, `key`, `metadata` | `kit.messages`: `payload`, `key`, `metadata` | `kit.produced`: `payload`, `key`, `metadata` | `ConsumerTestKit::<C>::messages(&effect)` |

```scala
test("a checkout is answered with three messages, the second under a key of its own") {
  val kit    = ConsumerTestKit.of(CheckoutFanout)
  val result = kit.onMessage(CheckedOut, subject = "c1", sequenceNumber = 4)

  assertEquals(result.payloads, Vector(Fanned(1), Fanned(2), Fanned(3)))
  // The key each message named; the others are keyed by their subject, the cart's id.
  assertEquals(result.keys, Vector(None, Some("second:c1"), None))
  assertEquals(result.recordKeys, Vector(Some("c1"), Some("second:c1"), Some("c1")))
  assertEquals(result.messages(2).metadata.get("x-n"), Some("3"))
}
```

The Scala kit applies the handler's effect with the function the runtime applies it with, so a result the
runtime would refuse — no topic to publish to, an empty key — fails in the test. A consumer that calls
other components is given a client that answers: a `TestTransport` with stubs in Scala, a client double
in Python and TypeScript, and `with_service(build())` in Rust. Without one the call is refused, as a
failed call is.

What a consumer does in a running service — delivery, redelivery, what reaches a broker — is tested with
the integration test kit. In Scala, `InMemoryPublisher` captures what a consumer published, with no
broker, and `InMemoryBroker` also delivers it to the service's own topic sources:

```scala
val publisher = InMemoryPublisher()
val testKit = AnkkaTestKit.start(
  Seq(ShoppingCartEntity.descriptor, CheckoutNotifier.descriptor),
  Seq(ProjectionRuntime.withPublisher(publisher))
)
// ... check out a cart, then poll until the notice arrives:
publisher.publishedTo("cart-checkouts").map(_.recordKey)
```

See [Testing](testing.md#testing-a-consumer).
