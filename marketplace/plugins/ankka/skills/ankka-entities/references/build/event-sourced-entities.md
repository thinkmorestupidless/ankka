# Event sourced entities

> Model state as a sequence of persisted events, write commands and queries that return effects, delete or expire an entity, and tune snapshots, in Scala, Java, Kotlin, Python, TypeScript or Rust.

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
| event type | Everything that can happen to the entity. A closed set: a Scala `enum`, a union of Python dataclasses, a TypeScript `s.sumType`, or a Go interface whose cases `ankka.Cases` registers. |
| event handler | `applyEvent` in Scala and TypeScript, `apply_event` in Python, the apply function given to `ankka.EventSourced` in Go. Folds one event into the state, and is the only place state changes. |
| command handlers | Decide, given the current state and a request, what should happen, and return it as an effect. |

The events of the shopping cart sample are a closed set of three cases:

**Scala**

```scala
/** Everything that can happen to a cart. */
enum ShoppingCartEvent:
  case ItemAdded(item: LineItem)
  case ItemRemoved(productId: String)
  case CheckedOut
  case Discarded
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


@dataclass(frozen=True)
class Discarded:
    pass


ShoppingCartEvent = ItemAdded | ItemRemoved | CheckedOut | Discarded
```

**TypeScript**

```ts
export const ShoppingCartEvent = s.sumType("ShoppingCartEvent", {
  ItemAdded: { item: LineItem },
  ItemRemoved: { productId: s.string },
  CheckedOut: {},
  Discarded: {},
})
export type ShoppingCartEvent = Infer<typeof ShoppingCartEvent>
```

**Go**

```go
// Event is what a cart persists.
type Event interface{ cartEvent() }

// ItemAdded is an item put in the cart.
type ItemAdded struct {
	Item LineItem `json:"item"`
}

// ItemRemoved is a product taken out of the cart.
type ItemRemoved struct {
	ProductID string `json:"productId"`
}

// CheckedOut is the cart ordered.
type CheckedOut struct{}

// Discarded is the cart thrown away.
type Discarded struct{}

func (ItemAdded) cartEvent()   {}
func (ItemRemoved) cartEvent() {}
func (CheckedOut) cartEvent()  {}
func (Discarded) cartEvent()   {}

// Events are the cases of Event, under the Scala sample's discriminators.
var Events = ankka.Cases[Event](
	ankka.Case[Event, ItemAdded]("ItemAdded"),
	ankka.Case[Event, ItemRemoved]("ItemRemoved"),
	ankka.Case[Event, CheckedOut]("CheckedOut"),
	ankka.Case[Event, Discarded]("Discarded"),
)
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
    // The cart is deleted straight after; the event is there for what reads the journal.
    case Discarded => currentState

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if item.quantity <= 0 then
      effects.error(s"quantity must be greater than zero, was ${item.quantity}")
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)

  def removeItem(productId: String): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if !currentState.contains(productId) then
      effects.error(s"cart does not contain '$productId'", ErrorCode.NotFound)
    else effects.persist(ItemRemoved(productId)).thenReply(_ => Done)

  /**
   * Records the checkout and answers with the cart as it was checked out.
   *
   * A checked-out cart stays: it is the record of what was ordered, and every later change to it is
   * refused.
   */
  def checkout: Effect[ShoppingCart] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if currentState.isEmpty then effects.error("cannot check out an empty cart")
    else effects.persist(CheckedOut).thenReplyState

  /**
   * Records the discard and then deletes the cart, so the same id starts again empty.
   *
   * The event is persisted before the deletion takes effect, so a consumer or view downstream still
   * observes that this cart was discarded rather than merely vanishing. A checked-out cart is a
   * record of an order and is not discarded.
   */
  def discard: Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else effects.persist(Discarded).deleteEntity().thenReply(_ => Done)

  def getCart: ReadOnlyEffect[ShoppingCart] = effects.reply(currentState)

  def totalQuantity: ReadOnlyEffect[Int] = effects.reply(currentState.totalQuantity)
```

**Python**

```python
from __future__ import annotations

from ankka import DONE, Done, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query

from examples.shopping_cart.domain import CheckedOut, Discarded, ItemAdded, ItemRemoved, LineItem, ShoppingCart, ShoppingCartEvent


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
            case Discarded():
                # The cart is deleted straight after; the event is there for what reads the journal.
                return state
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
        # As the Scala cart: a checked-out cart is kept, the record of what was ordered, and every
        # later change to it is refused.
        return self.effects.persist(CheckedOut()).then_reply_state()

    @command("discard")
    def discard(self) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        # The event is persisted, then the cart is deleted, so a consumer downstream still sees the
        # discard rather than a cart that vanished; the same id then starts again empty.
        return self.effects.persist(Discarded()).delete_entity().then_reply(lambda _: DONE)

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)

    @query("total-quantity")
    def total_quantity(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, int]:
        return self.effects.reply(self.state.total_quantity)
```

**TypeScript**

```ts
export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")
  static readonly snapshotEvery = 100

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    removeItem: command("remove-item", s.string, Done, (cart: ShoppingCartEntity, productId) => cart.removeItem(productId)),
    checkout: command("checkout", ShoppingCart, (cart: ShoppingCartEntity) => cart.checkout()),
    discard: command("discard", Done, (cart: ShoppingCartEntity) => cart.discard()),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
    totalQuantity: query("total-quantity", s.int, (cart: ShoppingCartEntity) => cart.effects.reply(totalQuantity(cart.state))),
  }

  emptyState(): ShoppingCart {
    return emptyCart(this.entityId)
  }

  applyEvent(cart: ShoppingCart, event: ShoppingCartEvent): ShoppingCart {
    switch (event.type) {
      case "ItemAdded":
        return addItem(cart, event.item)
      case "ItemRemoved":
        return removeItem(cart, event.productId)
      case "CheckedOut":
        return { ...cart, checkedOut: true }
      case "Discarded":
        // The cart is deleted straight after; the event is there for what reads the journal.
        return cart
    }
  }

  addItem(item: LineItem) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (item.quantity <= 0) return this.effects.error(`quantity must be greater than zero, was ${item.quantity}`)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
  }

  removeItem(productId: string) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (!contains(this.state, productId)) return this.effects.error(`cart does not contain '${productId}'`, ErrorCode.NotFound)
    return this.effects.persist({ type: "ItemRemoved", productId }).thenReply(() => done)
  }

  checkout() {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (this.state.items.length === 0) return this.effects.error("cannot check out an empty cart")
    // As the Scala cart: a checked-out cart is kept, the record of what was ordered, and every later
    // change to it is refused.
    return this.effects.persist({ type: "CheckedOut" }).thenReplyState()
  }

  discard() {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    // The event is persisted, then the cart is deleted, so a consumer downstream still sees the discard
    // rather than a cart that vanished; the same id then starts again empty.
    return this.effects.persist({ type: "Discarded" }).deleteEntity().thenReply(() => done)
  }
}
```

**Go**

```go
// Entity is the shopping cart: the same wire names and manifests as the Scala sample's.
var Entity = ankka.EventSourced[Cart, Event](ComponentID, Events, Empty, Apply,
	ankka.Command("add-item", addItem),
	ankka.Command("remove-item", removeItem),
	ankka.Command("checkout", checkout),
	ankka.Command("discard", discard),
	ankka.Query("get-cart", func(_ context.Context, c Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[Cart], error) {
		return ankka.Reply(c), nil
	}),
	ankka.Query("total-quantity", func(_ context.Context, c Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(TotalQuantity(c)), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Cart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
	ankka.SnapshotEvery(3),
)

var checkedOut = ankka.Refuse(ankka.Conflict, "cart is already checked out")

func done(Cart) ankka.Done { return ankka.Done{} }

func addItem(_ context.Context, c Cart, item LineItem) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemAdded{Item: item}).ThenReply(done), nil
}

func removeItem(_ context.Context, c Cart, productID string) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if !Contains(c, productID) {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.NotFound, "cart does not contain '%s'", productID)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemRemoved{ProductID: productID}).ThenReply(done), nil
}

// checkout keeps the cart, the record of what was ordered, and refuses every later change to it.
func checkout(_ context.Context, c Cart, _ ankka.Unit) (effect[Cart], error) {
	if c.CheckedOut {
		return effect[Cart]{}, checkedOut
	}
	if len(c.Items) == 0 {
		return effect[Cart]{}, ankka.Refuse(ankka.BadRequest, "cannot check out an empty cart")
	}
	return ankka.Persist[Cart, Event, Cart](CheckedOut{}).ThenReplyState(), nil
}

// discard persists the event, then deletes the cart, so a consumer downstream sees the discard
// rather than a cart that vanished; the same id then starts again empty.
func discard(_ context.Context, c Cart, _ ankka.Unit) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	return ankka.Persist[Cart, Event, ankka.Done](Discarded{}).DeleteEntity().ThenReply(done), nil
}
```

In Scala, `currentState` is the state as of now: after replay, and after any events this command has
already persisted. In Python the same value is `self.state`, and `apply_event` receives the state to
fold into as an argument. The entity's id is `context.entityId` in Scala, from the context the runtime
passes to the constructor, and `self.entity_id` in Python. In Go a handler is a plain function given
the state and the input, the empty state is a function of the entity's id, and a refusal is the error the
handler returns, built with `ankka.Refuse` or `ankka.Refusef`. A query returns `ankka.ReadOnlyEffect`,
which no persisting builder makes, so a query that persists does not compile.

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
  val discard       = command("discard")(_.discard)
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

Deleting an entity marks it deleted in the journal. The shopping cart deletes itself when a customer
discards it, after persisting a final event:

```scala
/**
 * Records the discard and then deletes the cart, so the same id starts again empty.
 *
 * The event is persisted before the deletion takes effect, so a consumer or view downstream still
 * observes that this cart was discarded rather than merely vanishing. A checked-out cart is a
 * record of an order and is not discarded.
 */
def discard: Effect[Done] =
  if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
  else effects.persist(Discarded).deleteEntity().thenReply(_ => Done)
```

Persisting the event before deleting matters to everything downstream. A view or consumer reading the
cart's events sees `Discarded` and then the deletion, so it learns that the cart was discarded rather
than that it vanished. A view decides what a deleted entity's row becomes; see
[Views](views.md#when-the-source-is-deleted).

Not every ending is a deletion. A checked-out cart is kept: it is the record of what was ordered, so
checkout persists `CheckedOut` and leaves the entity in place, and every handler refuses a change to a
checked-out cart with `ErrorCode.Conflict`. Delete an entity when its id should start again; keep it,
in a state that refuses changes, when what it holds is still worth reading.

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
| Go | never | pass `ankka.SnapshotEvery(100)` to `ankka.EventSourced`; `0` means never |

Snapshots are stored with the state serializer, so the state type follows the same evolution rules as the
events.

## Registering the entity

Registration is explicit: a component the service does not register does not exist, and a call to it
fails. In Scala, register the companion's descriptor on the service builder. In Python and TypeScript,
register the class. In Go, pass the declaration to `ankka.NewService`.

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

**TypeScript**

```ts
import { Ankka } from "ankka"

const service = Ankka.service().register(ShoppingCartEntity)
```

**Go**

```go
// Service is the whole inventory: registration is explicit, so nothing is found by scanning.
func Service() *ankka.Service { return ankka.NewService(Entity, API, Calling) }
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

**TypeScript**

```ts
const done = await client.of(ShoppingCartEntity, "c1").call(ShoppingCartEntity.handlers.addItem).invoke(item)
const cart = await client.of(ShoppingCartEntity, "c1").call(ShoppingCartEntity.handlers.getCart).invoke()
```

**Go**

```go
ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (Cart, error) {
	return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "get-cart", ankka.Unit{})
}),
ankka.Get("/{cartId}/total", func(ctx context.Context, req *ankka.Request) (int, error) {
	return ankka.Invoke[int](ctx, ComponentID, req.Param("cartId"), "total-quantity", ankka.Unit{})
}),
ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
	return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "add-item", item)
}),
```

In Scala, `invoke` blocks and `invokeAsync` returns a `Future`. Blocking is cheap because endpoints,
workflow steps, consumers and timers run on virtual threads. An entity handler itself never calls
another component: it returns an effect, and the runtime does the rest. See
[Calling components](component-client.md).

An entity that has never received a command exists with its empty state, so reading it answers the empty
state rather than failing.

## Replication

A replicated entity is held at several installations of the service and written at one of them, its
home; the other installations in its replication set are followers, which hold the home's journal
record for record and answer queries from it. A command that may persist and reaches a follower is
forwarded to the home. [Replicated entities](../concepts/replicated-entities.md) describes the model;
this section is the code.

### Declaring an entity replicated

The companion declares it, and the service names the installations it may replicate with in
`ankka.replication.installations` (see [Configuration](../reference/configuration.md)). Nothing else
changes: the handlers, the events and the wire names are an unreplicated entity's.

**Scala**

```scala
final class VehicleKind(id: String, replicatedHere: Boolean = true)
    extends EventSourcedEntity.Companion[VehicleEntity, Vehicle, VehicleEvent](
      ComponentId(id),
      FleetCodecs.vehicle,
      FleetCodecs.event
    ):
  override def replicated: Boolean = replicatedHere

  def create(ctx: EventSourcedEntityContext) = new VehicleEntity(ctx)

  val createVehicle     = command("create")(_.create)
  val addStep           = command("add-step")(_.addStep)
  val recordInspection  = command("record-inspection")(_.recordInspection)
  val recordSale        = command("record-sale")(_.recordSale)
  val handOver          = command("hand-over")(_.handOver)
  val replicateTo       = command("replicate-to")(_.replicateTo)
  val stopReplicatingTo = command("stop-replicating-to")(_.stopReplicatingTo)
  val get               = query("get")(_.get)
  val placement         = query("placement")(_.placement)
```

**Python**

```python
class VehicleEntity(EventSourcedEntity[Vehicle, VehicleEvent]):
    component_id = "vehicle"
    replicated = True
```

**TypeScript**

```ts
class VehicleEntity extends EventSourcedEntity<Vehicle, VehicleEvent> {
  static readonly componentId = "vehicle"
  static readonly replicated = true
  // …
}
```

**Rust**

```rust
impl EventSourcedEntity for VehicleEntity {
    type State = Vehicle;
    type Event = VehicleEvent;
    const COMPONENT_ID: &'static str = "vehicle";
    const REPLICATED: bool = true;
    // …
}
```

**Go**

```go
// ReplicatedVehicle is the replicated entity. The conformance service runs at installation a.
var ReplicatedVehicle = ankka.EventSourced[VehicleRecord, StepAdded]("replicated-vehicle", nil, emptyVehicle,
	func(s VehicleRecord, e StepAdded) VehicleRecord { return appendStep(s, e, EventPlace{Local: true}) },
	ankka.ApplyWithContext(func(ctx context.Context, s VehicleRecord, e StepAdded) VehicleRecord {
		at := EventPlace{Local: true}
		if ev, ok := ankka.EventFrom(ctx); ok {
			at = EventPlace{Installation: ev.Installation, Local: ev.Local, Attribution: ev.Attribution, ForwardedFrom: ev.ForwardedFrom}
		}
		return appendStep(s, e, at)
	}),
	ankka.Replicated(),
```

Only an event sourced entity can be replicated. A key value entity or a workflow declared replicated is
refused when the service starts.

### Handing the home over and changing the replication set

Three effects change where the entity is held, each alone or beside the events a command persists:
`handOverTo(installation)` makes another installation in the replication set the home,
`replicateTo(installation)` adds an installation to the set, and `stopReplicatingTo(installation)`
removes one, which keeps the copy it has. One command asks for one handover or one change to the set; a
change to the set may add and remove several installations.

**Scala**

```scala
/** Ships the vehicle: its home is the dealership's installation from now on. */
def handOver(installation: String): Effect[Receipt] =
  effects.handOverTo(installation).thenReply(receipt("hand-over"))

/** Allots the vehicle to a dealership, whose installation holds it from now on. */
def replicateTo(installation: String): Effect[Receipt] =
  effects.replicateTo(installation).thenReply(receipt("replicate-to"))

/** The factory no longer needs it: its installation keeps what it holds and mirrors no more. */
def stopReplicatingTo(installation: String): Effect[Receipt] =
  effects.stopReplicatingTo(installation).thenReply(receipt("stop-replicating-to"))
```

**Python**

```python
@command("hand-over")
def hand_over(self, installation: str) -> EventSourcedEffect[Vehicle, VehicleEvent, Done]:
    return self.effects.hand_over_to(installation).then_reply(lambda _: DONE)

@command("allot")
def allot(self, dealer: str) -> EventSourcedEffect[Vehicle, VehicleEvent, Done]:
    return self.effects.persist(Allotted(dealer)).replicate_to(dealer).then_reply(lambda _: DONE)
```

**TypeScript**

```ts
handOver: command("hand-over", s.string, Done, (v: VehicleEntity, installation) =>
  v.effects.handOverTo(installation).thenReply(() => done)),
allot: command("allot", s.string, Done, (v: VehicleEntity, dealer) =>
  v.effects.persist({ type: "Allotted", dealer }).replicateTo(dealer).thenReply(() => done)),
```

**Rust**

```rust
fn hand_over(_: &Vehicle, installation: String, _: &Context) -> Effect<VehicleEvent, Done> {
    effects::hand_over_to(installation).then_reply_value(Done)
}

fn allot(_: &Vehicle, dealer: String, _: &Context) -> Effect<VehicleEvent, Done> {
    effects::persist(VehicleEvent::Allotted { dealer: dealer.clone() })
        .replicate_to(dealer)
        .then_reply_value(Done)
}
```

**Go**

```go
ankka.Command("hand-over", replicate(func(t string) ankka.PersistBuilder[VehicleRecord, StepAdded, string] {
	return vehicle[string]().HandOverTo(t)
})),
ankka.Command("replicate-to", replicate(func(t string) ankka.PersistBuilder[VehicleRecord, StepAdded, string] {
	return vehicle[string]().ReplicateTo(t)
})),
ankka.Command("stop-replicating-to", replicate(func(t string) ankka.PersistBuilder[VehicleRecord, StepAdded, string] {
	return vehicle[string]().StopReplicatingTo(t)
})),
```

What each answers:

| Asked for | Refused when |
|---|---|
| a handover | the installation is the home already, is not one the service names, or is outside the replication set; or another handover is waiting |
| a change to the set | an installation is not one the service names, or the home removes itself |
| either | the entity is not replicated, or the service names no other installation |

A refusal is `BadRequest` and nothing the command persisted is written. An effect that asks for a
handover and a change to the set at once, or adds and removes the same installation, is a mistake in the
handler and fails where it is built: an exception in Scala, Python and TypeScript, an `Internal` answer
in Rust. The caller of a handover is
answered once the handover is recorded at the old home; it completes once the new home holds everything.
In Scala, `componentClient.forEventSourcedEntity(id).awaitHandover(companion, installation, timeout)`,
asked at the old home, waits for the completion.

### What a handler knows about where it runs

A command handler reads the installation it runs at and whether that is the home. A query at a follower
runs there, with `isHome` false; every command that may persist runs at the home.

| | Installation | At the home |
|---|---|---|
| Scala | `commandContext.installation` | `commandContext.isHome` |
| Python | `self.context.installation` | `self.context.is_home` |
| TypeScript | `this.context.installation` | `this.context.isHome` |
| Rust | `ctx.installation()` | `ctx.is_home()` |
| Go | `ankka.CommandFrom(ctx)`'s `Installation` | its `AtHome` |

The event handler can read where the event it applies was written, whether that is this installation,
and its sequence number, which is the same at every installation. It runs at the home when an event is
persisted and at every installation when the history is replayed, so what it records from the context is
the same everywhere.

**Scala**

```scala
def applyEvent(event: VehicleEvent): Vehicle =
  val vehicle = event match
    case Created(id)              => currentState.copy(id = id)
    case StepAdded(step)          => currentState.copy(steps = currentState.steps :+ step)
    case InspectionRecorded(note) =>
      currentState.copy(inspections = currentState.inspections :+ note)
    case Sold => currentState.copy(sold = true)
  // Where this event was written, on replay as well as when it is persisted here.
  vehicle.copy(
    writtenAt = vehicle.writtenAt :+ eventContext.installation,
    local = vehicle.local :+ eventContext.isLocal,
    // Who acted on the command that wrote it, as its record says.
    actedBy = vehicle.actedBy :+ eventContext.attribution
  )
```

**Python**

```python
def apply_event(self, state: Vehicle, event: StepAdded) -> Vehicle:
    written = self.event_context  # installation, is_local, sequence
    return Vehicle(state.steps + [event.step], state.written_at + [written.installation])
```

**TypeScript**

```ts
applyEvent(state: Vehicle, event: StepAdded, context?: EventContext): Vehicle {
  // context.installation, context.isLocal, context.sequence
  return { steps: [...state.steps, event.step], writtenAt: [...state.writtenAt, context?.installation ?? ""] }
}
```

**Rust**

```rust
// `apply` is unchanged; `apply_at` is called instead, and calls `apply` unless overridden.
fn apply_at(mut state: Vehicle, event: &VehicleEvent, ctx: &EventContext) -> Vehicle {
    let VehicleEvent::StepAdded { step } = event;
    state.steps.push(step.clone());
    state.written_at.push(ctx.installation().unwrap_or_default().to_string());
    state
}
```

**Go**

```go
ankka.ApplyWithContext(func(ctx context.Context, s VehicleRecord, e StepAdded) VehicleRecord {
	at := EventPlace{Local: true}
	if ev, ok := ankka.EventFrom(ctx); ok {
		at = EventPlace{Installation: ev.Installation, Local: ev.Local, Attribution: ev.Attribution, ForwardedFrom: ev.ForwardedFrom}
	}
	return appendStep(s, e, at)
}),
```

### Errors a replicated entity answers

| Code | When |
|---|---|
| `NotHome` | a write reached a follower whose home cannot be reached; a query reached an installation outside the replication set; a forwarded write found that the installation it was sent to is no longer the home. The message names the home |
| `Conflict` | a write reached an entity created at two installations, which takes no write until an operator resolves it |
| `Timeout` | a forwarded write was not answered in time; it may or may not have happened |
| `BadRequest` | a handover or a change to the replication set was refused |

`NotHome` is HTTP `421` and gRPC `OUT_OF_RANGE`. See [Error codes](../reference/error-codes.md).

### Reading how a copy stands

Any installation's copy answers its status from its own journal: the home, the replication set, a
handover waiting, a conflict, and the position its history reaches.

```scala
val status = componentClient.forEventSourcedEntity(EntityId("v1")).replication(Vehicles)
status.home            // Some("leipzig")
status.replicationSet  // Set("leipzig", "vienna")
status.position        // how many records this copy holds
```

### Testing a replicated entity

The entity test kit drives a copy as its home, where every command runs and a handover or a change to the
replication set is shown on the result, or as a follower, where a command that may persist is refused
`NotHome` and a query is answered from history the test gives it:

**Scala**

```scala
val kit = EventSourcedTestKit.of(
  Fleet.vehicles,
  "v1",
  mode = EventSourcedTestKit.Mode.Follower(home = "leipzig"),
  installation = "bratislava"
)
given EventSourcedTestKit.WrittenAt = EventSourcedTestKit.WrittenAt(Some("leipzig"))
kit.replay(VehicleEvent.Created("v1"), VehicleEvent.StepAdded("frame"))
```

**Python**

```python
kit = EventSourcedTestKit.of(VehicleEntity, "v1", mode="follower", home="leipzig", installation="bratislava")
kit.replay(StepAdded("frame"), written_at="leipzig")
assert kit.call("add-step", "wheels").error.code == ErrorCode.NOT_HOME
```

**TypeScript**

```ts
const kit = EventSourcedTestKit.of(VehicleEntity, "v1", noClient(), { kind: "follower", home: "leipzig", installation: "bratislava" })
kit.replay([{ step: "frame" }], "leipzig")
assert.equal((await kit.call("add-step", "wheels")).error?.code, ErrorCode.NotHome)
```

**Rust**

```rust
let mut kit = EventSourcedTestKit::<VehicleEntity>::new("v1").in_mode(ReplicationMode::Follower {
    home: "leipzig".to_string(),
    installation: "bratislava".to_string(),
});
kit.replay(vec![VehicleEvent::StepAdded { step: "frame".to_string() }], Some("leipzig"));
assert_eq!(kit.command("add-step", "wheels".to_string()).error().map(|e| e.code), Some(ErrorCode::NotHome));
```

A whole service at several installations is tested with the integration test kit; see
[Testing](testing.md#several-installations).

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

**TypeScript**

```ts
test("adds an item and replies done", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
  assert.deepEqual(result.events, [{ type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } }])
  assert.equal(result.reply, done)
  assert.equal(kit.state.items.length, 1)
})
```

**Go**

```go
func TestAddingAndRemovingItems(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(cart.Entity, testkit.EntityID("c1"))
	if r := kit.Command(ctx, "add-item", pen); r.Refusal() != nil || len(r.Events()) != 1 || r.Events()[0] != (cart.ItemAdded{Item: pen}) {
		t.Fatalf("add-item answered %+v", r)
	}
	kit.Command(ctx, "add-item", ink)
	if q := kit.Query(ctx, "total-quantity", ankka.Unit{}); q.Reply() != 3 {
		t.Fatalf("the total is %v", q.Reply())
	}
	// Items are kept sorted by product, as the Scala sample keeps them.
	if got := kit.State().Items; len(got) != 2 || got[0] != ink || got[1] != pen {
		t.Fatalf("the items are %+v", got)
	}
	if r := kit.Command(ctx, "remove-item", "pen"); r.Refusal() != nil || r.Events()[0] != (cart.ItemRemoved{ProductID: "pen"}) {
		t.Fatalf("remove-item answered %+v", r)
	}
	if got := kit.State(); got.CartID != "c1" || len(got.Items) != 1 || got.Items[0] != ink {
		t.Fatalf("after removing, the cart is %+v", got)
	}
}
```

See [Testing](testing.md) for the integration testkit, which runs the whole service against a real
database and can restart it to prove the state is durable.
