---
name: ankka-rust
description: Write, run, test or deploy an ankka service in Rust, built to a WebAssembly module the ankka runtime loads — components as unit structs implementing kind traits (associated types and constants, handler tables with wire names), serde-derived types in the shared encoding, ReadOnlyEffect for queries, the two guest shapes, the crate's testkits, the module ABI, and the descriptor of a wasm-hosted service. Use when the task involves Rust, cargo, crates.io, WebAssembly, wasm32, or the ankka crate.
---

# ankka in Rust

A Rust service is built to a WebAssembly module (`wasm32-unknown-unknown`) and the ankka runtime loads it
into its own process. The runtime owns everything durable and distributed (sharding, the journal,
projections, timers, the workflow engine, the agent loop and the model's key, HTTP binding and ACLs,
cluster membership); the module owns the decisions. The crate speaks the ABI so your code never does.
Every component guide in the other ankka skills applies; this skill holds what differs.

## Rules

1. **Every component is a unit struct implementing its kind's trait.** `pub struct ShoppingCart;` then
   `impl EventSourcedEntity for ShoppingCart { type State = Cart; type Event = CartEvent; const
   COMPONENT_ID: &'static str = "shopping-cart"; fn empty_state(id: &str) -> Cart; fn apply(state, &event)
   -> Cart; fn handlers() -> Handlers<Self> }`. The traits are `EventSourcedEntity`, `KeyValueEntity`,
   `Workflow` (with `steps()`), `View`, `Consumer`, `TimedAction` (with `actions()`), `Agent` (with
   `tools()` and `guardrails()`) and `Endpoint` (with `acl()` and `routes()`). Handlers are ordinary
   functions taking the state, the input and `&Context`.
2. **Handler tables name the wire name first.** `Handlers::new().command("add-item", Self::add_item)
   .query("get-cart", Self::get_cart)`. `query` accepts only a handler returning `ReadOnlyEffect`, so a
   persisting query does not compile. The function's name is yours; the wire name is the platform's.
3. **Register by value, once.** `Service::new("cart").register(ShoppingCart).endpoint(CartApi)`, and
   `ankka::service!(build);` exactly once in the crate. Components are named to the client by value too:
   `ctx.client().invoke(ShoppingCart, id, "add-item", item)?`.
4. **Types are serde, in the stored spelling.** `#[derive(Serialize, Deserialize)]` with
   `#[serde(rename = "productId")]` for camelCase fields; a sum type is an enum with
   `#[serde(tag = "type")]` (the codec refuses one without it). `STATE_MANIFEST`/`EVENT_MANIFEST`/
   `ROW_MANIFEST` name what the journal stores values under. A top-level `String`, integer or `bool` is
   `text/plain`; `Done` and `()` answer 204 from a route. Use `ankka::Instant` and `ankka::Duration`, not
   `std::time`.
5. **A module reaches nothing but the runtime.** No `std::net`, `std::fs`, `std::env` or
   `SystemTime::now`: call components with `ctx.client()`, read the time with `ctx.now()`, read
   configuration with `ankka::config("NAME")` (reserved names read as `None`). A handler that never
   replies is called with `ctx.client().send(...)`, not `invoke`, which waits.
6. **Effects are values.** `effects::persist(e).then_reply_value(Done)`, `.then_reply(|s| r)`,
   `.delete_entity()`, `.expire_after(d)`, `effects::reply(r)`, `effects::error(ErrorCode::Conflict, msg)`
   (`.into()` in a command); key value `effects::update_state(s)`, `delete_state()`; workflow
   `workflow::update_state(s).transition_to("step")`, `step_effects::update_state(s).then_transition_to(..)`,
   `.then_pause_for(d, "step")`, `.then_end()`; views `view::update_row`, `delete_row`, `ignore`; consumers
   `consumer::produce`, `done`, `ignore`; agents `agent::system_message(..).user_message(..).tools([..])
   .then_reply()`.
7. **Endpoints declare `acl()` and typed routes.** `Routes::new().get("/{cartId}", Self::get_cart)
   .post("/{cartId}/items", Self::add_item)`; a `post`/`put`/`patch` handler's second argument is the
   decoded body (`()` for none); `.with_acl(Acl::Authenticated)` after a route replaces the endpoint's.
   `HttpProblem::new(404, msg)` for a status; a `CommandError` becomes its code's status with `?`. No route
   or handler streams: a module answers whole.
8. **Two guest shapes.** A stateful-kind component is stateless by default (handed its state every call);
   `const SHAPE: Shape = Shape::Stateful` or `register_as(C, Shape::Stateful)` hands it once per loaded
   instance. The runtime holds the state either way, so a panic loses nothing.
9. **A panic is a fault.** The release profile sets `panic = "abort"`; `service!` logs the message before
   the trap. A workflow step that panics is retried and failed over as its recovery says; a consumer that
   panics has its message redelivered. A refusal is an effect, not a panic.
10. **Test natively, then through the runtime.** `EventSourcedTestKit::<C>::new(id).command(name, input)`
    answers a `CommandOutcome` (`events`, `reply::<R>()`, `error()`); `KeyValueEntityTestKit`,
    `WorkflowTestKit`, `ViewTestKit`, `ConsumerTestKit`, `TimedActionTestKit`, `AgentTestKit` with a
    `ScriptedModel`, `EndpointTestKit::with_service(build())`. `AnkkaTestKit::start(Module::build()?)`
    (feature `testkit`, Docker) runs the module in the real runtime image; keep those tests behind a
    feature so plain `cargo test` needs no Docker.
11. **Several messages are one effect, and a graph is published by a graph consumer.** A consumer returns
    `consumer::produce_all([consumer::message(x).key("k"), …])` to publish several messages for one change,
    each under its own record key if it names one; `ConsumerEffect` has a `ProduceAll` variant a `match`
    must cover. To publish entities as a graph, implement `GraphConsumer` (`Message`, `COMPONENT_ID`,
    `TOPIC`, `source()`) and return
    `graph::publish([graph::node(id).label("Cart").property("cartId", id), graph::edge(id, "TYPE", from, to), …])`.
    Each element is its whole state; the crate writes the delta's JSON, its `node:<id>`/`edge:<id>` key and
    its version (`ctx.sequence()`, or `.at(version)` when stated). The builders cannot fail; an element is
    checked when the result is dispatched, and a fault is a panic naming it. Tombstones come from
    `on_deleted`. Test with `GraphConsumerTestKit::<G>::new()`.
12. **Deploy as a wasm-hosted service.** The descriptor sets `"hosting": "wasm"` and `"protocol"`; the
    image holds only the module and a command copying it to `/ankka/module/service.wasm`; the platform
    runs it as an init container and its own runtime as the one container. `"http": false` is refused.

## Development loop

`rustup target add wasm32-unknown-unknown`; `cargo test` runs natively; `cargo build --release --target
wasm32-unknown-unknown` (a template project's `cargo module`) builds the module; `docker compose --profile
wasm up -d` in the ankka repository runs it, `ANKKA_WASM_MODULE_PATH` pointing at yours. In the crate's
own checkout, `sdks/rust`: `cargo test --workspace`, `cargo test -p shopping-cart --features slow`,
`./conformance.sh`. See `references/get-started/first-service-rust.md` for the first service and
`references/reference/rust-sdk.md` for every trait, builder and kit.

## Mistakes to check for

- `[build] target = "wasm32-unknown-unknown"` in a project's `.cargo/config.toml`: `cargo test` then
  builds for wasm32 and cannot run. Use an alias for the module build.
- An enum stored without `#[serde(tag = "type")]`, or a field in snake_case the journal spells camelCase.
- `invoke` on a handler that never replies: it waits for the timeout. Use `send`.
- `ankka::service!` twice, or a component used but not registered.
- A route or handler meant to stream: a module cannot; answer whole.
- Reading `std::env::var` for configuration: the module has no environment; use `ankka::config`.
- A graph delta built by hand: JSON, a `node:`/`edge:` key or a version written in the handler. Return
  elements from a graph consumer's builder; it writes all three.
- A record key set on a delta, or a delta published through an ordinary consumer's `produce`.
- A graph consumer that publishes an element another entity owns (a cart publishing the `product` node).
  Publish the edge; the owning entity publishes the node.
- An element built from a thin event, carrying only what changed. An element is its whole state: build it
  from an event that carries it, or read the entity through the client.
- A graph consumer over a topic with no version stated on its elements: a topic has no sequence number.
- Expecting ankka to create the delta topic or make it compacted. Declare it in the ankka-flow pipeline
  that reads it, and deploy that pipeline first.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/first-service-rust.md` — Write a Rust service with an event sourced entity and an HTTP endpoint, build it to a WebAssembly module, run it in the ankka runtime, test it with and without the runtime, and watch it in the local console.

### Concepts

- `references/concepts/polyglot.md` — How ankka hosts a service in Python, TypeScript or Rust — the runtime runs beside a process as a sidecar or loads a WebAssembly module, owning everything durable while the service's code decides.

### Build

- `references/build/graph.md` — Publish a service's entities as nodes and edges with a graph consumer, which writes versioned graph deltas to a topic for a graph database to follow, with no key, version or JSON written by hand.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in every language, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in Scala, Python, TypeScript and Rust, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

### Run and deploy

- `references/deploy/images.md` — Package a Scala, Python, TypeScript or Rust ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.
- `references/deploy/deploy-a-service.md` — Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.

### Reference

- `references/reference/rust-sdk.md` — A compact map of the Rust crate — installing it, the codec and time types, and for every component kind its trait, declarations, effect builders and testkit — plus the guest shapes, running a module and the crate's own commands.
- `references/reference/wasm-abi.md` — How the ankka runtime hosts a service built to a WebAssembly module — the exports a module provides, the imports it may call, the memory convention, the two guest shapes, faults, discovery, the runtime's settings, and versioning.
