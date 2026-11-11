# ankka

> What ankka is, who it is for, the components a service is built from, and where in this documentation to start.

Source: https://docs.ankka.cloud/
ankka is a serverless application platform for agentic AI, built on the actor model: Scala 3 on
[Apache Pekko](https://pekko.apache.org/), Apache 2.0 throughout, so the programming model carries no
licence constraint on who runs it.
You write components; ankka supplies the runtime. Sharding, persistence, replay, projections, durable
orchestration, timers, HTTP and the agent loop are the platform's problem, not yours.

ankka is for developers who build services that hold state, react to change, run long processes and
talk to language models, and who want to deploy and operate them without assembling that machinery
themselves. A service is written in Scala, Java or Kotlin in the runtime's own JVM, in Python, TypeScript or Go with
the runtime running beside it as a sidecar, or in Rust built to a WebAssembly module the runtime loads, and is deployed to a Kubernetes cluster
with the `ankka` command-line tool.

## What a service is made of

A service is a set of components, registered explicitly and hosted by the runtime:

| Component | What it is for | Scala | Java | Kotlin | Python | TypeScript | Go | Rust |
|---|---|---|---|---|---|---|---|---|
| Event sourced entity | State derived by replaying the events it persisted | yes | yes | yes | yes | yes | yes | yes |
| Key value entity | The latest value only, with no history | yes | yes | yes | yes | yes | yes | yes |
| View | A queryable projection of another component's changes | yes | yes | yes | yes | yes | yes | yes |
| Consumer | Reacting to changes, and optionally publishing onward | yes | yes | yes | yes | yes | yes | yes |
| Workflow | A durable multi-step process that survives restarts | yes | yes | yes | yes | yes | yes | yes |
| Timed action | A call the runtime makes later, on your behalf | yes | yes | yes | yes | yes | yes | yes |
| Agent | A task carried out by talking to a model, with tools and memory | yes | not yet | not yet | yes | yes | yes | yes |
| HTTP endpoint | The service's edge: routes, access control, request handling | yes | yes | yes | yes | yes | yes | yes |

![The components of one ankka service and how they communicate: callers reach an HTTP endpoint; the endpoint, workflow steps, agent tools, consumers and timed actions all call components through the component client; agents, workflows and entities write to the service's Postgres journal or durable state; projections of those changes feed views and consumers; stored timers fire timed actions; agents call the model provider; views and consumers can read Kafka topics and consumers can publish to them.](assets/diagrams/components.svg)

[Components](concepts/components.md#how-components-communicate) describes each path in the picture.

A handler returns an effect, which is a description of what should happen. It performs no I/O itself:

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

**Java**

```java
/** A customer's cart, one per cart id. Its handlers' wire names are the Scala cart's. */
@ComponentId("shopping-cart")
public class ShoppingCart extends EventSourcedEntity<Cart, CartEvent> {

  @Override
  protected Cart emptyState() {
    return Cart.empty(context().entityId());
  }

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

  @Command("checkout")
  public Effect<Cart> checkout() {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (currentState().isEmpty()) return effects().error("cannot check out an empty cart");
    return effects().persist(new CartEvent.CheckedOut()).thenReplyState();
  }

  /**
   * Records the discard and then deletes the cart, so the same id starts again empty. The event is
   * persisted before the deletion takes effect, so a consumer or view downstream still observes
   * that this cart was discarded rather than merely vanishing.
   */
  @Command("discard")
  public Effect<Done> discard() {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    return effects().persist(new CartEvent.Discarded()).thenDelete().thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<Cart> getCart() {
    return effects().reply(currentState());
  }

  @Query("total-quantity")
  public ReadOnlyEffect<Integer> totalQuantity() {
    return effects().reply(currentState().totalQuantity());
  }

  @Override
  protected Cart applyEvent(Cart state, CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded added -> state.addItem(added.item());
      case CartEvent.ItemRemoved removed -> state.removeItem(removed.productId());
      case CartEvent.CheckedOut ignored -> state.onCheckedOut();
      case CartEvent.Discarded ignored -> state;
    };
  }

  private static Problem checkedOut() {
    return Problem.conflict("cart is already checked out");
  }
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

  /**
   * Records the discard and then deletes the cart, so the same id starts again empty.
   *
   * The event is persisted before the deletion takes effect, so a consumer or view downstream still
   * observes that this cart was discarded rather than merely vanishing. A checked-out cart is a
   * record of an order and is not discarded.
   */
  discard() {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    return this.effects.persist({ type: "Discarded" }).deleteEntity().thenReply(() => done)
  }
}
```

**Rust**

```rust
pub struct ShoppingCart;

impl ShoppingCart {
    fn add_item(cart: &Cart, item: LineItem, _: &Context) -> Effect<ShoppingCartEvent, Done> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        if item.quantity <= 0 {
            let message = format!("quantity must be greater than zero, was {}", item.quantity);
            return effects::error(ErrorCode::BadRequest, message).into();
        }
        effects::persist(ShoppingCartEvent::ItemAdded { item }).then_reply_value(Done)
    }

    fn remove_item(
        cart: &Cart,
        product_id: String,
        _: &Context,
    ) -> Effect<ShoppingCartEvent, Done> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        if !cart.contains(&product_id) {
            let message = format!("cart does not contain '{product_id}'");
            return effects::error(ErrorCode::NotFound, message).into();
        }
        effects::persist(ShoppingCartEvent::ItemRemoved { product_id }).then_reply_value(Done)
    }

    fn checkout(cart: &Cart, _: (), _: &Context) -> Effect<ShoppingCartEvent, Cart> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        if cart.is_empty() {
            return effects::error(ErrorCode::BadRequest, "cannot check out an empty cart").into();
        }
        // As the Scala cart: a checked-out cart is kept, the record of what was ordered, and every
        // later change to it is refused.
        effects::persist(ShoppingCartEvent::CheckedOut).then_reply(|cart: &Cart| cart.clone())
    }

    /// Records the discard and then deletes the cart, so the same id starts again empty. A
    /// checked-out cart is a record of an order and is not discarded.
    fn discard(cart: &Cart, _: (), _: &Context) -> Effect<ShoppingCartEvent, Done> {
        if cart.checked_out {
            return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
        }
        // The event is persisted, then the cart deleted, so a consumer downstream still sees the
        // discard rather than a cart that vanished; the same id then starts again empty.
        effects::persist(ShoppingCartEvent::Discarded)
            .delete_entity()
            .then_reply_value(Done)
    }

    fn get_cart(cart: &Cart, _: (), _: &Context) -> ReadOnlyEffect<Cart> {
        effects::reply(cart.clone())
    }

    fn total_quantity(cart: &Cart, _: (), _: &Context) -> ReadOnlyEffect<i32> {
        effects::reply(cart.total_quantity())
    }
}

impl EventSourcedEntity for ShoppingCart {
    type State = Cart;
    type Event = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "shopping-cart";
    // The manifests the Scala cart stores under: what makes the journal shared.
    const STATE_MANIFEST: Option<&'static str> = Some("shopping-cart");
    const EVENT_MANIFEST: Option<&'static str> = Some("shopping-cart-event");

    fn empty_state(cart_id: &str) -> Cart {
        Cart::empty(cart_id)
    }

    fn apply(cart: Cart, event: &ShoppingCartEvent) -> Cart {
        match event {
            ShoppingCartEvent::ItemAdded { item } => cart.add_item(item.clone()),
            ShoppingCartEvent::ItemRemoved { product_id } => cart.remove_item(product_id),
            ShoppingCartEvent::CheckedOut => cart.on_checked_out(),
            // The cart is deleted straight after; the event is there for what reads the journal.
            ShoppingCartEvent::Discarded => cart,
        }
    }

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

    fn snapshot_every() -> u32 {
        100
    }
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


var checkedOut = ankka.Refuse(ankka.Conflict, "cart is already checked out")

func done(Cart) ankka.Done { return ankka.Done{} }


// addItem describes what should happen: a refusal is a returned error and nothing is persisted; an
// accepted item is an event the runtime persists before it replies.
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


// discard records the discard and then deletes the cart, so the same id starts again empty. The
// event is persisted before the deletion takes effect, so a consumer or view downstream still
// observes that this cart was discarded rather than merely vanishing. A checked-out cart is a
// record of an order and is not discarded.
func discard(_ context.Context, c Cart, _ ankka.Unit) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	return ankka.Persist[Cart, Event, ankka.Done](Discarded{}).DeleteEntity().ThenReply(done), nil
}
```

The runtime interprets the effect: it writes the event to the journal, applies it to the state and
replies. Because building an effect does nothing, a component's decisions can be tested with nothing
running. [Effects are data](concepts/effects.md) explains the idea in full.

## Where to start

- **Building a feature.** [Design, then build](get-started/design-and-build.md): describe it, answer
  the design's questions, and approve the pull request the build raises.
- **New to ankka.** [Install the tools](get-started/install.md), then build
  [your first service in Scala](get-started/first-service-scala.md),
  [in Java](get-started/first-service-java.md),
  [in Kotlin](get-started/first-service-kotlin.md),
  [in Python](get-started/first-service-python.md),
  [in TypeScript](get-started/first-service-typescript.md),
  [in Go](get-started/first-service-go.md) or
  [in Rust](get-started/first-service-rust.md), and
  [deploy it to a local platform](get-started/deploy-locally.md).
- **Designing a system.** [How ankka works](concepts/architecture.md) is the overview, and
  [Designing a service](concepts/designing-services.md) maps requirements onto components.
- **Building components.** The build guides start with
  [event sourced entities](build/event-sourced-entities.md), and each shows Scala and Python side by side.
- **Deploying.** [Deploy a service](deploy/deploy-a-service.md) covers the descriptor and the CLI;
  [Expose a service](deploy/expose.md) puts it on the internet.
- **Operating.** [The local console](operate/local-console.md) shows traces on your own machine, and
  [Troubleshooting](operate/troubleshooting.md) collects the problems people hit.
- **Running the platform.** [Install a local platform](platform/install-local.md) sets up a kind cluster
  with everything a service needs.
- **Looking something up.** The [CLI reference](reference/cli.md),
  [service descriptor](reference/service-descriptor.md) and [glossary](reference/glossary.md).
- **Checking what is missing.** [Limitations](reference/limitations.md) lists what ankka does not do yet.

## For models and agents

This documentation is published in forms a model can read directly. `llms.txt` at the site root lists
every page with a one-sentence description, `llms-full.txt` holds every page in one file, and each
page is also served as Markdown at its own path with a `.md` suffix. The same pages ship as agent
skills, one per kind of task: in the Claude Code plugin published to the ankka marketplace, and inside
every project created from the service template. `ankka mcp` serves the platform to an agent as MCP tools: the CLI's service commands,
the services running on your machine, and this documentation.
[Work with a coding agent](get-started/coding-agents.md) sets both up.
