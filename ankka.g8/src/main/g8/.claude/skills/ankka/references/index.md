# ankka

> What ankka is, who it is for, the components a service is built from, and where in this documentation to start.

Source: https://docs.ankka.cloud/
ankka is a serverless application platform for agentic AI, built on the actor model. It reimplements
[Akka's](https://doc.akka.io/) component model in Scala 3 on [Apache Pekko](https://pekko.apache.org/),
the Apache 2.0 fork of Akka 2.6, so the programming model carries no licence constraint on who runs it.
You write components; ankka supplies the runtime. Sharding, persistence, replay, projections, durable
orchestration, timers, HTTP and the agent loop are the platform's problem, not yours.

ankka is for developers who build services that hold state, react to change, run long processes and
talk to language models, and who want to deploy and operate them without assembling that machinery
themselves. A service is written in Scala, in Python or TypeScript with the runtime running beside it as a
sidecar, or in Rust built to a WebAssembly module the runtime loads, and is deployed to a Kubernetes cluster
with the `ankka` command-line tool.

## What a service is made of

A service is a set of components, registered explicitly and hosted by the runtime:

| Component | What it is for | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|---|
| Event sourced entity | State derived by replaying the events it persisted | yes | yes | yes | yes |
| Key value entity | The latest value only, with no history | yes | yes | yes | yes |
| View | A queryable projection of another component's changes | yes | yes | yes | yes |
| Consumer | Reacting to changes, and optionally publishing onward | yes | yes | yes | yes |
| Workflow | A durable multi-step process that survives restarts | yes | yes | yes | yes |
| Timed action | A call the runtime makes later, on your behalf | yes | yes | yes | yes |
| Agent | A task carried out by talking to a model, with tools and memory | yes | yes | yes | yes |
| HTTP endpoint | The service's edge: routes, access control, request handling | yes | yes | yes | yes |

![The components of one ankka service and how they communicate: callers reach an HTTP endpoint; the endpoint, workflow steps, agent tools, consumers and timed actions all call components through the component client; agents, workflows and entities write to the service's Postgres journal or durable state; projections of those changes feed views and consumers; stored timers fire timed actions; agents call the model provider; views and consumers can read Kafka topics and consumers can publish to them.](assets/diagrams/components.svg)

[Components](concepts/components.md#how-components-communicate) describes each path in the picture.

A handler returns an effect, which is a description of what should happen. It performs no I/O itself:

**Scala**

```scala
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  def emptyState: ShoppingCart = ShoppingCart.empty(context.entityId)

  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

**Python**

```python
class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")

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
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)
```

**TypeScript**

```ts
export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
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
    }
  }

  addItem(item: LineItem) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
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
        effects::persist(ShoppingCartEvent::ItemAdded { item }).then_reply_value(Done)
    }
}

impl EventSourcedEntity for ShoppingCart {
    type State = Cart;
    type Event = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "shopping-cart";
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
        }
    }

    fn handlers() -> Handlers<ShoppingCart> {
        Handlers::new().command("add-item", ShoppingCart::add_item)
    }
}
```

The runtime interprets the effect: it writes the event to the journal, applies it to the state and
replies. Because building an effect does nothing, a component's decisions can be tested with nothing
running. [Effects are data](concepts/effects.md) explains the idea in full.

## Where to start

- **New to ankka.** [Install the tools](get-started/install.md), then build
  [your first service in Scala](get-started/first-service-scala.md),
  [in Python](get-started/first-service-python.md),
  [in TypeScript](get-started/first-service-typescript.md) or
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
