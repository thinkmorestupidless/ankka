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
`SystemTime::now` do nothing useful in one. Everything is asked of the runtime: other components through
the context's client, [other services](#calling-other-services) through `ctx.services()`,
[the time and random bytes](#the-time-and-random-bytes) through `ctx.now()` and `ctx.random()`, and
configuration through [`config`](#configuration).

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
| Must define | `source() -> Source` (`Source::of(ShoppingCart)`, or `Source::topic("name")` with `.start_from(..)`, `.contract(..)`, `.broker(..)`, `.parallel()`), `on_event(row, event, ctx) -> ViewEffect<Row>` |
| May define | `on_deleted(row, ctx)`, which deletes the row by default; `queries()`, `["get", "all"]` by default; `declared()`, the view's declared queries (`query(name, statement)`); `version()` |
| In a handler | `row` is the current row or `None`; `ctx.metadata().subject()` is the source's id |
| Effects | `view::update_row(row)`, `view::delete_row()`, `view::ignore()` |
| Querying | `ctx.client().query(CartRows, "by-id", key)`, `query(CartRows, "all", ())`, `query_by_name(view_id, name, key)`, or a declared query with `ask(view, name, values)` / `ask_by_name(view_id, name, values, limit)` |
| Table name | `table_of(id)`, for a statement to name |

## Keyed view

| Part | API |
|---|---|
| Trait | `KeyedView` |
| Must define | `type Row`, `COMPONENT_ID`, `sources() -> Sources<Self>` (`Sources::new().on::<E>(Source::of(...), handler)`) |
| May define | `declared()`, `version()` |
| In a handler | `fn(event, ctx) -> KeyedViewEffect<Row>`; `ctx.rows().get(key)`, `ctx.rows().ask(name, values)` |
| Effects | `update_row(key, row)`, `delete_row(key)`, `update_rows(...)`, `delete_rows(keys)`, `ignore()`, `and(...)` |
| Testing | `KeyedViewTestKit::<C>::new()` |

See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Trait | `Consumer` |
| Associated items | `type Message`, `COMPONENT_ID` |
| Must define | `source() -> Source`, `on_message(message, ctx) -> ConsumerEffect` |
| May define | `on_deleted(ctx)`, which ignores by default; `produces_to() -> Option<&str>`, a topic to publish to, or `produces() -> Option<Publication>` (`Publication::to(topic).contract(..).broker(..)`) |
| In a handler | `ctx.entity_id()`, the source entity's id; `ctx.sequence()`, the change's sequence number; `ctx.client()` |
| Effects | `consumer::produce(value)`, `consumer::produce_with(value, metadata)`, `consumer::produce_all(messages)`, `consumer::done()`, `consumer::ignore()` |
| One of several messages | `consumer::message(value)`, then `.key(key)` to publish it under a record key other than its subject and `.metadata(metadata)` for its headers; `Outgoing::of(payload)` takes a payload already encoded |

Delivery is at least once, and a handler that panics has the message delivered again. A consumer that
produces needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the runtime. `produce_all` publishes its messages in
order, and the change is handled when the broker has accepted all of them; an empty list is handled at
once. `ConsumerEffect` has a variant for it, `ProduceAll`, which a `match` on the effect must cover. See
[Consumers](../build/consumers.md).

## Graph consumer

A consumer that publishes its source as graph deltas, in `ankka::graph`. It is registered as a consumer —
`.register(CartGraph)` — and can publish nothing but deltas.

| Part | API |
|---|---|
| Trait | `GraphConsumer` |
| Associated items | `type Message`, `COMPONENT_ID`, `TOPIC` |
| Must define | `source() -> Source`, `on_message(message, ctx) -> GraphEffect` |
| May define | `on_deleted(ctx)`, which ignores by default |
| Elements | `graph::node(id)`, `graph::edge(id, type, from, to)`, `graph::tombstone_node(id)`, `graph::tombstone_edge(id, type, from, to)`; then `.label(label)`, `.property(name, value)`, `.at(version)` |
| Effects | `graph::publish(elements)`, `graph::done()`, `graph::ignore()` |
| A refusal | a panic when the result is dispatched, naming the element and the fault; `graph::resolve(elements, sequence)` gives the same as a `Result` whose error is a `Refused` with its `why` |

| In `ankka::graph` | What it is |
|---|---|
| `Element` | An element as described, and as read: `kind()`, `id()`, `version()`, `labels()`, `edge_type()`, `from()`, `to()`, `properties()`, `get(name)`, `key()` |
| `Value` | A property value: `Text`, `Bool`, `Integer`, `Float`, `List`, with `From` for `&str`, `String`, `bool`, `i32`, `i64`, `f64` and `Vec` of them. `Float(2.0)` equals `Integer(2)`, as the reader of the topic stores it. |
| `read(value, key)` | Reads a record's value into an `Element`; given a key, also checks it is the delta's element key |
| `node_key(id)`, `edge_key(id)`, `SCHEMA_NAME` | The element keys and the contract's name |

The builders are chainable and cannot fail: an element is checked when the result is dispatched. A label
on an edge or a property on a tombstone is refused there too. See [Publish a graph](../build/graph.md).

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
| In a handler | `ctx.metadata()` carries `ankka.timer`, `ankka.attempts` and `ankka.due`; `ctx.due()` is the due time the run is for |
| Scheduling | `ctx.client().schedule(timer_id, Duration::of_seconds(30), Reminder, None, "remind", input)`, or `schedule_by_name(...)`; `ctx.client().schedule_recurring(timer_id, delay, period, Cleanup, "sweep", input)`, or `schedule_recurring_by_name(...)`; `ctx.client().cancel(timer_id)` |

Scheduling twice under one id replaces the earlier timer, except that a recurring timer set again for
the same handler with the same period keeps its next due time; a handler's `Err` is retried on the
runtime's schedule. A refused period is an `Err` from `schedule_recurring`, not a trap.
See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Trait | `Agent` |
| Associated items | `COMPONENT_ID`; optionally `ROLE` |
| Must define | `handlers() -> AgentHandlers<Self>` |
| May define | `tools() -> Tools<Self>`, `guardrails() -> Guardrails<Self>`, `max_tool_call_steps()` |
| Declarations | `AgentHandlers::new().command(name, f)`; `Tools::new().tool(name, description, schema, f)`, `f: fn(Args, &Context) -> Result<String, String>`; `Guardrails::new().guardrail(name, f)`, `f: fn(Stage, &str, &Context) -> Result<(), String>` |
| Tool schemas | `Schema::object().string(name, description).integer(...).number(...).boolean(...).string_array(...).optional_string(...)`: the JSON Schema the model sees, with a description per field |
| Effects | `agent::system_message(t)`, `agent::user_message(t)`, then `.context(t)`, `.model(name)`, `.tools([...])`, `.guardrails([...])`, `.session_memory(bool)`, `.json_shape(hint)`, `.then_reply()`; `agent::error(code, message)` |

A handler returns a plan; the runtime runs the model loop, calls the module back for each tool with the
model's arguments decoded as `Args`, and checks guardrails. A tool's `Err` is a message for the model, not a
failure. Agents answer whole: a module cannot stream a reply. See [Agents](../build/agents.md).

## Autonomous agent

| Part | API |
|---|---|
| Trait | `AutonomousAgent` |
| Associated items | `COMPONENT_ID`, `DESCRIPTION`; optionally `INSTRUCTIONS`, `MODEL` (a model named in the runtime's configuration) |
| Must define | `accepts() -> Vec<TaskAcceptance>` |
| May define | `tools() -> Tools<Self>`, `guardrails() -> Guardrails<Self>`, `settings() -> Option<AutonomousSettings>` |
| Task types | `TaskType::<R>::new(name, description, schema)` for a result decoded as `R`; `TaskType::text(name, description)` for a result that is text, which the model gives as `{"result": "..."}`; `.rule(name, f)`, `f: fn(&R, &Context) -> Verdict` |
| Verdicts | `Verdict::Accepted`, `Verdict::rejected(reason)` |
| Acceptance | `TaskAcceptance::new(task_type, max_iterations)`, or `TaskAcceptance::of(task_type)` for a budget of ten |
| Settings | `AutonomousSettings::new().approaching_budget_at(0.8).repeated_failure_at(3).max_consecutive_failures(5).dependency_stuck_after_millis(300_000)`; each left unset keeps the runtime's default |

The runtime runs the whole loop: the model, the task records and the instance's own record. It calls the
module for three things only — a tool, a guardrail, and a result the model completed a task with — each for
one task, which `ctx.task_id()` names. A result is decoded as its type's `R`; one that does not decode goes
back to the model as a mistake to correct, and so does the first rule's rejection. A rule that panics has
decided nothing: the module traps and the runtime checks the result again, so a rule must not rely on
anything the module kept, since a module keeps nothing between calls. A tool can run more than once for one
request of the model after a crash, so a tool with a side effect should tolerate that.

A task type is a value built by a function, so the agent's declaration and every caller share one:

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Answer {
    pub answer: String,
    pub sources: Vec<String>,
}

/// The one task type the answerer takes: an answer, and what it was drawn from.
pub fn answer() -> TaskType<Answer> {
    TaskType::new(
        "answer",
        "Answer a question, citing what you looked up",
        Schema::object()
            .string("answer", "the answer")
            .string_array("sources", "what the answer was drawn from"),
    )
    .rule("cites-sources", |a: &Answer, _: &Context| {
        if a.sources.is_empty() {
            Verdict::rejected("sources must not be empty")
        } else {
            Verdict::Accepted
        }
    })
    .rule("steady", steady)
}
```

`steady` is a second rule, which panics the first time it sees one particular answer and records that it
has, to show a rule's fault being checked again.

The agent names what it accepts, its tools and its guardrails; `Tools` and `Guardrails` are an agent's:

```rust
pub struct ConformanceAnswerer;

impl AutonomousAgent for ConformanceAnswerer {
    const COMPONENT_ID: &'static str = "answerer";
    const DESCRIPTION: &'static str = "Answers questions";

    fn accepts() -> Vec<TaskAcceptance> {
        vec![TaskAcceptance::new(answer(), 4)]
    }

    fn tools() -> Tools<ConformanceAnswerer> {
        Tools::new().tool(
            "lookup",
            "Looks up how many things were recorded under an id.",
            Schema::object().string("id", "the id things were recorded under"),
            ConformanceAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<ConformanceAnswerer> {
        Guardrails::new().guardrail("no-secrets", ConformanceAssistant::no_secrets)
    }
}
```

A caller creates tasks and drives instances through the client. `run_single_task` creates the task and
starts an instance of its own on it; `task(id)` reads and cancels one:

```rust
fn run_task(request: &Request, instructions: String) -> Result<Ran, HttpProblem> {
    AutonomousEndpoint::only_answer(request)?;
    let client = request.client();
    let task_id = client
        .autonomous_agent(ConformanceAnswerer)
        .run_single_task(&answer(), instructions)?;
    let task = client.task(&task_id).get_as(&answer())?;
    let instance_id = task
        .assignee
        .map(|(_, instance)| instance)
        .unwrap_or_default();
    Ok(Ran {
        task_id,
        instance_id,
    })
}
```

An instance the caller names takes queued work with `assign`, and is suspended, resumed, terminated and
read the same way:

```rust
fn assign(request: &Request, task_ids: Vec<String>) -> Result<Accepted, HttpProblem> {
    let instance = request
        .client()
        .autonomous_agent(ConformanceAnswerer)
        .instance(request.path("instance"));
    let assignment = instance.assign(task_ids)?;
    Ok(Accepted {
        accepted: assignment.accepted,
    })
}
```

| Call | Does |
|---|---|
| `client.tasks().create(&task_type, instructions)` | creates a pending task and answers its id; `NewTask::new(instructions).id(..).depends_on([..]).attachment(..)` in place of the instructions sets the rest. A dependency that does not exist is refused before anything is written, and one that has already failed or been cancelled cancels the new task at once |
| `client.task(id).get()`, `.get_as(&task_type)` | the task's record as a `TaskSnapshot`: `status`, `result` (as JSON, or decoded as the type's `R`), `reason`, `iterations`, `assignee`, the whole `record` |
| `client.task(id).wait(reads)` | reads the record until the task has ended, at most `reads` times; `Timeout` naming its status otherwise |
| `client.task(id).cancel()`, `.cancel_because(reason)` | cancels it, and takes it off the instance it was assigned to |
| `client.autonomous_agent(Agent).run_single_task(&task_type, task)` | creates the task and starts it on an instance the platform names; answers the task's id at once |
| `client.autonomous_agent(Agent).instance(id)` | `.assign(ids)` (an `Assignment` of accepted and refused ids), `.suspend()`, `.resume()`, `.terminate()`, `.state()` (an `AgentState`: `phase`, `current_task`, `iteration`, `queued`) |

A module has no clock to sleep on, so `wait` reads the record as fast as the runtime answers: it suits a task
that is nearly done, and a longer wait belongs to a workflow step retried on a timer, or to the caller. A
module cannot subscribe to an instance's notifications, since a module answers every call whole; read the
task's record instead. An id the caller does not choose is made from the runtime's clock and the call's
trace, a module having no source of randomness, and one that names an existing task is made again.

`AutonomousAgentTestKit::<C>::new(task_id)` runs the parts a module decides, with no loop and no model:
`run_tool(name, arguments)`, `check_guardrail(name, stage, text)`, `check_rule(&task_type, rule, &result)`,
and `check_result(&task_type, &result)` or `check_result_json(type_name, json)` for the whole check the runtime
asks for. `with_service(build())` answers their calls to the service's entities in memory. See
[Autonomous agents](../build/autonomous-agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Trait | `Endpoint` |
| Associated items | `ENDPOINT_ID`, `PREFIX` |
| Must define | `acl() -> Acl` (`Acl::AllowAll`, `Acl::DenyAll`, `Acl::Authenticated` or `Acl::Callers(vec![...])`), `routes() -> Routes<Self>` |
| Declarations | `Routes::new().get(template, f)`, `.delete(template, f)`, `.post(template, f)`, `.put(...)`, `.patch(...)`; `.with_acl(acl)` after a route replaces the endpoint's for it |
| Handlers | `get` and `delete`: `fn(&Request) -> Result<R, HttpProblem>`; `post`, `put`, `patch`: `fn(&Request, Body)`, the body decoded as `Body`, `()` for none. `R` is any serializable value or a `Response`; `Done` and `()` answer 204 |
| Callers | `CallerMatcher::Internet`, `CallerMatcher::service(name)`, `CallerMatcher::Service { name, project }`, `CallerMatcher::AnyInProject`, `CallerMatcher::SelfService` |
| In a handler | `request.path(name)`, `query(name)`, `header(name)`, `body_as::<T>()`, `principal()` (`subject`, `name`, `email`, `email_verified`, `roles`, `claims`, `issuer`), `caller()` (`Caller::Gateway`, `Caller::Service { project, name }` or `Caller::Local`), `metadata()`, `client()` |
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
| `schedule(...)`, `schedule_recurring(...)`, `cancel(timer_id)` | timers; `schedule_recurring` since protocol 1.12 |

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

## Secret store

The service's secret store, for a value it must keep and never record, such as a credential a person
gave it ([Secrets a service keeps](../build/secrets.md)). `ctx.secrets()` answers `Some(Secrets)` in an
endpoint, a consumer, a timed action, an agent, an autonomous agent's tools and a workflow step, and
`None` in an entity, a view and a workflow's command handler. `put(name, value)`, `get(name)` (an
`Option<String>`) and `delete(name)` each return a `Result` whose error is the runtime's `CommandError`.

The runtime holds the store and its key; a module never sees the key, and `config("ANKKA_SECRET_KEY")` is
`None`. The three imports, `get_secret`, `put_secret` and `delete_secret`, are reached through a function
of their own, so a module that never keeps a secret imports none of them and runs on a runtime from before
protocol 1.4. Natively, a unit test talks to an in-memory store that applies the runtime's rules, unless a
`NativeHost` answers. The integration test kit sets a generated `ANKKA_SECRET_KEY` on the runtime it
starts.

## Calling other services

A handler calls a route of another service by that service's name, and the runtime makes the call as this
service, so the other service's access rules can admit it by name
([Calling other services](../build/calling-services.md)). `ctx.services()` answers `Some(Services)` in an
endpoint's route, a workflow's step, a consumer, a timed action, an agent's handler, tool and guardrail,
and an autonomous agent's task rule. It answers `None` in an entity, a view and a workflow's command
handler: a call to another service waits for as long as that service takes, and a command that waited
would hold every other command to the same entity behind it.

```rust
// A consumer may call another service; an entity's context answers `None` here. The call
// is made by the runtime as this service, so the other service's ACL can admit it by name.
let services = ctx.services().expect("a consumer may call another service");
let answer =
    match services
        .service(&service)
        .request("GET", &path, RequestOptions::default())
    {
        // Whatever the service answered, a refusal included: its status and its body.
        Ok(response) => Answer {
            status: i32::from(response.status),
            body: String::from_utf8_lossy(&response.body).into_owned(),
        },
        // No answer came: the service was not found, was not the one named, or did not
        // answer in time. What that means here is the handler's to decide.
        Err(error) => Answer {
            status: 0,
            body: error.to_string(),
        },
    };
```

`services.service(name)` is a service of the same project and `service_in(project, name)` one of another.
A `ServiceClient` has `get`, `post` and `put`, which send and read JSON through the crate's codec,
`get_text`, `delete`, and the raw `request(method, path, RequestOptions)`, which answers a
`ServiceResponse` — status, content type, body and headers — for every status. `with_headers` adds headers
to every call a client makes. Every call blocks the handler until the service answers.

A call fails with a `ServiceError`:

| Error | When | Was anything sent |
|---|---|---|
| `Unresolvable { service, reason }` | no such service was found | no |
| `IdentityMismatch { service, detail }` | what answers for the name is not the service asked for | no |
| `Unanswered { service, reason }` | the connection was refused or broke, or no answer came in time | perhaps |
| `CallFailed { service, status, body }` | the service answered with a status outside 2xx, to a typed helper | yes, and it answered |
| `Refused(CommandError)` | the runtime refused the request, or the crate did: a body over 4,000,000 bytes, an answer that does not decode | no |

A `ServiceError` converts into a `CommandError`, so `?` works in a handler that answers one.

**The rule about where a call may be made is the runtime's, not the crate's.** `ctx.services()` being
`None` is the earlier answer, with the better message. A command that reaches the client some other way is
stopped by the runtime: the call does not return, the command is answered with a fault naming the import
and the command, and the entity keeps the state it had.

A module that never calls another service imports nothing for it. One that does needs a runtime that
speaks protocol 1.10, and an earlier runtime refuses the module at start, naming the import `request`.

In a unit test, `ScriptedServices` plays the other services: `answer(name, |request| …)` says what a
service answers, `unresolvable`, `unanswered` and `mismatch` make a call fail in each way, `requests()` is
every request made, and `run(|| …)` runs the code under test with them in place. A call to a service with
no script fails the test, naming the service.

## The time and random bytes

`ctx.now()` is the runtime's clock, read when it is called, from any handler. Two reads in one handler may
differ, so a handler that needs one time reads it once. When an event is applied again the time is still
the present: an event's time belongs in the event.

`ctx.random(&mut buf)` fills a buffer of any length with random bytes from the runtime's secure source,
from any handler. An id made from them in a command belongs in the event the command persists, because
applying the event again does not run the command again.

Natively there is no runtime: `now()` is the machine's clock and `random` the system's source. A unit test
fixes either with the test kit's `with_clock(instant, || …)` and `with_random(&bytes, || …)`.

A module that reads the time or asks for random bytes imports `now` or `random`, and needs a runtime that
speaks protocol 1.10; an earlier runtime refuses the module at start, naming the import. A module built with
an earlier version of the crate reads the time the runtime states on every call, and still runs.

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
| `ConsumerTestKit::<C>::new()` | A consumer's `on_message(subject, message)` and `on_deleted(subject)`. `.at(sequence)` sets the change's sequence number, `.with_service(build())` answers its client calls in memory, and `ConsumerTestKit::<C>::messages(&effect)` reads what an effect publishes as `Published { payload, key, metadata }`. |
| `GraphConsumerTestKit::<G>::new()` | A graph consumer's `on_message(subject, sequence, message)` and `on_deleted(subject, sequence)`, each returning the `Element`s published, read back from their bytes; `records(…)` gives the raw records. |
| `TimedActionTestKit::<C>::new()` | A timed action's `fire(name, input)`. |
| `AgentTestKit::<C>::new(session, ScriptedModel::new())` | An agent's plan, tools and guardrails, against a scripted model that fails when the script runs out. |
| `AutonomousAgentTestKit::<C>::new(task_id)` | An autonomous agent's tools, guardrails and task rules for one task, with no loop. |
| `EndpointTestKit::<E>::new()` | An endpoint's routes by method and path, with no runtime; `with_service(build())` answers its calls to the service's entities in memory. |
| `AnkkaTestKit::start(Module::build()?)` | The whole module in the real runtime image and a throwaway Postgres (feature `testkit`, Docker). `restart()` starts a new runtime on the same database. |

The kits that call other components take `with_service(build())` too. Unit kits run natively with
`cargo test` and still round-trip every value through the codec. `ScriptedServices`, `with_clock` and
`with_random` stand in for other services, the runtime's clock and its random bytes, beside any kit. See
[Testing](../build/testing.md).

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
