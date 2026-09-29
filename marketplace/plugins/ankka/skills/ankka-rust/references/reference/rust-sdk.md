# Rust SDK

> A compact map of the Rust crate — installing it, the codec and time types, and for every component kind its trait, declarations, effect builders and testkit — plus the guest shapes, running a module and the crate's own commands.

Source: https://docs.ankka.cloud/reference/rust-sdk/
The Rust SDK is the crate `ankka`. A Rust service is built to a WebAssembly module that the ankka runtime
loads into its own process: the runtime owns the journal, sharding, projections, timers, HTTP and the agent
loop, and your functions decide what each command does. This page lists what each component kind is made
of. [Services in other languages](../concepts/polyglot.md#services-as-webassembly-modules) explains the
model, and [WebAssembly ABI](wasm-abi.md) is what the crate speaks to the runtime.

## Installing

The crate is on crates.io as `ankka`. Every ankka release publishes it at the same version, so pin the
version of the platform you deploy to:

```toml
[lib]
crate-type = ["cdylib", "rlib"]            # the module, and the library the tests link

[dependencies]
ankka = "0.8.0"
serde = { version = "1", features = ["derive"] }

[dev-dependencies]
ankka = { version = "0.8.0", features = ["testkit"] }   # the integration testkit, which needs Docker
```

A module is built for `wasm32-unknown-unknown` (`rustup target add wasm32-unknown-unknown`) with
`cargo build --release --target wasm32-unknown-unknown`; a project made by `ankka init --language rust`
names that build `cargo module`. The crate depends on `prost`, `serde` and `serde_json`; the `testkit`
feature adds a Docker client and an HTTP client, so a service's own dependencies carry no Docker tooling.
The release profile should set `panic = "abort"`: a panic is a trap the runtime reports, not an unwind a
module has any use for.

A module has no network, no file system and no clock of its own. `std::net`, `std::fs`, `std::env` and
`SystemTime::now` do nothing useful in one; the runtime is reached through the context's client, the time
through `ctx.now()`, and configuration through [`config`](#configuration).

## Codec and types

Every value that crosses to the runtime is encoded by serde's derives, under the rules every ankka
language shares, so one journal is readable from all of them; [Serialization](../build/serialization.md)
has the rules.

| Rust type | On the wire |
|---|---|
| a struct with `Serialize, Deserialize` | a JSON object, every field written; `#[serde(rename = "productId")]` for the stored spelling |
| an enum with `#[serde(tag = "type")]` | the case's object with `"type": "<Case>"`; an enum without the attribute is refused, naming it |
| `String`, `i32`, `i64`, `f64`, `bool` | at top level `text/plain`, as the other SDKs write them; inside a record, JSON |
| `f64` inside JSON | as Scala writes it: `1.0`, `1.0E10` |
| `Option<T>` | `null` when absent |
| `Vec<T>`, `BTreeMap<String, T>` | a JSON array, a JSON object |
| `ankka::Instant` | ISO-8601 in UTC with 0, 3, 6 or 9 fractional digits |
| `ankka::Duration` | ISO-8601, `PT1.5S` |
| `ankka::LocalDate`, `ankka::LocalDateTime` | ISO-8601 strings |
| `ankka::Bytes` | raw bytes at top level |
| `Done`, `()` | the empty `done` and `unit` payloads |

A value is stored under its type's own name unless its component names a manifest: `STATE_MANIFEST`,
`EVENT_MANIFEST` and `ROW_MANIFEST` on the traits. A codec of your own implements `ankka::codec::Codec`.

## The shape every component shares

A component is a unit struct that implements its kind's trait: associated types for its state and
messages, `COMPONENT_ID`, and a table of handlers whose first argument is the wire name.

```rust
pub struct ShoppingCart;

impl EventSourcedEntity for ShoppingCart {
    type State = Cart;
    type Event = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "shopping-cart";

    fn empty_state(cart_id: &str) -> Cart { Cart::empty(cart_id) }
    fn apply(cart: Cart, event: &ShoppingCartEvent) -> Cart { /* the fold */ }

    fn handlers() -> Handlers<ShoppingCart> {
        Handlers::new()
            .command("add-item", ShoppingCart::add_item)   // fn(&Cart, LineItem, &Context) -> Effect<ShoppingCartEvent, Done>
            .query("get-cart", ShoppingCart::get_cart)     // fn(&Cart, (), &Context) -> ReadOnlyEffect<Cart>
    }
}
```

`command` accepts a handler returning `Effect`; `query` accepts only `ReadOnlyEffect`, so a query that
persists does not compile. The function's name is yours; the wire name (`"add-item"`) is the platform's.
`empty_state` is given the entity's id, since a state commonly carries it. Components are registered, and
named to the client, by value: `.register(ShoppingCart)`.

## Event sourced entity

| Part | API |
|---|---|
| Trait | `EventSourcedEntity` |
| Associated items | `type State`, `type Event`, `COMPONENT_ID`; optionally `SHAPE`, `STATE_MANIFEST`, `EVENT_MANIFEST` |
| Must define | `empty_state(entity_id) -> State`, `apply(state, &event) -> State`, `handlers() -> Handlers<Self>` |
| May define | `snapshot_every() -> u32` |
| Declarations | `Handlers::new().command(name, f)`, `.query(name, f)`; `f` takes the state, the input and the `Context` |
| Effects | `effects::persist(e)`, `persist_all(events)`, `delete_entity()`, then `.and(e)`, `.delete_entity()`, `.expire_after(d)`, `.then_reply(\|state\| r)`, `.then_reply_value(r)`, `.then_no_reply()`; `effects::reply(r)`, `effects::error(code, message)`, `effects::no_reply()` |
| Types | a command returns `Effect<E, R>`; a query returns `ReadOnlyEffect<R>`; `.into()` turns a `ReadOnlyEffect` refusal into an `Effect` |

See [Event sourced entities](../build/event-sourced-entities.md).

## Key value entity

| Part | API |
|---|---|
| Trait | `KeyValueEntity` |
| Associated items | `type State`, `COMPONENT_ID`; optionally `SHAPE`, `STATE_MANIFEST` |
| Must define | `empty_state(entity_id) -> State`, `handlers() -> KeyValueHandlers<Self>` |
| Effects | `effects::update_state(s)`, `effects::delete_state()`, then `.expire_after(d)`, `.then_reply(\|state\| r)`, `.then_reply_value(r)`, `.then_no_reply()`; `effects::reply(r)`, `effects::error(code, message)` |
| Types | a command returns `KeyValueEffect<S, R>`; a query returns `ReadOnlyEffect<R>` |

See [Key value entities](../build/key-value-entities.md).

## View

| Part | API |
|---|---|
| Trait | `View` |
| Associated items | `type Row`, `type Event`, `COMPONENT_ID`; optionally `ROW_MANIFEST` |
| Must define | `source() -> Source` (`Source::of(ShoppingCart)` or `Source::topic("name")`), `on_event(row, event, ctx) -> ViewEffect<Row>` |
| May define | `on_deleted(row, ctx)`, which deletes the row by default; `queries()`, `["get", "all"]` by default |
| In a handler | `row` is the current row or `None`; `ctx.metadata().subject()` is the source's id |
| Effects | `view::update_row(row)`, `view::delete_row()`, `view::ignore()` |
| Querying | `ctx.client().query(CartRows, "by-id", key)`, `query(CartRows, "all", ())`, or `query_by_name(view_id, name, key)` |

See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Trait | `Consumer` |
| Associated items | `type Message`, `COMPONENT_ID` |
| Must define | `source() -> Source`, `on_message(message, ctx) -> ConsumerEffect` |
| May define | `on_deleted(ctx)`, which ignores by default; `produces_to() -> Option<&str>`, a topic to publish to |
| Effects | `consumer::produce(value)`, `consumer::done()`, `consumer::ignore()` |

Delivery is at least once, and a handler that panics has the message delivered again. A consumer that
produces needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the runtime. See [Consumers](../build/consumers.md).

## Workflow

| Part | API |
|---|---|
| Trait | `Workflow` |
| Associated items | `type State`, `COMPONENT_ID`; optionally `SHAPE`, `STATE_MANIFEST` |
| Must define | `empty_state(entity_id)`, `handlers() -> WorkflowHandlers<Self>`, `steps() -> Steps<Self>` |
| May define | `settings() -> WorkflowSettings` |
| Declarations | `WorkflowHandlers::new().command(name, f)`, `.query(name, f)`; `Steps::new().step(name, f)`, `f` taking the state, the step's input and the `Context` |
| Command effects | `workflow::update_state(s)`, `workflow::transition_to(step)`, then `.transition_to(step)`, `.transition_to_with(step, input)`, `.then_reply(\|state\| r)`, `.then_reply_value(r)`; `effects::reply(r)`, `effects::error(code, message)` |
| Step effects | `step_effects::update_state(s)`, then `.then_transition_to(step)`, `.then_transition_to_with(step, input)`, `.then_pause()`, `.then_pause_for(duration, on_timeout_step)`, `.then_end()`; or directly `step_effects::transition_to(step)`, `end()`, `fail(CommandError::new(code, message))` |
| Settings | `WorkflowSettings::new().timeout(d).default_step_timeout(d).default_recovery(r).step_timeout(step, d).step_recovery(step, r)` |
| Recovery | `Recovery::retries(n).failover_to("step")` |

A step may call other components through `ctx.client()`. A step that panics is retried as its recovery says,
then failed over; a step that returns `fail(...)` ends the workflow. A step runs on an instance of the module
of its own, so a step waiting on another component holds nothing a command needs. See
[Workflows](../build/workflows.md).

## Timed action and timers

| Part | API |
|---|---|
| Trait | `TimedAction` |
| Associated items | `COMPONENT_ID` |
| Must define | `actions() -> Actions<Self>` |
| Declarations | `Actions::new().action(name, f)`, `f: fn(Input, &Context) -> Result<(), CommandError>` |
| In a handler | `ctx.metadata()` carries `ankka.timer` and `ankka.attempts` |
| Scheduling | `ctx.client().schedule(timer_id, Duration::of_seconds(30), Reminder, None, "remind", input)`, or `schedule_by_name(...)`; `ctx.client().cancel(timer_id)` |

Scheduling twice under one id replaces the earlier timer, and an `Err` is retried on the runtime's schedule.
See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Trait | `Agent` |
| Associated items | `COMPONENT_ID`; optionally `ROLE` |
| Must define | `handlers() -> AgentHandlers<Self>` |
| May define | `tools() -> Tools<Self>`, `guardrails() -> Guardrails<Self>`, `max_tool_call_steps()` |
| Declarations | `AgentHandlers::new().command(name, f)`; `Tools::new().tool(name, description, schema, f)`, `f: fn(Args, &Context) -> Result<String, String>`; `Guardrails::new().guardrail(name, f)`, `f: fn(Stage, &str, &Context) -> Result<(), String>` |
| Tool schemas | `Schema::object().string(name, description).integer(...).number(...).boolean(...).optional_string(...)`: the JSON Schema the model sees, with a description per field |
| Effects | `agent::system_message(t)`, `agent::user_message(t)`, then `.context(t)`, `.model(name)`, `.tools([...])`, `.guardrails([...])`, `.session_memory(bool)`, `.json_shape(hint)`, `.then_reply()`; `agent::error(code, message)` |

A handler returns a plan; the runtime runs the model loop, calls the module back for each tool with the
model's arguments decoded as `Args`, and checks guardrails. A tool's `Err` is a message for the model, not a
failure. Agents answer whole: a module cannot stream a reply. See [Agents](../build/agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Trait | `Endpoint` |
| Associated items | `ENDPOINT_ID`, `PREFIX` |
| Must define | `acl() -> Acl` (`Acl::AllowAll`, `Acl::DenyAll`, `Acl::Authenticated` or `Acl::Callers(vec![...])`), `routes() -> Routes<Self>` |
| Declarations | `Routes::new().get(template, f)`, `.delete(template, f)`, `.post(template, f)`, `.put(...)`, `.patch(...)`; `.with_acl(acl)` after a route replaces the endpoint's for it |
| Handlers | `get` and `delete`: `fn(&Request) -> Result<R, HttpProblem>`; `post`, `put`, `patch`: `fn(&Request, Body)`, the body decoded as `Body`, `()` for none. `R` is any serializable value or a `Response`; `Done` and `()` answer 204 |
| Callers | `CallerMatcher::Internet`, `CallerMatcher::service(name)`, `CallerMatcher::Service { name, project }`, `CallerMatcher::AnyInProject`, `CallerMatcher::SelfService` |
| In a handler | `request.path(name)`, `query(name)`, `header(name)`, `body_as::<T>()`, `principal()`, `caller()` (`Caller::Gateway`, `Caller::Service { project, name }` or `Caller::Local`), `metadata()`, `client()` |
| Responses | `Response::json(v)`, `text(s)`, `html(s)`, `bytes(content_type, b)`, `redirect(location)`, `no_content()`, then `.status(n)`, `.header(name, value)` |
| Errors | `HttpProblem::new(status, message)`; a `CommandError` from a call becomes its code's status with `?` |

The module never binds an HTTP port: the runtime serves the routes and hands each request over. `acl` is
required, as in the other SDKs: an endpoint without one does not compile. A route's id is
`"METHOD template"`. Routes cannot stream. See [HTTP endpoints](../build/http-endpoints.md).

## Calling components

```rust
let cart: Cart = ctx.client().invoke(ShoppingCart, "cart-1", "get-cart", ())?;
ctx.client().send(Recorder, "r1", "note", note)?;                  // for a handler that never replies
let rows: Vec<CartRow> = ctx.client().query(CartRows, "all", ())?;
let answer: String = ctx.client().invoke_by_name(Kind::Agent, "assistant", session, "ask", question)?;
```

| Call | Does |
|---|---|
| `invoke(Component, entity_id, name, input)` | calls a handler and waits for its reply, as `R` |
| `send(Component, entity_id, name, input)` | dispatches the call and carries on; nothing it answers comes back. The way to call a handler that never replies |
| `invoke_by_name(kind, component_id, entity_id, name, input)` | the same, for a component this service does not declare |
| `invoke_stream(...)` | a streaming handler's tokens, delivered whole once the stream ends |
| `query(View, name, key)`, `query_by_name(...)` | a view's rows |
| `schedule(...)`, `cancel(timer_id)` | timers |

A call blocks the handler until the runtime answers; a refusal is an `Err(CommandError)` whose `code` is the
refusal's. Inside a handler `ctx.client()` carries the request's trace, so the call is a child span. See
[Calling components](../build/component-client.md).

## Configuration

`ankka::config("NAME")` answers a variable the service's descriptor set, or `None`. It answers `None` for
every name the platform reserves — model keys, the database's credentials, the cluster's and the runtime's
own settings — whether or not it is set, since those belong to the runtime, never the module.

## Guest shapes

A stateful-kind component — an event sourced entity, a key value entity or a workflow — is stateless by
default: the runtime hands it its state on every call and it keeps nothing between calls. Declared
`Shape::Stateful`, it is handed its state once, when its instance is loaded, and keeps it until the runtime
unloads it, which saves decoding the state on every command.

```rust
impl EventSourcedEntity for ShoppingCart {
    const SHAPE: Shape = Shape::Stateful;
    // ...
}
// or, chosen when the service is built:
Service::new("cart").register_as(ShoppingCart, Shape::Stateful)
```

The runtime holds the state in both shapes, so a module that faults loses nothing. Choose stateful for a
state that is large or costly to decode; stateless otherwise.

## Running a service

```rust
fn build() -> Service {
    Service::new("cart").register(ShoppingCart).endpoint(CartApi)
}
ankka::service!(build);
```

`service!` writes every function the runtime calls and a panic hook that sends a panic's message to the
runtime's log before the module traps; it appears once in a crate. `Service::build` reports every problem
at once — a duplicate id or wire name, a shared endpoint prefix, a `Callers` ACL naming nobody — and the
runtime refuses a module with problems, logging each. Locally, the ankka repository's
`docker compose --profile wasm up -d` runs the runtime with a module mounted, which
`ANKKA_WASM_MODULE_PATH` points at yours.

## Testing

| Kit | Drives |
|---|---|
| `EventSourcedTestKit::<C>::new(id)` | One entity: `command(name, input)` answers a `CommandOutcome` with `events`, `new_state`, `retention`, `reply::<R>()` (a `Result<R, CommandError>`) and `error()`; `state()`. |
| `KeyValueEntityTestKit::<C>::new(id)` | One key value entity. |
| `WorkflowTestKit::<C>::new(id)` | One workflow: `command`, `run_step`, `run_until_pause`, `run_to_end`, `resume`, `state`. |
| `ViewTestKit::<C>::new()` | A view's `on_event(key, event)` and `on_deleted(key)`, and `row(key)`. |
| `ConsumerTestKit::<C>::new()` | A consumer's `on_message` and `on_deleted`. |
| `TimedActionTestKit::<C>::new()` | A timed action's `fire(name, input)`. |
| `AgentTestKit::<C>::new(session, ScriptedModel::new())` | An agent's plan, tools and guardrails, against a scripted model that fails when the script runs out. |
| `EndpointTestKit::<E>::new()` | An endpoint's routes by method and path, with no runtime; `with_service(build())` answers its calls to the service's entities in memory. |
| `AnkkaTestKit::start(Module::build()?)` | The whole module in the real runtime image and a throwaway Postgres (feature `testkit`, Docker). `restart()` starts a new runtime on the same database. |

The kits that call other components take `with_service(build())` too. Unit kits run natively with
`cargo test` and still round-trip every value through the codec. See [Testing](../build/testing.md).

## Developing the crate

From `sdks/rust` in the ankka repository:

```bash
cargo test --workspace                                              # the crate, the fixtures, the example
cargo build -p shopping-cart --release --target wasm32-unknown-unknown   # the example's module
cargo test -p shopping-cart --features slow                         # through the real runtime; needs Docker
./conformance.sh                                                    # the platform's conformance suite, both shapes
./scripts/proto.sh                                                  # refresh the crate's copy of the protocol
```

The crate carries a copy of the protocol so it builds from crates.io alone; `scripts/proto.sh` refreshes it.
The conformance suite needs sbt and runs the platform's suite against the example's reference module, once
in each guest shape. See [Adding a language SDK](../contributing/language-sdks.md#a-guest-library).
