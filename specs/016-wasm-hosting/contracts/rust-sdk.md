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

    fn empty_state(entity_id: &str) -> Cart { Cart::new(entity_id) }

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
- `empty_state` is given the entity id, since a state commonly carries it (the cart's `cartId`).
- `KeyValueEntity`, `Workflow` (with `steps()` and `step("name", f)`), `View`, `Consumer`,
  `TimedAction`, `Agent` (with `tools()` and `guardrails()`) and `Endpoint` follow the same
  pattern. An endpoint's `routes()` declares `get`, `post`, … with a template and a handler; a
  `post`, `put` or `patch` handler takes the decoded body as its second argument (`()` for none),
  and `.with_acl(Acl::…)` after a route sets that route's own ACL — the protocol's `AllowAll`,
  `DenyAll`, `Authenticated` or `Callers(…)` — which otherwise is the endpoint's. A route's id is
  `"METHOD template"`. A handler answers a `Response` or any serializable value (`Done` and `()`
  answer 204); `HttpProblem` is a refusal, and a component's `CommandError` becomes its status
  with `?`.
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

### The other kinds, as built

- **Key value**: `effects::update_state(s).then_reply(..)`, and `effects::delete_state()` for a
  deletion (the event sourced `effects::delete_entity()` persists; a key value entity has nothing to
  persist). Handlers are `KeyValueHandlers::new().command(..).query(..)`.
- **Workflow**: command effects are `effects::workflow::update_state(s).transition_to("step")`
  (or `transition_to_with("step", input)`), step effects `step_effects::update_state(s)` then
  `.then_transition_to(..)`, `.then_pause()`, `.then_pause_for(duration, "on-timeout-step")`,
  `.then_end()`, or `step_effects::fail(error)`. A step is `Steps::new().step("name", fn(&S, In,
  &Context) -> StepEffect<S>)`, `In` being `()` for a step handed nothing. Timeouts and recovery are
  `WorkflowSettings::new().default_step_timeout(..).step_recovery("step", Recovery::retries(n)
  .failover_to("step"))`. A step that panics is a failed step, retried and failed over as declared.
- **View**: `on_event(row: Option<Row>, event, &Context) -> ViewEffect<Row>` and `on_deleted(row,
  &Context)` (dropping the row unless overridden), `effects::view::{update_row, delete_row, ignore}`;
  the source is `Source::of(Component)` or `Source::topic(..)`; `queries()` names the queries.
- **Consumer**: `on_message(message, &Context) -> ConsumerEffect`, `effects::consumer::{done,
  ignore, produce}`, `produces_to()` for a publishing consumer.
- **Timed action**: `Actions::new().action("name", fn(In, &Context) -> Result<(), CommandError>)`.
- **Agent**: a tool's argument schema is declared with `Schema::object().string(..).integer(..)`
  rather than derived — a Rust type carries no description of its fields at run time — and the
  arguments are decoded into the tool's own type; `Err` is a message for the model. Guardrails are
  `Guardrails::new().guardrail("name", fn(Stage, &str, &Context) -> Result<(), String>)`. There
  are no streaming handlers: a module answers every call whole.
- **Autonomous agent** (amended): `impl AutonomousAgent` with `COMPONENT_ID`, `DESCRIPTION`, optional
  `INSTRUCTIONS` and `MODEL`, `accepts() -> Vec<TaskAcceptance>`, and optional `tools()`,
  `guardrails()` (the agent's own `Tools` and `Guardrails`, no longer bound to `Agent`) and
  `settings() -> Option<AutonomousSettings>`; registered by value like every kind, rendered into
  `AutonomousAgentDetail`. A task type is a value, `TaskType::<R>::new(name, description, Schema)`
  (the result's schema written out, as a tool's is) or `TaskType::text(name, description)` (no schema:
  the model gives `{"result": "..."}` and the result is a JSON string), with `.rule(name, fn(&R, &Context)
  -> Verdict)`; `TaskAcceptance::new(type, max_iterations)` erases `R`. `ankka1_check_task_result`
  decodes the result as `R` (`malformed` with serde's message when it does not), runs the rules in
  order (`reject{rule, reason}` for the first refusal, else `accept`), and a rule that panics traps.
  Rules take a `&Context` where Python's take only the result: a module instance keeps nothing between
  calls (a check runs on a fresh instance), so a rule that must remember something — the conformance
  reference's `steady`, which faults once — does it through the client. A tool, guardrail or rule reads
  its task as `ctx.task_id()`. Validation mirrors Python's refusals, one problem each, prefixed
  `autonomous agent '<id>': `. The client adds `tasks().create(&type, task)`, `task(id).get()`,
  `.get_as(&type)`, `.wait(reads)`, `.cancel()`, and `autonomous_agent(A).run_single_task(&type, task)` and
  `.instance(id).{assign, suspend, resume, terminate, state}`, in the order and with the payloads Python
  sends. Two departures follow from a module having no clock and no randomness: `wait` is bounded by a
  number of reads rather than a timeout, and an id nobody named is derived from `ankka.now`, the call's
  metadata and a per-instance count, made again when it names an existing task. Notifications are not
  offered — a module answers every call whole — and the conformance suite's two notification cases are
  skipped for a module, as its streaming cases are. `AutonomousAgentTestKit::<C>::new(task_id)` offers
  `run_tool`, `check_rule`, `check_guardrail` and `check_result`, with no loop. The protocol version the
  crate declares is 1.2.
- **Shape chosen at start**: `Service::register_as(component, shape)` registers a stateful kind
  with a shape its declaration does not fix, for a service that reads it from configuration, as
  the conformance reference does.
- **Unit testkits** beside `EventSourcedTestKit`: `KeyValueEntityTestKit`, `WorkflowTestKit`
  (`command`, `run_step`, `run_until_pause`, `run_to_end`, `resume`), `ViewTestKit`,
  `ConsumerTestKit`, `TimedActionTestKit` (`fire`) and `AgentTestKit` over a `ScriptedModel`; each
  that calls other components takes `with_service(build())` to answer them in memory.

## The service and the exports

```rust
// lib.rs of the service crate
fn build() -> Service {
    Service::new("ankka-rust")
        .register(ShoppingCart)
        .register(CartRows)
        .endpoint(CartApi)
}
ankka::service!(build);                    // emits every ankka1_ export and the panic hook
```

A component is a unit struct and is registered, and named to the client, by value: Rust's coherence
rules allow one blanket implementation per trait, so the kind is inferred from a marker type
parameter, which needs a value to infer from.

`Service::build` validates the whole registry (duplicate ids and wire names, an endpoint without an
ACL, a streaming route) and reports every problem in the discovery reply; the runtime refuses to start
and logs them.

## The client and configuration

```rust
let cart: Cart = ctx.client().invoke(ShoppingCart, "cart-1", "get-cart", ())?;
let rows: Vec<Row> = ctx.client().query(CartRows, "by-owner", owner)?;
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
let outcome = kit.command("add-item", item.clone());       // a CommandOutcome
assert_eq!(outcome.events, vec![CartEvent::ItemAdded { item }]);
assert_eq!(outcome.reply::<Done>(), Ok(Done));            // Result<R, CommandError>: a refusal is the Err
assert_eq!(kit.state().items.len(), 1);

// integration (feature "testkit"; Docker)
let rt = AnkkaTestKit::start(Module::build()?)?;     // Postgres + the sidecar image, module bind-mounted
let r = rt.http().post("/carts/cart-1/items").json(&item).send()?;
rt.restart()?;                                          // a new runtime on the same database
```

`EndpointTestKit::with_service(build())` answers an endpoint's calls to its event sourced entities
in memory; `EndpointTestKit::new()` refuses them as unavailable. `AnkkaTestKit` can also stop and
start its runtime and start another image on its database (`stop_runtime`, `start_runtime`,
`start_beside`), for tests that share a journal between services, which must never run at once.

`Module::build()` runs `cargo build --release --target wasm32-unknown-unknown` for the current
package; `Module::at(path)` takes a built one. The kit takes the sidecar image from
`ANKKA_SIDECAR_IMAGE`, else `ghcr.io/thinkmorestupidless/ankka-sidecar:<the crate's version>`, else
`ankka-sidecar:latest` for the `0.0.0` tree, as the other testkits do.

## Conformance

`examples/shopping-cart` carries the reference components under `src/conformance.rs`, with a
`build()` of their own — a module is one service, so the example builds as the reference with its
`conformance` cargo feature; their shape is read from `ankka::config("ANKKA_CONFORMANCE_SHAPE")`.
`sdks/rust/conformance.sh` builds the module and runs:

```bash
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=wasm:$MODULE                      # stateless
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=wasm:$MODULE -Dankka.conformance.shape=stateful
```

`ANKKA_CONFORMANCE_ONLY` narrows either.
