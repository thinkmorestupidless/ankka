# Handlers and wire names

> How ankka names components, handlers and stored types independently of your code's names, why those names are a versioning boundary, and which renames are safe in a running system.

Source: https://docs.ankka.cloud/concepts/wire-names/
Everything the platform stores or routes by has a name you declare as a string, separate from the name of
the class or method in your code. A component has a **component id**, each handler has a **wire name**, and
each stored type has a **manifest**. Those strings are protocol: they are written into the database and
used to address calls. The code names are yours to change at any time.

## Declaring handlers

A handler is declared under its wire name, beside the method that implements it.

**Scala**

```scala
object ShoppingCartEntity
    extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
      componentId = ComponentId("shopping-cart"),
      stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
      eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
    ):
  def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)

  val addItem = command("add-item")(_.addItem)
  val getCart = query("get-cart")(_.getCart)
```

**Java**

```java
@Command("add-item")
public Effect<Done> addItem(LineItem item) {
  if (currentState().checkedOut()) return effects().error(checkedOut());
  if (item.quantity() <= 0)
    return effects().error("quantity must be greater than zero, was " + item.quantity());
  return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
}

@Command("remove-item")
public Effect<Done> removeItem(String productId) {
  if (currentState().checkedOut()) return effects().error(checkedOut());
  if (!currentState().contains(productId))
    return effects().error(Problem.notFound("cart does not contain '" + productId + "'"));
  return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
}
```

**Kotlin**

```kotlin
/** A customer's cart, one per cart id. Its handlers' wire names are the Scala cart's. */
@ComponentId("shopping-cart")
class ShoppingCart : EventSourcedEntity<Cart, CartEvent>() {
    override fun emptyState() = Cart.empty(context().entityId())

    // A suspend fun, as any handler may be: it waits without holding a thread.
    @Command("add-item")
    suspend fun addItem(item: LineItem): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            item.quantity <= 0 -> error("quantity must be greater than zero, was ${item.quantity}")
            else -> persist(CartEvent.ItemAdded(item)).thenDone()
        }
    }

    @Command("remove-item")
    fun removeItem(productId: String): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            !currentState().contains(productId) -> error(Problem.notFound("cart does not contain '$productId'"))
            else -> persist(CartEvent.ItemRemoved(productId)).thenDone()
        }
    }

    @Command("checkout")
    fun checkout(): Effect<Cart> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            currentState().items.isEmpty() -> error("cannot check out an empty cart")
            else -> persist(CartEvent.CheckedOut).thenReplyState()
        }
    }

    @Command("discard")
    fun discard(): Effect<Done> = effects {
        if (currentState().checkedOut) error(checkedOut) else persist(CartEvent.Discarded).thenDelete().thenDone()
    }

    @Query("get-cart")
    fun getCart(): ReadOnlyEffect<Cart> = effects { reply(currentState()) }

    @Query("total-quantity")
    fun totalQuantity(): ReadOnlyEffect<Int> = effects { reply(currentState().totalQuantity) }

    override fun applyEvent(state: Cart, event: CartEvent): Cart =
        when (event) {
            is CartEvent.ItemAdded -> state.addItem(event.item)
            is CartEvent.ItemRemoved -> state.removeItem(event.productId)
            CartEvent.CheckedOut -> state.copy(checkedOut = true)
            CartEvent.Discarded -> state
        }

    private val checkedOut: Problem
        get() = Problem.conflict("cart is already checked out")
}
```

**Python**

```python
class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]: ...

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]: ...
```

**TypeScript**

```ts
export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
  }
}
```

**Rust**

```rust
/// Every handler under its wire name: the name a caller and a stored timer use, which does
/// not change when the Rust function is renamed.
fn handlers() -> Handlers<ShoppingCart> {
    Handlers::new()
        .command("add-item", ShoppingCart::add_item)
        .command("remove-item", ShoppingCart::remove_item)
        .command("checkout", ShoppingCart::checkout)
        .command("discard", ShoppingCart::discard)
        .query("get-cart", ShoppingCart::get_cart)
        .query("total-quantity", ShoppingCart::total_quantity)
}
```

**Go**

```go
// Entity is the shopping cart: the same wire names and manifests as the Scala sample's. The
// string each handler is registered under is its wire name, separate from the Go function's.
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
	// A JSON codec derived from the type, under the manifest the journal records it by.
	ankka.StateCodec(ankka.CodecFor[Cart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
	ankka.SnapshotEvery(3),
)
```


In Scala the declaration is a value on the companion object, and a call site uses that value. The
call is fully typed — the argument and the reply type come from the declaration — and involves no
reflection. There is no classpath scanning and no inspection of method names or bytecode. In Python a call
names the component id and the wire name as strings:

**Scala**

```scala
val added = componentClient
  .forEventSourcedEntity(EntityId(id))
  .call(ShoppingCartEntity.addItem)
  .invoke(LineItem("p1", "Widget", 2))
```

**Java**

```java
public static CartState addAndRead(ComponentClient client) {
  var cart = client.forEventSourcedEntity("cart-1");

  cart.call(ShoppingCart::addItem).invoke(new LineItem("p1", "Widget", 2)); // Done
  return cart.call(ShoppingCart::getCart).invoke(); // CartState
}
```

**Kotlin**

```kotlin
val cart = componentClient.forEventSourcedEntity("cart-1")
cart.call(ShoppingCart::addItem).invokeSuspending(LineItem("p1", "Widget", 2))
val held: Cart = cart.call(ShoppingCart::getCart).invokeSuspending()
```

**Python**

```python
await client.for_event_sourced_entity("shopping-cart", cart_id).call("add-item").invoke(item, reply=Done)
```

**TypeScript**

```ts
addItem: post("/{cartId}/items", LineItem, Done, (ep: ShoppingCartEndpoint, req, item) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)),
```

**Rust**

```rust
/// `/carts/{cartId}`, `/total`, `/items`, `/items/{productId}` and `/checkout`, and `DELETE
/// /carts/{cartId}`, open to anyone.
pub struct CartApi;

impl CartApi {
    fn get_cart(request: &Request) -> Result<Cart, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "get-cart", ())?)
    }

    fn total(request: &Request) -> Result<i32, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "total-quantity", ())?)
    }

    fn add_item(request: &Request, item: LineItem) -> Result<Done, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "add-item", item)?)
    }

    fn remove_item(request: &Request) -> Result<Done, HttpProblem> {
        let (cart_id, product_id) = (request.path("cartId"), request.path("productId"));
        let removed = request.client().invoke(
            ShoppingCart,
            cart_id,
            "remove-item",
            product_id.to_string(),
        )?;
        Ok(removed)
    }

    fn checkout(request: &Request, (): ()) -> Result<Cart, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "checkout", ())?)
    }

    fn discard(request: &Request) -> Result<Done, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "discard", ())?)
    }
}
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

Because Scala and Python address a handler by the same component id and wire name, and store data under
the same manifests, a service can be ported from one language to the other without its callers or its
stored data noticing.

## Why the wire name is not the method name

A method name is a detail of the code. A wire name is a promise to everything outside it:

- **During a rolling update**, old and new instances run side by side and call each other. A call sent by
  an old instance names the handler as the old code knew it. If the name came from the method, renaming a
  method would make every such call fail until the update finished.
- **Timers are stored.** A timer holds the component id and the handler's wire name of the call it will
  make, possibly hours or days later. A handler renamed in the meantime would leave the timer calling a name
  that no longer exists, and it would be retried forever.
- **Other services and other languages** address handlers by name, and cannot see your code at all.

Declaring the name separately makes the boundary visible. Renaming a method is a refactoring; changing a
string in a `command(...)` or `@command(...)` is a protocol change, and it looks like one in review.

## What each name is used for

| Name | Declared as | Used for |
|---|---|---|
| Component id | `ComponentId("shopping-cart")`, `component_id = "shopping-cart"` | addressing calls; the journal's entity type; view table names; timers; traces |
| Wire name | `command("add-item")`, `@command("add-item")`, and likewise `query`, workflow `step`, timed action `handler` | addressing one handler; timers; traces |
| Manifest | `Codecs.serializer[T]("shopping-cart-event")`, `json_codec(T, "shopping-cart-event")` | stored with every event, snapshot, state and row, to say how to read it |
| Route path | `get("/{cartId}")`, `@get("/{cartId}")` | the service's public HTTP API |

## What is safe to change

| Change | Safe? | Why |
|---|---|---|
| Rename a Scala method, Python method, class or package | yes | nothing outside the code knows these names |
| Add a handler, a component or a route | yes | nothing addresses it yet |
| Remove a handler nothing calls any more | yes, once no caller or timer uses it | a stored timer that names it will fail on every attempt |
| Change a handler's wire name | no | in-flight calls, stored timers and other services use the old name |
| Change a component id | no | the journal, snapshots, view tables and timers are keyed by it; the component would start empty |
| Change a manifest | no | stored data carries the old manifest and could no longer be read |
| Change a route path | a breaking API change | callers outside the service use it |

To change a name that is not safe to change, add the new one beside the old, move callers across, and
remove the old one only when nothing can still use it: no instance running old code, no timer still
scheduled, no caller outside the service. For stored types, see
[Serialization and evolution](../build/serialization.md).

## Names are lowercase and hyphenated by convention

ankka does not require a style for wire names and component ids, but every sample uses lowercase words
joined by hyphens: `shopping-cart`, `add-item`, `get-cart`. Traces and the local console show a call as
`<component id>#<wire name>`, for example `shopping-cart#add-item`, so consistent names read well there.
A service's own name, in its descriptor, does have rules, because it becomes a Kubernetes name and part of a
hostname: lowercase letters, digits and hyphens, starting with a letter, at most 63 characters.
