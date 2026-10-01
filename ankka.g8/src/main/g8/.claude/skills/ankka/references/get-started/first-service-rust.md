# Your first service in Rust

> Write a Rust service with an event sourced entity and an HTTP endpoint, build it to a WebAssembly module, run it in the ankka runtime, test it with and without the runtime, and watch it in the local console.

Source: https://docs.ankka.cloud/get-started/first-service-rust/
This tutorial writes a shopping cart in Rust, builds it to a WebAssembly module, runs it in the ankka
runtime on your machine, tests it, and looks at it in the local console. You need Rust through `rustup`
with the `wasm32-unknown-unknown` target, Docker, and the `ankka-sidecar` image from
[Install the tools](install.md). No JVM of your own.

A Rust service is built to a **WebAssembly module**, and ankka's runtime loads the module into its own
process. The runtime owns everything stateful and distributed — the journal, sharding, projections,
timers, HTTP and the agent loop. Your module owns the decisions: given this command and this state, what
should happen. The module reaches nothing but the runtime: no network, no file system, no clock of its
own. [Services in other languages](../concepts/polyglot.md#services-as-webassembly-modules) explains the
model.

## Create the project

This page builds the project one file at a time, so that each part is explained. To start from a complete
project instead, run `ankka init cart --language rust`: it writes an entity, a view, an endpoint, tests at
both levels, a `docker-compose.yml` that starts Postgres and the runtime with your module, a Dockerfile,
the descriptor and GitHub workflows, and its README says how to run each. To follow this page, start
empty:

```bash
cargo new --lib cart && cd cart
rustup target add wasm32-unknown-unknown
```

`Cargo.toml` declares the crate as both a module and a library, so the same code is built for the runtime
and tested natively:

```toml
[package]
name = "cart"
version = "0.1.0"
edition = "2024"

[lib]
crate-type = ["cdylib", "rlib"]            # cdylib: the module; rlib: the tests

[dependencies]
ankka = "0.8.0"                            # the version of the platform you will deploy to
serde = { version = "1", features = ["derive"] }

[dev-dependencies]
ankka = { version = "0.8.0", features = ["testkit"] }

[features]
slow = []                                  # the tests that start Docker

[profile.release]
opt-level = 3
lto = true
codegen-units = 1
panic = "abort"                            # a panic is a trap the runtime reports
strip = true
```

And `.cargo/config.toml`, which names the module build and sizes the module's stack:

```toml
[alias]
module = "build --release --target wasm32-unknown-unknown"

[target.wasm32-unknown-unknown]
rustflags = ["-C", "link-arg=-zstack-size=262144"]
```

`cargo module` is the build the runtime loads; a plain `cargo test` still builds for your machine. The
stack is a quarter of Rust's default because the runtime builds an instance of the module for some calls,
and a smaller stack makes each one cheaper.

## The domain

The cart's state and events are plain Rust types with serde's derives. Create `src/domain.rs`:

```rust
/// One product in a cart.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct LineItem {
    #[serde(rename = "productId")]
    pub product_id: String,
    pub name: String,
    pub quantity: i32,
}

/// A cart: what its events fold to.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ShoppingCart {
    #[serde(rename = "cartId")]
    pub cart_id: String,
    pub items: Vec<LineItem>,
    #[serde(rename = "checkedOut")]
    pub checked_out: bool,
}

impl ShoppingCart {
    /// A cart with nothing in it.
    pub fn empty(cart_id: &str) -> ShoppingCart {
        ShoppingCart {
            cart_id: cart_id.to_string(),
            items: Vec::new(),
            checked_out: false,
        }
    }

    /// Adds a line, folding the quantity into an existing line for the same product.
    pub fn add_item(mut self, item: LineItem) -> ShoppingCart {
        let merged = match self.items.iter().find(|i| i.product_id == item.product_id) {
            Some(existing) => LineItem {
                quantity: existing.quantity + item.quantity,
                ..item
            },
            None => item,
        };
        self.items.retain(|i| i.product_id != merged.product_id);
        self.items.push(merged);
        // Sorted, as the other carts sort: two carts holding the same products are equal
        // whatever the order they were added in.
        self.items.sort_by(|a, b| a.product_id.cmp(&b.product_id));
        self
    }

    pub fn remove_item(mut self, product_id: &str) -> ShoppingCart {
        self.items.retain(|i| i.product_id != product_id);
        self
    }

    pub fn on_checked_out(self) -> ShoppingCart {
        ShoppingCart {
            checked_out: true,
            ..self
        }
    }

    pub fn contains(&self, product_id: &str) -> bool {
        self.items.iter().any(|i| i.product_id == product_id)
    }

    pub fn total_quantity(&self) -> i32 {
        self.items.iter().map(|i| i.quantity).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }
}
```

```rust
/// Everything that can happen to a cart, stored as `{"type":"ItemAdded",…}` as every language
/// stores it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(tag = "type")]
pub enum ShoppingCartEvent {
    ItemAdded {
        item: LineItem,
    },
    ItemRemoved {
        #[serde(rename = "productId")]
        product_id: String,
    },
    CheckedOut,
    Discarded,
}
```

**Field names are the stored format.** The codec writes `productId` because the field says so; a Scala,
Python or TypeScript service reading the same journal expects the same names, so keep them once data
exists. A sum type is an enum with `#[serde(tag = "type")]`, which writes the case's name as the `"type"`
field every ankka language stores; the codec refuses an enum without it, naming the attribute.

## An entity

An event sourced entity keeps its state as the fold of the events it persisted. Create `src/entity.rs`:

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

Five things in this file are true of every ankka component in Rust:

- **A component is a unit struct and a trait.** `ShoppingCart` holds nothing; the trait's associated
  types and constants say what it is, and its handlers are ordinary functions.
- **A handler returns an effect.** `effects::persist(...).then_reply_value(Done)` is a description of what
  should happen. The handler performs no I/O, which is why it can be tested with nothing running.
- **The wire name is the first argument of `command` and `query`.** `"add-item"` is what the platform
  stores and routes by. Renaming the function changes nothing; renaming `"add-item"` is a protocol change.
- **A query cannot persist.** `query` accepts only a handler returning `ReadOnlyEffect`. Pass one that
  returns `Effect` and the crate does not compile.
- **The manifests name the stored types.** `STATE_MANIFEST` and `EVENT_MANIFEST` are what the journal
  records the values under; without them a type is stored under its own name.

## An endpoint

An endpoint is the service's HTTP edge. Create `src/endpoint.rs`; an excerpt of the example's, the routes
this page uses:

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

A route's handler takes the request, and for `post`, `put` and `patch` the body decoded as the type of its
second argument. It answers a value, encoded as the reply; `Done` answers 204. A refusal from the entity
arrives as a `CommandError`, and `?` turns it into the HTTP status its code stands for. The routes and the
endpoint's access rule are declared by the `Endpoint` trait:

```rust
impl Endpoint for CartApi {
    const ENDPOINT_ID: &'static str = "ShoppingCartEndpoint";
    const PREFIX: &'static str = "/carts";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<CartApi> {
        Routes::new()
            .get("/{cartId}", CartApi::get_cart)
            .get("/{cartId}/total", CartApi::total)
            .post("/{cartId}/items", CartApi::add_item)
            .delete("/{cartId}/items/{productId}", CartApi::remove_item)
            .post("/{cartId}/checkout", CartApi::checkout)
            .delete("/{cartId}", CartApi::discard)
            // A literal beside a parameter: the router must prefer it over `/{cartId}`.
            .get("/awkward", |_: &Request| Ok("literal".to_string()))
            .get("/{cartId}/rows", CartApi::row)
            .get("/rows", CartApi::rows)
            .post("/{cartId}/checkouts", CartApi::start_checkout)
            .get("/{cartId}/checkouts", CartApi::checkout_status)
            .post("/ask/{session}", CartApi::ask)
            .get("/{cartId}/checkout-log", CartApi::checkout_log)
    }
}
```

`acl` is required: an endpoint without one does not compile, because an access rule nobody chose is not a
default worth having. `Acl::AllowAll` is fine on your machine; decide it deliberately before the service is
exposed, as [HTTP endpoints](../build/http-endpoints.md) shows. `.with_acl(...)` after a route gives that
route its own. A module never binds an HTTP port: the runtime serves the routes you declared, applies the
access rule, and hands each request to the module.

## Register it

Registration is explicit: a component that is not registered does not exist. In `src/lib.rs`, list them and
emit the module's exports once:

```rust
/// Everything the module hosts, registered by value; `service!` emits the exports once.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(entity::ShoppingCart)
        .register(cart_rows::CartRows)
        .register(checkout_workflow::CheckoutWorkflow)
        .register(checkout_notifier::CheckoutNotifier)
        .register(checkout_log::CheckoutLog)
        .register(assistant::CartAssistant)
        .endpoint(endpoint::CartApi)
}

#[cfg(not(feature = "conformance"))]
ankka::service!(build);
```

The example registers more than this page builds — a view, a workflow, a consumer, an agent. A service of
your own lists what it has. `ankka::service!` writes every function the runtime calls, and installs the
hook that sends a panic's message to the runtime's log before the module traps.

## Run it

Build the module, then start Postgres and the runtime with it, from the root of the ankka repository:

```bash
cargo module                                                        # in your project
ANKKA_WASM_MODULE_PATH=/path/to/cart/target/wasm32-unknown-unknown/release/cart.wasm \
  docker compose --profile wasm up -d                               # in the ankka repository
```

In another terminal:

```bash
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}
```

Stop the runtime and start it again, then read the cart: it is still there. The journal belongs to the
runtime and lives in Postgres, under the same records a Scala service writes. After a change, `cargo module`
again and `docker compose --profile wasm restart runtime` loads the new module. A project made with
`ankka init --language rust` carries its own compose file, which mounts its own module: there,
`cargo module && docker compose up -d runtime` is the whole of it.

## Test it without the runtime

The unit testkit runs a component natively, with no runtime and no module. In `tests/cart.rs`:

```rust
#[test]
fn adding_an_item_persists_it_and_the_cart_holds_it() {
    let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-1");
    let outcome = kit.command("add-item", pen(2));
    assert_eq!(
        outcome.events,
        vec![ShoppingCartEvent::ItemAdded { item: pen(2) }]
    );
    assert_eq!(outcome.reply::<Done>(), Ok(Done));
    assert_eq!(kit.state().items, vec![pen(2)]);

    // The same product again folds into one line.
    kit.command("add-item", pen(1));
    assert_eq!(kit.state().items, vec![pen(3)]);
}

#[test]
fn a_refused_command_persists_nothing() {
    let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-1");
    let refused = kit.command("add-item", pen(0));
    assert!(refused.events.is_empty());
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::BadRequest));
    assert!(kit.state().items.is_empty());
}
```

```bash
cargo test
```

These take milliseconds. Inputs, events, state and replies still round-trip through the codecs, so a type
the encoding cannot express fails here rather than on first deployment. An endpoint is tested the same
way, with the service's entities answering its calls in memory:

```rust
#[test]
fn the_api_answers_from_the_cart_behind_it() {
    let kit = EndpointTestKit::<CartApi>::with_service(shopping_cart::build());
    assert_eq!(kit.post("/carts/c1/items", &pen(2)).status, 204);
    assert_eq!(kit.post("/carts/c1/items", &ink()).status, 204);
    let cart: Cart = kit.get("/carts/c1").json().unwrap();
    assert_eq!(cart.items, vec![pen(2), ink()]);
    assert_eq!(kit.get("/carts/c1/total").text(), "3");
    // A refusal from the entity is its status.
    assert_eq!(kit.post("/carts/c1/items", &pen(0)).status, 400);
}
```

## Test it through the real runtime

The integration testkit builds the module, starts Postgres and the runtime image in Docker with the module
loaded, and gives you an HTTP client for your routes. The example keeps these tests behind the `slow`
feature, so a plain `cargo test` needs no Docker:

```rust
#[test]
fn the_cart_through_the_runtime_survives_a_restart() {
    let mut rt = AnkkaTestKit::start(Module::build().unwrap()).unwrap();
    let http = rt.http().clone();
    assert_eq!(
        http.post("/carts/c1/items")
            .json(&pen(2))
            .send()
            .unwrap()
            .status,
        204
    );
    assert_eq!(
        http.post("/carts/c1/items")
            .json(&ink())
            .send()
            .unwrap()
            .status,
        204
    );
    assert_eq!(
        http.delete("/carts/c1/items/p2").send().unwrap().status,
        204
    );

    // A new runtime on the same database: the cart comes back from the journal.
    rt.restart().unwrap();
    let cart: Cart = rt.http().get("/carts/c1").send().unwrap().json().unwrap();
    assert_eq!(cart.items, vec![pen(2)]);

    let checkout = rt.http().post("/carts/c1/checkout").send().unwrap();
    assert_eq!(checkout.status, 200, "{}", checkout.text());
    // Kept after the checkout, and refusing changes after a restart too.
    rt.restart().unwrap();
    let kept: Cart = rt.http().get("/carts/c1").send().unwrap().json().unwrap();
    assert!(kept.checked_out);
    assert_eq!(kept.items, vec![pen(2)]);
    assert_eq!(
        rt.http()
            .post("/carts/c1/items")
            .json(&ink())
            .send()
            .unwrap()
            .status,
        409
    );

    // Discarding deletes a cart, so the id is fresh again.
    rt.http()
        .post("/carts/c2/items")
        .json(&ink())
        .send()
        .unwrap();
    let discarded = rt.http().delete("/carts/c2").send().unwrap();
    assert_eq!(discarded.status, 204, "{}", discarded.text());
    let fresh: Cart = rt.http().get("/carts/c2").send().unwrap().json().unwrap();
    assert_eq!(fresh, Cart::empty("c2"));
}
```

```bash
cargo test --features slow
```

`Module::build()` runs `cargo module`'s build for the package under test. `restart()` replaces the runtime
and keeps the database, which is how a test proves the state is durable rather than cached.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The runtime is a local ankka service like any other, so the console lists it: its components, a form per
route, and a trace of each request. The trace of `POST /{cartId}/items` shows the endpoint and the
`shopping-cart#add-item` call beneath it. [The local console](../operate/local-console.md) covers the rest.

## Next

[Deploy to a local platform](deploy-locally.md). A Rust service is deployed as a Scala one is, with two
extra fields in its descriptor, `"hosting": "wasm"` and the protocol version the crate speaks:

```json title="service.json"
{ "name": "cart", "service": { "image": "cart-module:0.1.0", "hosting": "wasm", "protocol": "1.1" } }
```

The image holds only the module and one command that copies it where the runtime loads it from;
[Build an image](../deploy/images.md#a-rust-service) shows it.
