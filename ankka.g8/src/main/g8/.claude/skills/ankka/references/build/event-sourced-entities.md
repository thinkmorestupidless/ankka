# Event sourced entities

> Model state as a sequence of persisted events, write commands and queries that return effects, delete or expire an entity, and tune snapshots, in Scala or Python.

Source: https://docs.ankka.cloud/build/event-sourced-entities/
An event sourced entity is a piece of state, addressed by an id, whose current value is derived by
replaying the events it has persisted. A command handler never changes the state directly. It decides
which events to persist, the runtime journals them, and one function folds each event into the state.
The journal is the record of everything that happened to the entity, and the state is a cache of it that
can always be rebuilt.

The runtime hosts each entity id in exactly one place in the service's cluster and hands it one command
at a time. That is why handlers are ordinary sequential code with no locks: two commands for the same
id never run at once, and two different ids never share state.

Choose an event sourced entity when the history matters: when something downstream needs to react to
each change, when a view needs to project it, or when an audit trail has value. When only the latest
value matters, a [key value entity](key-value-entities.md) is simpler.

## The parts of an entity

An entity has four parts, and each has one job:

| Part | Job |
|---|---|
| state type | What the entity knows now. Plain data with no ankka types. |
| event type | Everything that can happen to the entity. A closed set: a Scala `enum` or a union of Python dataclasses. |
| event handler | `applyEvent` in Scala, `apply_event` in Python. Folds one event into the state, and is the only place state changes. |
| command handlers | Decide, given the current state and a request, what should happen, and return it as an effect. |

The events of the shopping cart sample are a closed set of three cases:

**Scala**

```scala
/** Everything that can happen to a cart. */
enum ShoppingCartEvent:
  case ItemAdded(item: LineItem)
  case ItemRemoved(productId: String)
  case CheckedOut
```

**Python**

```python
@dataclass(frozen=True)
class ItemAdded:
    item: LineItem


@dataclass(frozen=True)
class ItemRemoved:
    productId: str


@dataclass(frozen=True)
class CheckedOut:
    pass


ShoppingCartEvent = ItemAdded | ItemRemoved | CheckedOut
```

## Writing the entity

The entity class holds the empty state, the event handler and the command handlers. Every handler reads
the current state and returns an effect: a description of what should happen, which the runtime carries
out after the handler has returned.

**Scala**

```scala
/**
 * The application layer: connects the cart domain to the ankka runtime.
 *
 * Every handler is ordinary sequential code returning a description of what should happen. Nothing
 * here knows about sharding, Postgres, replay or JSON.
 */
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  private val cartId: String = context.entityId

  def emptyState: ShoppingCart = ShoppingCart.empty(cartId)

  /** The only place state changes, and the only thing replay needs to be correct. */
  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if item.quantity <= 0 then
      effects.error(s"quantity must be greater than zero, was \${item.quantity}")
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)

  def removeItem(productId: String): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if !currentState.contains(productId) then
      effects.error(s"cart does not contain '\$productId'", ErrorCode.NotFound)
    else effects.persist(ItemRemoved(productId)).thenReply(_ => Done)

  /**
   * Records the checkout and then deletes the cart.
   *
   * The event is persisted before the deletion takes effect, so a consumer or view downstream still
   * observes that this cart was checked out rather than merely vanishing.
   */
  def checkout: Effect[ShoppingCart] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if currentState.isEmpty then effects.error("cannot check out an empty cart")
    else effects.persist(CheckedOut).deleteEntity().thenReplyState

  def getCart: ReadOnlyEffect[ShoppingCart] = effects.reply(currentState)

  def totalQuantity: ReadOnlyEffect[Int] = effects.reply(currentState.totalQuantity)
```

**Python**

```python
from __future__ import annotations

from ankka import DONE, Done, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query

from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, LineItem, ShoppingCart, ShoppingCartEvent


class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")
    snapshot_every = 100

    def empty_state(self) -> ShoppingCart:
        return ShoppingCart.empty(self.entity_id)

    def apply_event(self, state: ShoppingCart, event: ShoppingCartEvent) -> ShoppingCart:
        match event:
            case ItemAdded(item):
                return state.add_item(item)
            case ItemRemoved(product_id):
                return state.remove_item(product_id)
            case CheckedOut():
                return state.on_checked_out()
        raise AssertionError(event)

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if item.quantity <= 0:
            return self.effects.error(f"quantity must be greater than zero, was {item.quantity}")
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)

    @command("remove-item")
    def remove_item(self, product_id: str) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if not self.state.contains(product_id):
            return self.effects.error(f"cart does not contain '{product_id}'", ErrorCode.NOT_FOUND)
        return self.effects.persist(ItemRemoved(product_id)).then_reply(lambda _: DONE)

    @command("checkout")
    def checkout(self) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if self.state.is_empty:
            return self.effects.error("cannot check out an empty cart")
        # As the Scala cart: the event is persisted, then the cart is deleted, so a consumer
        # downstream still sees the checkout rather than a cart that vanished.
        return self.effects.persist(CheckedOut()).delete_entity().then_reply_state()

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)

    @query("total-quantity")
    def total_quantity(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, int]:
        return self.effects.reply(self.state.total_quantity)
```

In Scala, `currentState` is the state as of now: after replay, and after any events this command has
already persisted. In Python the same value is `self.state`, and `apply_event` receives the state to
fold into as an argument. The entity's id is `context.entityId` in Scala, from the context the runtime
passes to the constructor, and `self.entity_id` in Python.

The event handler must be pure. It runs when an event is first persisted and again every time the entity
is recovered from the journal, so reading a clock, generating a random value or calling anything outside
the entity would make a recovered entity diverge from the one that wrote the journal. Put anything
non-deterministic into the event when the command handler creates it.

## Declaring handlers and their wire names

In Scala, handlers are declared on the entity's companion object. The companion names the component,
supplies the state and event serializers, constructs the entity, and registers every handler under a
wire name:

```scala
object ShoppingCartEntity
    extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
      componentId = ComponentId("shopping-cart"),
      stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
      eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
    ):

  /**
   * `LineItem` crosses the wire as a command argument, so it needs a manifest of its own. Declared
   * before the handlers below because object initialisation runs in order.
   */
  given Serializer[LineItem] = Codecs.serializer[LineItem]("line-item")

  def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)

  val addItem       = command("add-item")(_.addItem)
  val removeItem    = command("remove-item")(_.removeItem)
  val checkout      = command("checkout")(_.checkout)
  val getCart       = query("get-cart")(_.getCart)
  val totalQuantity = query("total-quantity")(_.totalQuantity)
```

In Python the same declaration is made with class attributes and decorators: `component_id`,
`state_codec`, `event_codec`, and `@command("add-item")` or `@query("get-cart")` on each handler.

The string passed to `command` or `query` is the handler's wire name. It is the name the platform
routes by, stores in timers and shows in traces, and it is deliberately separate from the method name:
renaming `addItem` changes nothing on the wire, and renaming `"add-item"` is a protocol change. See
[Handlers and wire names](../concepts/wire-names.md) for why.

`command` and `query` differ in what they accept. A `query` handler must return a read-only effect,
which provably persists nothing, so a handler that tries to persist cannot be registered as a query.
Python enforces the same rule at registration, and the sidecar refuses events from a read-only handler
regardless.

A handler takes zero or one argument. The argument and the reply each need a serializer: in Scala the
state and event serializers are in scope inside the companion, primitives come from
`com.thinkmorestupidless.ankka.core.Serializers.given`, and any other type needs a `given`, declared
before the handlers that use it, as the sample does for `LineItem`. See
[Serialization and evolution](serialization.md).

## Effects

A command handler returns one of these effects. In Python the names are the same in snake case.

| Effect | Meaning |
|---|---|
| `effects.persist(e1, e2, ...)` / `persistAll(events)` | Persist events, then choose a reply with one of the next three. |
| `.thenReply(state => value)` | Reply with a value computed from the state *after* the events are applied. |
| `.thenReplyState` | Reply with the whole state after the events are applied. |
| `.thenNoReply` | Persist and reply with nothing. |
| `effects.reply(value)` | Reply without persisting. A read-only effect, and the only thing a query may return besides an error. |
| `effects.error(message, code)` | Refuse the command. Nothing is persisted, and the caller receives a typed error. The code defaults to `BadRequest`. |
| `effects.deleteEntity()` / `.deleteEntity()` after `persist` | Mark the entity deleted, with or without a final event. |
| `.expireAfter(duration)` after `persist` | Delete the entity automatically once `duration` passes with no further update. |

All the events of one effect are persisted together, before the reply is sent. A refusal persists nothing,
so a handler can check every rule first and refuse without leaving a partial change behind.

A refusal is a value, not an exception. `effects.error("cart is already checked out", ErrorCode.Conflict)`
reaches an HTTP caller as a `409`, and a caller using the component client as a `CommandError` carrying
the same code. The endpoint in between needs no error handling of its own. See
[Error codes](../reference/error-codes.md) for how each code maps to an HTTP status.

## Deleting an entity

Deleting an entity marks it deleted in the journal. The shopping cart deletes itself on checkout, after
persisting a final event:

```scala
/**
 * Records the checkout and then deletes the cart.
 *
 * The event is persisted before the deletion takes effect, so a consumer or view downstream still
 * observes that this cart was checked out rather than merely vanishing.
 */
def checkout: Effect[ShoppingCart] =
  if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
  else if currentState.isEmpty then effects.error("cannot check out an empty cart")
  else effects.persist(CheckedOut).deleteEntity().thenReplyState
```

Persisting the event before deleting matters to everything downstream. A view or consumer reading the
cart's events sees `CheckedOut` and then the deletion, so it learns that the cart was checked out rather
than that it vanished. A view can keep a row for a deleted entity, as the cart's view does; see
[Views](views.md#when-the-source-is-deleted).

After a deletion the id is free. The next command for it starts from the empty state, as if the id had
never been used, and none of the earlier events are folded in. Choose ids that are not reused by accident
when that matters, for example a generated order id rather than a customer's email address.

`expireAfter` is the same deletion, deferred: the entity is deleted once the duration passes with no
further update to it.

## Snapshots

A snapshot is a stored copy of the state, so recovering an entity replays only the events after it rather
than the whole journal. Snapshots are an optimisation with no effect on behaviour: an entity recovered
from a snapshot has exactly the state it would have had from a full replay.

| | Default | Change it |
|---|---|---|
| Scala | every 100 events | override `snapshotEvery` on the companion: `override def snapshotEvery: Option[Int] = Some(500)`, or `None` to never snapshot |
| Python | never | set the class attribute `snapshot_every = 100`; `0` means never |

Snapshots are stored with the state serializer, so the state type follows the same evolution rules as the
events.

## Registering the entity

Registration is explicit: a component the service does not register does not exist, and a call to it
fails. In Scala, register the companion's descriptor on the service builder. In Python, register the
class.

**Scala**

```scala
import com.thinkmorestupidless.ankka.runtime.Ankka

val service = Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .start()
```

**Python**

```python
from ankka import Ankka

service = Ankka.service().register(ShoppingCartEntity)
```

## Calling the entity

Other components and endpoints call an entity through the component client, addressing it by id. The
call is routed to wherever the entity lives in the cluster.

**Scala**

```scala
import com.thinkmorestupidless.ankka.core.EntityId

val done  = componentClient.forEventSourcedEntity(EntityId("c1")).call(ShoppingCartEntity.addItem).invoke(item)
val cart  = componentClient.forEventSourcedEntity(EntityId("c1")).call(ShoppingCartEntity.getCart).invoke()
```

**Python**

```python
done = await client.for_event_sourced_entity("shopping-cart", "c1").call("add-item").invoke(item, reply=Done)
cart = await client.for_event_sourced_entity("shopping-cart", "c1").call("get-cart").invoke(reply=ShoppingCart)
```

In Scala, `invoke` blocks and `invokeAsync` returns a `Future`. Blocking is cheap because endpoints,
workflow steps, consumers and timers run on virtual threads. An entity handler itself never calls
another component: it returns an effect, and the runtime does the rest. See
[Calling components](component-client.md).

An entity that has never received a command exists with its empty state, so reading it answers the empty
state rather than failing.

## Testing

Because a handler returns an effect instead of performing it, an entity can be tested with no runtime,
cluster or database. The unit testkit runs a handler, applies the events it persisted, and shows the reply,
the events and the new state, in milliseconds. Arguments and replies still round-trip through the
component's serializers, so a missing codec fails in the test rather than on first deployment.

**Scala**

```scala
val kit    = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")
val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))

assertEquals(result.replyValue, Done)
assertEquals(result.events, Vector(ItemAdded(LineItem("p1", "Widget", 2))))
```

**Python**

```python
kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
assert kit.call("add-item", LineItem("p1", "Pen", 2)).events == (ItemAdded(LineItem("p1", "Pen", 2)),)
```

See [Testing](testing.md) for the integration testkit, which runs the whole service against a real
database and can restart it to prove the state is durable.
