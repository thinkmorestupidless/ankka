# Contract: the `ankka` crate's public surface

What a developer writes against. Names are stable across the feature; bodies are the implementation's.

## Declaring a component

```rust
use ankka::prelude::*;

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct Cart { pub items: Vec<Item> }

#[derive(Serialize, Deserialize, Clone)]
#[serde(tag = "type")]
pub enum CartEvent { ItemAdded { item: Item }, CheckedOut {} }

pub struct ShoppingCart;

impl EventSourcedEntity for ShoppingCart {
    type State = Cart;
    type Event = CartEvent;
    const COMPONENT_ID: &'static str = "shopping-cart";
    const SHAPE: Shape = Shape::Stateless;               // or Shape::Stateful

    fn empty_state() -> Cart { Cart::default() }

    fn apply(state: Cart, event: &CartEvent) -> Cart { /* the fold */ }

    fn handlers() -> Handlers<Self> {
        Handlers::new()
            .command("add-item", Self::add_item)          // (&Cart, Item, &Context) -> Effect<CartEvent, Done>
            .query("get-cart", Self::get_cart)            // (&Cart, (), &Context) -> ReadOnlyEffect<Cart>
    }
}
```

- `command` accepts a handler returning `Effect<E, R>`; `query` accepts only `ReadOnlyEffect<R>`.
  A query returning `Effect` does not compile.
- `Handlers::new()` refuses a duplicate wire name at registration, reported with every other
  problem by `Service::build`.
- `KeyValueEntity`, `Workflow` (with `steps()` and `step("name", f)`), `View`, `Consumer`,
  `TimedAction`, `Agent` (with `tools()` and `guardrails()`) and `Endpoint` (with `routes()`:
  `get`, `post`, … each with an `Acl`) follow the same pattern.
- `Shape` applies to `EventSourcedEntity`, `KeyValueEntity` and `Workflow`; the default is
  `Stateless`.

## Effects

`effects::persist(event).then_reply(|state| …)`, `.then_reply_value(v)`, `.delete_entity()`,
`.expire_after(d)`, `effects::reply(v)`, `effects::error(code, message)`; key value:
`effects::update_state(s)`; workflow: `step_effects::update_state(s).then_transition_to("step",
input)`, `.then_pause()`, `.then_end()`, `.fail(...)`; agent: `effects::system_message(..).user_message(..)
.tools([..]).guardrails([..]).then_reply()`. All are values; `Effect` and `ReadOnlyEffect` are distinct
types. The library's own reduction (`materialise`) is what the unit testkit shows and what the
`HandleReply` carries, so the two cannot disagree.

## The service and the exports

```rust
// lib.rs of the service crate
fn build() -> Service {
    Service::new("ankka-rust")
        .register::<ShoppingCart>()
        .register::<CartRows>()
        .endpoint::<CartApi>()
}
ankka::service!(build);                    // emits every ankka1_ export and the panic hook
```

`Service::build` validates the whole registry (duplicate ids and wire names, an endpoint without an
ACL, a streaming route) and reports every problem in the discovery reply; the runtime refuses to start
and logs them.

## The client and configuration

```rust
let cart: Cart = ctx.client().invoke::<ShoppingCart, _>("cart-1", "get-cart", ())?;
let rows: Vec<Row> = ctx.client().query::<CartRows, _>("by-owner", owner)?;
ctx.client().schedule("reminder-1", Duration::minutes(5), Notify::COMPONENT_ID, "remind", payload)?;
let key = ankka::config("MY_SETTING");      // None for a reserved or unset name
```

Every call blocks the calling handler and is answered by the runtime through the ABI's imports.

## Codec

`serde::Serialize + DeserializeOwned` with the encoding's rules, through `ankka::codec::Json<T>`
(the default for records and sum types) and `ankka::codec::Text` for `String`, integers, floats,
booleans. `ankka::Instant` and `ankka::Duration` render as the encoding requires. A custom codec is
`impl Codec for MyType`. Manifests default to the type's simple name and can be set.

## Testkits

```rust
// unit (native, no module, no Docker)
let kit = EventSourcedTestKit::<ShoppingCart>::new("cart-1");
let outcome = kit.command("add-item", item.clone());
assert_eq!(outcome.events, vec![CartEvent::ItemAdded { item }]);
assert_eq!(outcome.reply::<Done>()?, Done);
assert_eq!(kit.state().items.len(), 1);

// integration (feature "testkit"; Docker)
let rt = AnkkaTestKit::start(Module::build()?)?;     // Postgres + the sidecar image, module bind-mounted
let r = rt.http().post("/carts/cart-1/items").json(&item).send()?;
rt.restart()?;                                          // a new runtime on the same database
```

`Module::build()` runs `cargo build --release --target wasm32-unknown-unknown` for the current
package; `Module::at(path)` takes a built one. The kit takes the sidecar image from
`ANKKA_SIDECAR_IMAGE`, else `ghcr.io/thinkmorestupidless/ankka-sidecar:<the crate's version>`, else
`ankka-sidecar:latest` for the `0.0.0` tree, as the other testkits do.

## Conformance

`examples/shopping-cart` carries the reference components under `src/conformance.rs`, declared in
the example's `build()`; their shape is read from `ankka::config("ANKKA_CONFORMANCE_SHAPE")`.
`sdks/rust/conformance.sh` builds the module and runs:

```bash
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=wasm:$MODULE                      # stateless
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=wasm:$MODULE -Dankka.conformance.shape=stateful
```

`ANKKA_CONFORMANCE_ONLY` narrows either.
