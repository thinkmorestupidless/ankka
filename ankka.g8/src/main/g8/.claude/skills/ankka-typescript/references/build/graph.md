# Publish a graph

> Publish a service's entities as nodes and edges with a graph consumer, which writes versioned graph deltas to a topic for a graph database to follow, with no key, version or JSON written by hand.

Source: https://docs.ankka.cloud/build/graph/
A graph consumer publishes a service's entities as a graph: a node for each cart, a node for each
checkout, an edge from one to the other. For each change to its source it says which **elements** — nodes
and edges — the change leaves in which state, and ankka publishes each one to a topic as a **graph delta**:
one element's whole state at a version, or a tombstone marking it deleted.

The deltas follow the contract `ankka.graph-delta.v1`, which belongs to
[ankka-flow](https://flow.ankka.cloud/reference/graph-deltas/). Its built-in merge sink reads a topic of
deltas and keeps a Neo4j database in step with it, applying a delta when its version is newer than what
the graph holds. So a service with a graph consumer needs no second program to have its entities in a
graph database: the pipeline that fills the database is the sink alone.

A graph consumer is a [consumer](consumers.md). It reads one source, is delivered each change at least
once, and is registered, sharded and started as any consumer is. What differs is what it may return:
elements, and nothing else.

## Writing a graph consumer

A graph consumer's handler returns the elements a change leaves, and writes no record key, no version and
no JSON. This one publishes the shopping cart: the cart's node whenever its items change, and on a checkout
the cart again, a node for the checkout and the edge between them.

**Scala**

```scala
/**
 * Publishes the carts as a graph: a node for each cart, a node for each checkout, and an edge from
 * one to the other.
 *
 * Each handler says which elements the change leaves in which state. Every element is its whole
 * state — the cart node carries all of its properties each time — and takes the event's sequence
 * number as its version, so the newest state wins wherever the graph is kept and a change handled
 * twice changes nothing.
 */
final class CartGraph extends GraphConsumer[ShoppingCartEvent]:

  def onMessage(event: ShoppingCartEvent): Effect =
    val cartId = messageContext.subject
    event match
      case ItemAdded(_) | ItemRemoved(_) =>
        effects.publish(cart(cartId, checkedOut = false))
      case CheckedOut =>
        effects.publish(
          cart(cartId, checkedOut = true),
          graph.node(s"checkout:\$cartId", Seq("Checkout"), Map("cartId" -> cartId)),
          graph.edge(
            s"checked-out:\$cartId",
            "CHECKED_OUT",
            from = s"cart:\$cartId",
            to = s"checkout:\$cartId"
          )
        )
      // The deletion that follows is what removes the cart from the graph.
      case Discarded => effects.ignore()

  /** A discarded cart is deleted: mark its node, so an older delta cannot bring it back. */
  override def onDelete: Effect =
    effects.publish(graph.tombstoneNode(s"cart:\${messageContext.subject}"))

  private def cart(cartId: String, checkedOut: Boolean): GraphElement =
    graph.node(s"cart:\$cartId", Seq("Cart"), Map("cartId" -> cartId, "checkedOut" -> checkedOut))

object CartGraph
    extends GraphConsumer.Companion[CartGraph, ShoppingCartEvent](
      componentId = ComponentId("cart-graph"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      topic = "cart-graph"
    ):
  def create(ctx: ConsumerContext) = new CartGraph
```

**Python**

```python
class CartGraph(GraphConsumer[ShoppingCartEvent]):
    """Each element is the element's whole state after the event, at the event's sequence number:
    no key, no version and no JSON is written here."""

    component_id = "cart-graph"
    source = ShoppingCartEntity
    produces_to = "cart-graph"
    message_codec = ShoppingCartEntity.event_codec

    def on_message(self, event: ShoppingCartEvent) -> GraphEffect:
        cart_id = self.metadata.subject or ""
        match event:
            case ItemAdded() | ItemRemoved():
                return self.effects.publish([self._cart(cart_id, checked_out=False)])
            case CheckedOut():
                return self.effects.publish(
                    [
                        self._cart(cart_id, checked_out=True),
                        self.graph.node(f"checkout:{cart_id}", labels=["Checkout"], properties={"cartId": cart_id}),
                        self.graph.edge(f"checked-out:{cart_id}", type="CHECKED_OUT", from_id=f"cart:{cart_id}", to_id=f"checkout:{cart_id}"),
                    ]
                )
            case _:
                # Discarded: the deletion that follows it is what marks the cart gone.
                return self.effects.ignore()

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"cart:{self.metadata.subject}")])

    def _cart(self, cart_id: str, *, checked_out: bool) -> Element:
        return self.graph.node(f"cart:{cart_id}", labels=["Cart"], properties={"cartId": cart_id, "checkedOut": checked_out})
```

**TypeScript**

```ts
export class CartGraph extends GraphConsumer<ShoppingCartEvent> {
  static readonly componentId = "cart-graph"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly producesTo = "cart-graph"

  /** Each element is the whole state of a node or an edge; its version is the event's sequence number. */
  onMessage(event: ShoppingCartEvent) {
    const id = this.subject
    switch (event.type) {
      case "ItemAdded":
      case "ItemRemoved":
        return this.effects.publish([this.cart(id, false)])
      case "CheckedOut":
        return this.effects.publish([
          this.cart(id, true),
          this.graph.node(`checkout:\${id}`, { labels: ["Checkout"], properties: { cartId: id } }),
          this.graph.edge(`checked-out:\${id}`, { type: "CHECKED_OUT", from: `cart:\${id}`, to: `checkout:\${id}` }),
        ])
      default:
        // Discarded: the deletion that follows is what marks the cart.
        return this.effects.ignore()
    }
  }

  /** The cart was deleted: a tombstone marks its node, above every version published before. */
  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`cart:\${this.subject}`)])
  }

  private cart(id: string, checkedOut: boolean) {
    return this.graph.node(`cart:\${id}`, { labels: ["Cart"], properties: { cartId: id, checkedOut } })
  }
}
```

**Rust**

```rust
pub struct CartGraph;

impl GraphConsumer for CartGraph {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-graph";
    const TOPIC: &'static str = "cart-graph";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> GraphEffect {
        let id = ctx.entity_id();
        match event {
            ShoppingCartEvent::ItemAdded { .. } | ShoppingCartEvent::ItemRemoved { .. } => {
                graph::publish([cart(id, false)])
            }
            ShoppingCartEvent::CheckedOut => graph::publish([
                cart(id, true),
                graph::node(format!("checkout:{id}"))
                    .label("Checkout")
                    .property("cartId", id),
                graph::edge(
                    format!("checked-out:{id}"),
                    "CHECKED_OUT",
                    format!("cart:{id}"),
                    format!("checkout:{id}"),
                ),
            ]),
            // The deletion that follows is what marks the cart's node.
            ShoppingCartEvent::Discarded => graph::ignore(),
        }
    }

    /// A discarded cart is deleted, and its node is marked deleted: a tombstone, above every
    /// delta the cart published before it.
    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!("cart:{}", ctx.entity_id()))])
    }
}

/// The cart's node, whole: an element is its state, not a change to it.
fn cart(id: &str, checked_out: bool) -> Element {
    graph::node(format!("cart:{id}"))
        .label("Cart")
        .property("cartId", id)
        .property("checkedOut", checked_out)
}
```

The parts are the same in every language:

| | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|
| Base | `GraphConsumer[Src]` and `GraphConsumer.Companion(componentId, source, topic)` | `ankka.GraphConsumer[Src]` | `GraphConsumer<M>` | the trait `GraphConsumer` |
| The topic | the companion's `topic` | `produces_to` | `static producesTo` | `const TOPIC` |
| A node | `graph.node(id, labels, properties)` | `self.graph.node(id, labels=…, properties=…)` | `this.graph.node(id, { labels, properties })` | `graph::node(id).label(…).property(…, …)` |
| An edge | `graph.edge(id, type, from, to, properties)` | `self.graph.edge(id, type=…, from_id=…, to_id=…)` | `this.graph.edge(id, { type, from, to })` | `graph::edge(id, type, from, to)` |
| A tombstone | `graph.tombstoneNode(id)`, `graph.tombstoneEdge(id, type, from, to)` | `self.graph.tombstone_node(id)`, `tombstone_edge(…)` | `this.graph.tombstoneNode(id)`, `tombstoneEdge(id, {…})` | `graph::tombstone_node(id)`, `graph::tombstone_edge(…)` |
| Publish | `effects.publish(elements*)` | `self.effects.publish([…])` | `this.effects.publish([…])` | `graph::publish([…])` |
| Nothing to publish | `effects.done()`, `effects.ignore()` | `self.effects.done()`, `.ignore()` | `this.effects.done()`, `.ignore()` | `graph::done()`, `graph::ignore()` |

A property value is a string, a number, a boolean, or a list of one of those that is not empty. An edge
runs from one node's id to another's and has exactly one type. A label and an edge's type are identifiers:
letters, digits and underscores, not starting with a digit.

A graph consumer is registered like any consumer — `.register(CartGraph.descriptor)` in Scala,
`.register(CartGraph)` in Python, TypeScript and Rust — and, because it publishes to a topic, the service
needs a broker: `ProjectionRuntime.withKafka(…)` or `ProjectionRuntime.fromEnv()` in Scala, and
`ANKKA_KAFKA_BOOTSTRAP_SERVERS` in the service descriptor's `env` for the others. A service with a graph
consumer and no broker is refused at startup, naming the consumer. See [Broker topics](topics.md#connecting-to-kafka).

## What is published

Each element is published as one record on the consumer's topic. For the cart `c1` checked out at its
fourth event, the cart's node is this record:

```text
key      node:cart:c1
headers  ce-type: ankka.graph-delta.v1
         ce-subject: c1
         content-type: application/json
```

```json
{"kind":"node","id":"cart:c1","version":4,"labels":["Cart"],"properties":{"cartId":"c1","checkedOut":true}}
```

| Part | What it is |
|---|---|
| Record key | The **element key**: `node:<id>` or `edge:<id>`. Nodes and edges are separate id spaces, so a node and an edge with the same id have different keys. A tombstone has the key of the element it marks. |
| Version | The sequence number of the change being handled: an event's sequence number, or a key value entity's revision. |
| Value | The delta as JSON. `labels` and `properties` are always written, empty when there are none. |
| `ce-type` | `ankka.graph-delta.v1`, so anything reading the topic can tell what a record is. |
| `ce-subject` | The source entity's id, as on every message a consumer publishes. |

The record key is never the author's to set. It is what a compacted topic keeps one record of and what
keeps an element's deltas in order, so a graph consumer has no way to name one, and no way to publish
anything that is not a delta. A change that leaves several elements publishes several records, in the
order returned, and counts as handled when the broker has accepted all of them; see
[Consumers](consumers.md#publishing-several-messages-for-one-change).

## The rules a writer keeps

Four rules decide whether the graph is right, and none of them can be checked for you.

**An element is its whole state, not a change to it.** The cart's node carries every one of its
properties each time it is published. A property left out is removed from the graph; nothing means "add
to" or "leave as it was". The reader replaces what it holds with what it is given.

**An element has exactly one writing entity.** Versions come from one entity's own history, and two
entities' sequence numbers cannot be compared: whichever was higher would win for ever. A cart must not
publish the `product` node its items point at. It publishes the edge to the product, the merge sink
creates a placeholder for an endpoint nobody has described yet, and the product's own entity publishes the
product. Two consumers over the same entity may publish to one topic so long as they write different
elements.

**Ids are global and stable.** Prefix them by the kind of thing — `cart:c1`, `checkout:c1` — so ids from
different entities cannot collide, and derive them from the entity's id so the same element always has the
same id.

**A tombstone marks an element; it does not remove it.** The element stays in the graph, marked deleted,
at the tombstone's version, so an older delta arriving late cannot bring it back. Publishing the element
again at a higher version makes it live again.

## Versions

An element's version is the sequence number of the change being handled, unless the element states one.
For an event sourced entity that is the event's sequence number, and for a key value entity the state's
revision. All the elements of one change that state no version share the change's.

State a version to use something else that rises with the element's history:

| Scala | Python | TypeScript | Rust |
|---|---|---|---|
| `graph.node(id).at(version)` | `self.graph.node(id, version=version)` | `this.graph.node(id, { version })` | `graph::node(id).at(version)` |

A version is a whole number of at least 1. Zero is refused because it is what a change with no sequence
number presents, and a delta published at zero would lose to every other delta for its element.

A [broker topic](topics.md) as a source has no sequence number. A graph consumer over a topic must state
the version of every element it publishes, from something in the message that rises per element. An
element that states none fails its message, with an error saying a version must be stated.

## When an event does not carry the whole state

Build an element from the entity's state, read through the component client, when the event alone does not
say everything the element shows. An event says what changed — one item added — and an element is its
whole state: how many lines the cart has now. This consumer publishes what each cart holds, as a node of
its own beside the cart's:

**Scala**

```scala
/**
 * Publishes what each cart holds, as a node of its own beside the cart's.
 *
 * An event says what changed — one item added — and an element is its whole state: how many lines
 * the cart has now. So this consumer does not build the element from the event. It reads the cart
 * through the component client and publishes what it finds, at the version of the event it is
 * handling.
 *
 * The cart it reads may already be ahead of that event. The delta then carries a later state at an
 * earlier version, and the events still to come publish it again at their own: the graph is never
 * behind for longer than the consumer is, and ends where the cart is.
 */
final class CartContentsGraph(client: ComponentClient) extends GraphConsumer[ShoppingCartEvent]:

  def onMessage(event: ShoppingCartEvent): Effect = event match
    case Discarded => effects.ignore()
    case ItemAdded(_) | ItemRemoved(_) | CheckedOut =>
      val cartId = messageContext.subject
      val cart = client
        .forEventSourcedEntity(EntityId(cartId))
        .call(ShoppingCartEntity.getCart)
        .invoke()
      effects.publish(
        graph.node(
          s"cart-contents:\$cartId",
          Seq("CartContents"),
          Map("cartId" -> cartId, "lines" -> cart.items.size, "quantity" -> cart.totalQuantity)
        )
      )

  override def onDelete: Effect =
    effects.publish(graph.tombstoneNode(s"cart-contents:\${messageContext.subject}"))

object CartContentsGraph
    extends GraphConsumer.Companion[CartContentsGraph, ShoppingCartEvent](
      componentId = ComponentId("cart-contents-graph"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      topic = "cart-graph"
    ):
  def create(ctx: ConsumerContext) = new CartContentsGraph(ctx.componentClient)
```

**Python**

```python
class CartContentsGraph(GraphConsumer[ShoppingCartEvent]):
    """An event says what changed — one item added — and an element is its whole state: how many
    lines the cart has now. So the element is not built from the event. The cart is read through
    the client and published as it is found, at the version of the event being handled.

    The cart read may already be ahead of that event. The delta then carries a later state at an
    earlier version, and the events still to come publish it again at their own: the graph is never
    behind for longer than the consumer is, and ends where the cart is."""

    component_id = "cart-contents-graph"
    source = ShoppingCartEntity
    produces_to = "cart-graph"
    message_codec = ShoppingCartEntity.event_codec

    async def on_message(self, event: ShoppingCartEvent) -> GraphEffect:  # type: ignore[override]
        if isinstance(event, Discarded):
            return self.effects.ignore()
        cart_id = self.metadata.subject or ""
        assert self.client is not None
        cart = await self.client.for_event_sourced_entity("shopping-cart", cart_id).call("get-cart").invoke(reply=ShoppingCart)
        return self.effects.publish(
            [
                self.graph.node(
                    f"cart-contents:{cart_id}",
                    labels=["CartContents"],
                    properties={"cartId": cart_id, "lines": len(cart.items), "quantity": cart.total_quantity},
                )
            ]
        )

    def on_delete(self) -> GraphEffect:
        return self.effects.publish([self.graph.tombstone_node(f"cart-contents:{self.metadata.subject}")])
```

**TypeScript**

```ts
/**
 * An event says what changed — one item added — and an element is its whole state: how many lines the
 * cart has now. So the element is not built from the event. The cart is read through the client and
 * published as it is found, at the version of the event being handled.
 *
 * The cart read may already be ahead of that event. The delta then carries a later state at an earlier
 * version, and the events still to come publish it again at their own: the graph is never behind for
 * longer than the consumer is, and ends where the cart is.
 */
export class CartContentsGraph extends GraphConsumer<ShoppingCartEvent> {
  static readonly componentId = "cart-contents-graph"
  static readonly source = ShoppingCartEntity
  static readonly message = ShoppingCartEntity.events
  static readonly producesTo = "cart-graph"

  async onMessage(event: ShoppingCartEvent) {
    if (event.type === "Discarded") return this.effects.ignore()
    const id = this.subject
    const cart = await this.client.of(ShoppingCartEntity, id).call(ShoppingCartEntity.handlers.getCart).invoke()
    return this.effects.publish([
      this.graph.node(`cart-contents:\${id}`, {
        labels: ["CartContents"],
        properties: { cartId: id, lines: cart.items.length, quantity: totalQuantity(cart) },
      }),
    ])
  }

  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`cart-contents:\${this.subject}`)])
  }
}
```

**Rust**

```rust
/// An event says what changed — one item added — and an element is its whole state: how many
/// lines the cart has now. So the element is not built from the event. The cart is read through
/// the client and published as it is found, at the version of the event being handled.
///
/// The cart read may already be ahead of that event. The delta then carries a later state at an
/// earlier version, and the events still to come publish it again at their own: the graph is
/// never behind for longer than the consumer is, and ends where the cart is.
pub struct CartContentsGraph;

impl GraphConsumer for CartContentsGraph {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-contents-graph";
    const TOPIC: &'static str = "cart-graph";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> GraphEffect {
        if let ShoppingCartEvent::Discarded = event {
            return graph::ignore();
        }
        let id = ctx.entity_id();
        // A panic sends the event again, which is what a consumer that could not read wants.
        let read: Result<Cart, CommandError> =
            ctx.client().invoke(ShoppingCart, id, "get-cart", ());
        let cart = read.expect("the cart answers");
        graph::publish([graph::node(format!("cart-contents:{id}"))
            .label("CartContents")
            .property("cartId", id)
            .property("lines", cart.items.len() as i64)
            .property("quantity", cart.total_quantity())])
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!(
            "cart-contents:{}",
            ctx.entity_id()
        ))])
    }
}
```

The state read may be later than the change being handled, because the entity does not wait for its
consumers. The delta then carries a later state at an earlier version, and the changes still to come
publish that state again at their own versions. The graph is never behind for longer than the consumer is,
and it ends where the entity is. What it cannot be is ahead in version and behind in state, which is the
case that would be wrong.

An event sourced entity's state is not available as a source in its own right: a graph consumer is handed
the event, and reads the state when it needs it.

## When the source is deleted

Publish tombstones from the deletion handler: `onDelete` in Scala and TypeScript, `on_delete` in Python,
`on_deleted` in Rust. It runs when the source entity is deleted, at a sequence number above every earlier
change to that entity, so a tombstone built there outranks every delta published before it. The cart graph
marks the cart's node there.

An entity created again under the same id publishes above its tombstone, because an entity's sequence
numbers go on counting through its deletion. The element is then live again. This holds for an event
sourced entity and for a [key value entity](key-value-entities.md#effects), whose deletion is a recorded
change at the revision after its last update.

An entity whose state has **expired** is not deleted. Expiry is noticed when a command next arrives;
nothing is delivered when the time passes, so no deletion handler runs and the entity's elements are not
tombstoned. An entity that must leave the graph is deleted, not left to expire.

Nothing removes an element's records from the topic. A graph consumer writes tombstones, which stay as
their element's last record; it never writes a record with no value.

## What is refused

An element the merge sink would refuse is refused before anything is published, so a consumer cannot
write a record that stalls its reader. The change is not handled, and is delivered again; the fix is in
the handler.

| Refused | Reason |
|---|---|
| An empty id | `id` |
| An edge with an empty type, `from` or `to` | `endpoints` |
| A label, or an edge's type, that is not an identifier | `identifier` |
| A property named `id`, `_version` or `_deleted`, which are the sink's own | `reserved` |
| A property value that is null, an object, an empty list, a list of more than one kind, a list in a list, or a number that is not finite | `property-value` |
| An integer, or a float with a whole value, that does not fit 64 bits | `integer-range` |
| A stated version below 1 | `version` |
| The same element twice in one change's result | `duplicate` |
| No stated version on a change with no sequence number | `no-sequence` |

A float with a whole value is stored by the sink as an integer, so `[1.5, 2.0]` is a list of two kinds and
is refused. In TypeScript an integral `number` must be a safe integer, and larger integers are `bigint`.

Each SDK raises the refusal as its own error, carrying the reason's name:

| Scala | Python | TypeScript | Rust |
|---|---|---|---|
| `GraphElementRefused`, with `why` | `graph.RefusedElement`, with `why` | `GraphError`, with `why` | a panic naming the element and the fault |

In Scala, Python and TypeScript a fault of one element is raised where the element is described, and the
two faults of a whole result — a duplicate, a missing version — once the handler has returned. In Rust the
builders are chainable and cannot fail, and every fault is raised when the result is dispatched.

## Publishing is safe to repeat

A change handled twice publishes equal deltas: the same keys, the same versions, the same values. The
merge sink passes over a delta that is not newer than what it holds, so the repeat changes nothing. A
graph consumer therefore needs none of the care [a consumer normally takes](consumers.md#making-a-consumer-safe-to-repeat)
over at-least-once delivery, provided its handler is a function of the change it is given. A handler that
reads the entity's state is still safe: what it publishes on a repeat is the entity's state then, at the
same version, and the later changes bring the version up.

## Testing a graph consumer

A graph consumer is tested with nothing started: hand it a change at a sequence number and read back the
elements it publishes. Each is read from the bytes that would be on the topic, under the key it would
have, so the test asserts on what a reader of the topic is given.

**Scala**

```scala
private val kit = ConsumerTestKit.graph(CartGraph)

test("an item added publishes the cart's node, at the event's sequence number") {
  val deltas =
    kit.onMessage(ItemAdded(LineItem("p1", "Pen", 1)), subject = "c1", sequenceNumber = 1)

  assertEquals(deltas.map(_.key), Vector("node:cart:c1"))
  val cart = deltas.head
  assertEquals(cart.version, 1L)
  assertEquals(cart.labels, Vector("Cart"))
  assertEquals(cart.properties, Map("cartId" -> "c1", "checkedOut" -> false))
}

test("a checkout publishes the cart as checked out, the checkout, and the edge between them") {
  val deltas = kit.onMessage(CheckedOut, subject = "c1", sequenceNumber = 4)

  assertEquals(
    deltas.map(_.key),
    Vector("node:cart:c1", "node:checkout:c1", "edge:checked-out:c1")
  )
  assertEquals(deltas.map(_.version).distinct, Vector(4L))
  assertEquals(deltas(0).properties("checkedOut"), true)
  val edge = deltas(2)
  assertEquals(
    (edge.edgeType, edge.from, edge.to),
    (Some("CHECKED_OUT"), Some("cart:c1"), Some("checkout:c1"))
  )
}

test("a discarded cart's deletion publishes its tombstone") {
  assertEquals(kit.onMessage(Discarded, subject = "c1", sequenceNumber = 2), Vector.empty)

  val deltas = kit.onDelete(subject = "c1", sequenceNumber = 3)
  assertEquals(
    deltas.map(d => (d.key, d.isTombstone, d.version)),
    Vector(("node:cart:c1", true, 3L))
  )
}
```

**Python**

```python
def test_the_cart_graph_follows_the_cart() -> None:
    graph = GraphConsumerTestKit.of(CartGraph)
    cart = Element("node", "node", "cart:c1", 1, ("Cart",), properties={"cartId": "c1", "checkedOut": False})
    # The elements a change leaves, at the change's sequence number: read back from what would be
    # on the topic, with nothing started.
    assert graph.on_message(ItemAdded(PEN), "c1", sequence=1) == [cart]
    assert graph.on_message(ItemRemoved("p1"), "c1", sequence=2) == [replace(cart, version=2)]
    assert graph.on_message(CheckedOut(), "c1", sequence=3) == [
        Element("node", "node", "cart:c1", 3, ("Cart",), properties={"cartId": "c1", "checkedOut": True}),
        Element("node", "node", "checkout:c1", 3, ("Checkout",), properties={"cartId": "c1"}),
        Element("edge", "edge", "checked-out:c1", 3, type="CHECKED_OUT", from_id="cart:c1", to_id="checkout:c1"),
    ]


def test_a_discarded_cart_is_marked_gone_by_its_deletion_and_comes_back_above_it() -> None:
    graph = GraphConsumerTestKit.of(CartGraph)
    assert graph.on_message(Discarded(), "c2", sequence=2) == []
    tombstone = graph.on_delete("c2", sequence=3)
    assert tombstone == [Element("tombstone", "node", "cart:c2", 3)]
    # The same id takes items again: its node is published above the tombstone.
    again = graph.on_message(ItemAdded(PEN), "c2", sequence=4)
    assert again[0].key == tombstone[0].key == "node:cart:c2" and again[0].version == 4
```

**TypeScript**

```ts
test("a checkout publishes the cart, its checkout and the edge between them, at the event's sequence number", async () => {
  const kit = GraphConsumerTestKit.of(CartGraph)
  const elements = await kit.onMessage({ type: "CheckedOut" }, { subject: "c1", sequence: 4 })
  assert.deepEqual(elements, [
    { kind: "node", id: "cart:c1", version: 4, labels: ["Cart"], properties: { cartId: "c1", checkedOut: true } },
    { kind: "node", id: "checkout:c1", version: 4, labels: ["Checkout"], properties: { cartId: "c1" } },
    { kind: "edge", id: "checked-out:c1", version: 4, type: "CHECKED_OUT", from: "cart:c1", to: "checkout:c1", properties: {} },
  ])
})
```

**Rust**

```rust
#[test]
fn the_carts_graph_follows_the_carts_events() {
    let kit = GraphConsumerTestKit::<CartGraph>::new();

    // The cart's first event: its node, whole, at version 1.
    let added = kit.on_message("cart-1", 1, ShoppingCartEvent::ItemAdded { item: pen(2) });
    assert_eq!(added.len(), 1);
    assert_eq!(added[0].key(), "node:cart:cart-1");
    assert_eq!(added[0].version(), Some(1));
    assert_eq!(added[0].labels(), ["Cart".to_string()]);
    assert_eq!(added[0].get("cartId"), Some(&Value::from("cart-1")));
    assert_eq!(added[0].get("checkedOut"), Some(&Value::Bool(false)));

    // A checkout, at the cart's fourth event: the cart again, its checkout, and the edge between.
    let checked_out = kit.on_message("cart-1", 4, ShoppingCartEvent::CheckedOut);
    let keys: Vec<String> = checked_out.iter().map(|e| e.key()).collect();
    assert_eq!(
        keys,
        [
            "node:cart:cart-1",
            "node:checkout:cart-1",
            "edge:checked-out:cart-1"
        ]
    );
    assert!(checked_out.iter().all(|e| e.version() == Some(4)));
    assert_eq!(checked_out[0].get("checkedOut"), Some(&Value::Bool(true)));
    assert_eq!(checked_out[1].labels(), ["Checkout".to_string()]);
    let edge = &checked_out[2];
    assert_eq!(edge.edge_type(), "CHECKED_OUT");
    assert_eq!((edge.from(), edge.to()), ("cart:cart-1", "checkout:cart-1"));

    // A discarded cart says nothing of itself; the deletion that follows marks its node deleted,
    // above everything the cart published before.
    assert!(
        kit.on_message("cart-2", 2, ShoppingCartEvent::Discarded)
            .is_empty()
    );
    let gone = kit.on_deleted("cart-2", 3);
    assert_eq!(gone[0].kind(), ElementKind::NodeTombstone);
    assert_eq!(gone[0].key(), "node:cart:cart-2");
    assert_eq!(gone[0].version(), Some(3));
}
```

| | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|
| The kit | `ConsumerTestKit.graph(Companion)` | `GraphConsumerTestKit.of(Cls)` | `GraphConsumerTestKit.of(Cls)` | `GraphConsumerTestKit::<G>::new()` |
| A change | `onMessage(message, subject, sequenceNumber)` | `on_message(message, subject, sequence=…)` | `onMessage(message, { subject, sequence })` | `on_message(subject, sequence, message)` |
| The deletion | `onDelete(subject, sequenceNumber)` | `on_delete(subject, sequence=…)` | `onDelete({ subject, sequence })` | `on_deleted(subject, sequence)` |
| A client that answers | a `TestTransport` with stubs, passed to the kit | a client double, passed to `of` | a client double, passed to `of` | `.with_service(build())`, which answers from in-memory entities |

To read a delta topic in a test or in another component, each SDK has the reader the kits use:
`GraphDelta.read(key, value)` in Scala, `graph.read(value, key)` in Python, `readDelta(value, key)` in
TypeScript and `graph::read(value, key)` in Rust. Given a key, the reader also checks that it is the
delta's element key. See [Testing](testing.md#testing-a-consumer).

## The topic

The topic a graph consumer publishes to must be **compacted** to hold the graph. A compacted topic keeps
the latest record under every key, and every delta's key is its element, so the topic holds each element's
latest state however long the service runs. A topic that deletes by age holds only recent history, and a
graph cannot be rebuilt from it.

ankka creates no topics and checks none. The topic belongs to whoever declares it, and the right owner is
the ankka-flow pipeline that reads it:

1. Declare the topic in the pipeline's blueprint as a managed topic, under the name the graph consumer
   publishes to. ankka-flow creates a managed delta topic compacted.
2. Deploy the pipeline before the service publishes.
3. Deploy the service with `ANKKA_KAFKA_BOOTSTRAP_SERVERS` set.

If the service publishes first and the broker creates topics on first use, the topic comes into being
uncompacted, with the broker's defaults. The pipeline then reports `TopicNotCompacted` and leaves the
topic as it is; compacting it is a matter of altering or recreating the topic.

## Where ankka's part ends

ankka publishes the deltas. Everything from the topic onward is ankka-flow's:

- [Build a graph sink](https://flow.ankka.cloud/build/graph-sink/): the pipeline that reads a delta topic
  into Neo4j.
- [Graph deltas](https://flow.ankka.cloud/reference/graph-deltas/): the contract, field by field, and
  what the sink does with each delta.
- [Rebuild a graph from its delta topic](https://flow.ankka.cloud/deploy/rebuild-a-graph/): fill an empty
  database from the topic alone, with the service untouched.

## What is not there

- **No check that an element has one writer.** Two consumers that publish the same element are not
  detected.
- **No check that an element is whole.** A delta built from part of its element's state is published as
  given.
- **No state source for an event sourced entity.** A graph consumer is handed the event and reads the
  state through the client.
- **No delete markers.** A tombstoned element's record stays in the topic.
- **No topic creation, and no check that the topic exists or is compacted.**
- **No tombstones for an expired entity.**
- **No atomic publication.** A change's records are published at least once and in order, not all or
  nothing; see [Consistency and delivery](../concepts/consistency.md#delivery-to-views-and-consumers).
