---
title: Python SDK
description: A compact map of the Python SDK — installing it, and for every component kind its base class, class attributes, decorators, effect builders and testkit — plus the SDK's own development commands.
kind: reference
languages: [python]
components: [event-sourced-entity, key-value-entity, view, consumer, workflow, timed-action, agent, http-endpoint]
related: [concepts/polyglot.md, get-started/first-service-python.md, reference/scala-sdk.md, reference/sidecar-protocol.md]
---

# Python SDK

The Python SDK is the package `ankka`, for Python 3.12 and later. A Python service is a process that the
ankka sidecar hosts: the sidecar owns the journal, sharding, projections, timers, HTTP and the agent loop,
and your classes decide what each command does. This page lists what each component kind is made of.
[Services in other languages](../concepts/polyglot.md) explains the model.

## Installing

The SDK is on PyPI as [`ankka`](https://pypi.org/project/ankka/). Every ankka release publishes the SDK
at the same version, so pin the version of the platform you deploy to:

```bash
uv add "ankka==0.4.0"                   # the SDK
uv add "ankka[testkit]==0.4.0"          # with the integration testkit's dependencies
```

`pip install ankka==0.4.0` does the same for a project that does not use uv. To work against an unreleased
SDK, install it from a checkout of the ankka repository instead, by path (`uv add --editable
/path/to/ankka/sdks/python`), after generating its protocol stubs as described under
[Developing the SDK](#developing-the-sdk).

It depends on `grpcio` and `protobuf`. The `testkit` extra adds `testcontainers` and `httpx`, which the
integration testkit needs to start Postgres and the sidecar image. Docker is needed for integration tests
and for running the sidecar locally; a JVM is not.

## The shape every component shares

A component is a class that extends the kind's base class and declares, as class attributes, its
`component_id` and its codecs. Handlers are methods decorated with their wire name:

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

A handler's argument and reply types are read from its annotations and encoded with the SDK's default
codec: dataclasses and unions of dataclasses as JSON, primitives as text. `json_codec(cls, "manifest")`
names the codec for a state, event or row type explicitly. A mistake in a class, such as a query that does
not return a read-only effect, raises `RegistrationError` when the class is defined or registered.

Field names are the wire contract. The JSON the SDK writes is the JSON the Scala SDK writes, so use the same
field names, `productId` rather than `product_id`, when a Scala service shares the data.

## Event sourced entity

| Part | API |
|---|---|
| Base class | `EventSourcedEntity[S, E]` |
| Class attributes | `component_id`, `state_codec`, `event_codec` |
| Must define | `empty_state(self) -> S`, `apply_event(self, state, event) -> S` |
| Decorators | `@command("name")`, `@query("name")` |
| In a handler | `self.state`, `self.entity_id`, `self.context` (a `CommandContext`), `self.effects` |
| Effects | `persist(e, *more)`, `persist_all(events)`, `delete_entity()`, then `.then_reply(lambda s: r)`, `.then_reply_state()`, `.then_no_reply()`, `.delete_entity()`, `.expire_after(timedelta)`; `reply(r)`, `error(msg, code)`, `no_reply()` |
| Types | A command returns `EventSourcedEffect[S, E, R]`; a query returns `ReadOnlyEffect[S, E, R]` |

See [Event sourced entities](../build/event-sourced-entities.md).

## Key value entity

| Part | API |
|---|---|
| Base class | `ankka.key_value_entity.KeyValueEntity[S]` |
| Class attributes | `component_id`, `state_codec` |
| Must define | `empty_state(self) -> S` |
| Decorators | `@command("name")`, `@query("name")` |
| In a handler | `self.state`, `self.entity_id`, `self.context`, `self.effects` |
| Effects | `update_state(s)`, `delete_entity()`, then `.then_reply(lambda s: r)`, `.then_reply_state()`, `.then_no_reply()`, `.expire_after(timedelta)`; `reply(r)`, `error(msg, code)`, `no_reply()` |

See [Key value entities](../build/key-value-entities.md).

## View

| Part | API |
|---|---|
| Base class | `ankka.view.View[Src, Row]` |
| Class attributes | `component_id`, `source` (an entity class) or `topic` (a topic name), `event_codec`, `row_codec` |
| Must define | `on_change(self, event) -> ViewEffect` |
| May override | `on_delete(self) -> ViewEffect`, which deletes the row by default |
| In a handler | `self.row` (the current row or `None`), `self.metadata` (`subject`, `sequence_number`), `self.effects` |
| Effects | `update_row(row)`, `delete_row()`, `ignore()` |
| Querying | `client.views.get(view_id, key, RowType)`, `client.views.all(view_id, RowType)` |

See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Base class | `ankka.consumer.Consumer[Src, Out]` |
| Class attributes | `component_id`, `source` or `topic`, `message_codec`; to publish, `produces_to` and `out_codec` |
| Must define | `async on_message(self, message) -> ConsumerEffect` |
| May override | `on_delete(self)`, which ignores by default |
| In a handler | `self.metadata`, `self.client`, `self.effects` |
| Effects | `produce(out, metadata=None)`, `done()`, `ignore()` |

Delivery is at least once. A consumer that produces needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the sidecar.
See [Consumers](../build/consumers.md).

## Workflow

| Part | API |
|---|---|
| Base class | `ankka.workflow.Workflow[S]` |
| Class attributes | `component_id`, `state_codec`, optionally `settings` |
| Must define | `empty_state(self) -> S` |
| Decorators | `@command("name")`, `@query("name")`, `@step("name")` from `ankka.workflow` |
| In a handler | `self.state`, `self.entity_id`, `self.context` (with `client`), `self.effects`, `self.step_effects` |
| Command effects | `update_state(s)`, `transition_to(step, input=None)`, then `.then_transition_to(step, input)`, `.then_reply(lambda s: r)`, `.then_reply_state()`, `.then_no_reply()`; `reply(r)`, `error(msg, code)` |
| Step effects | `update_state(s)` then, or directly: `transition_to(step, input)`, `pause(after=None, on_timeout=None)`, `end()`, `fail(msg, code)` |
| Settings | `WorkflowSettings(timeout, default_step_timeout, default_recovery, steps={name: StepSettings(timeout, recovery)})` |
| Recovery | `Recovery(max_retries=n, failover_to="step")` |

Steps are `async` and may call other components. A step that raises is retried as its recovery says, then
failed over. See [Workflows](../build/workflows.md).

## Timed action and timers

| Part | API |
|---|---|
| Base class | `ankka.timed_action.TimedAction` |
| Class attributes | `component_id` |
| Decorators | `@action("name")` from `ankka.timed_action` |
| In a handler | `self.metadata` (the timer's name and attempt count), `self.client`, `self.effects` |
| Effects | `done()`, `fail(msg, code)` |
| Scheduling | `await client.timers.schedule(timer_id, timedelta, component_id, action, input)`, `await client.timers.cancel(timer_id)` |

Scheduling twice under one id replaces the earlier timer. See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Base class | `ankka.agent.Agent` |
| Class attributes | `component_id`, `tools` (`{name: Tool(description, function, InputDataclass)}`), `guardrails` (`{name: Guardrail(check)}`), optionally `role`, `max_tool_call_steps` |
| Decorators | `@command("name")`, `@stream("name")` from `ankka.agent` |
| In a handler | `self.session_id`, `self.metadata`, `self.client`, `self.effects` |
| Effects | `system_message(t)`, `user_message(t)`, `model(name)`, then `.with_model(name)`, `.with_context(t)`, `.memory(bool)`, `.tools(*names)`, `.guardrails(*names)`, `.then_reply()`, `.then_reply_json()`; `error(msg, code)` |

A handler returns a plan; the sidecar runs the model loop, calls tools back in the process with the model's
arguments, and checks guardrails. A guardrail's check takes the stage (`"input"` or `"output"`) and the text,
and returns `None` to pass or a reason to block. See [Agents](../build/agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Base class | `Endpoint` |
| Class attributes | `prefix`, `acl` (required: `Acl.ALLOW_ALL`, `Acl.DENY_ALL` or `Acl.AUTHENTICATED`) |
| Decorators | `@get`, `@post`, `@put`, `@patch`, `@delete`, `@sse`, each with a path template and an optional `acl=` for that route alone |
| Handlers | `async` methods; path parameters bind by name, one further typed parameter is the body, the return value is encoded by its type |
| In a handler | `self.request`: `query_param`, `query_params`, `header`, `principal`, `metadata` |
| Errors | raise `HttpProblem(status, message)`; a `CommandError` from a call answers with its code's status |

The constructor receives the component client when it takes one. The process never binds an HTTP port: the
sidecar serves the routes and forwards each request. `acl` is required, as it is in the Scala SDK: a class
that omits it raises `RegistrationError` when it is defined, naming the class, rather than serving requests
under a posture nobody chose. A route decorator's `acl=` replaces the endpoint's for that route; a decorator
that omits it leaves the endpoint's in force. See [HTTP endpoints](../build/http-endpoints.md).

## Calling components

```python
cart = client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cart_id)
await cart.call("add-item").invoke(item, reply=Done)
state = await cart.call("get-cart").invoke(reply=ShoppingCart)
```

| Target | Call |
|---|---|
| Event sourced entity | `client.for_event_sourced_entity(component_id, entity_id)` |
| Key value entity | `client.for_key_value_entity(component_id, entity_id)` |
| Workflow | `client.for_workflow(component_id, workflow_id)` |
| Agent | `client.for_agent(component_id, session_id)` |

`.call(name)` gives an invocation: `await invocation.invoke(input, reply=Type)`, or
`async for token in invocation.stream(input)` for a streaming agent handler. A refusal raises
`ankka.client.CommandError`, whose `error` holds the message and code. Passing a request's metadata on
makes the call a child span of the request's trace. See [Calling components](../build/component-client.md).

## Running a service

```python
import asyncio
from ankka import Ankka

asyncio.run(Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint).listen())
```

`listen()` serves the protocol on port 9010, or `ANKKA_PROCESS_PORT`, and waits for the sidecar to connect.
Locally, run the sidecar with `docker compose --profile polyglot up -d` from the ankka repository.

## Testing

| Kit | Module | Drives |
|---|---|---|
| `EventSourcedTestKit.of(Cls, id)` | `ankka.testkit` | One entity; `call(name, input)` returns events, state and reply. |
| `KeyValueTestKit.of(Cls, id)` | `ankka.testkit` | One key value entity. |
| `WorkflowTestKit.of(Cls, id)` | `ankka.testkit` | One workflow: `call`, `run_step`, `run_until_end`. |
| `ViewTestKit.of(Cls)` | `ankka.testkit` | A view's `on_change` and `on_delete`, and the resulting rows. |
| `ConsumerTestKit.of(Cls)` | `ankka.testkit` | A consumer's `on_message` and `on_delete`. |
| `TimedActionTestKit.of(Cls)` | `ankka.testkit` | A timed action's handlers. |
| `AgentTestKit.of(Cls, session_id, model=ScriptedModel())` | `ankka.testkit` | An agent's plan, tools and guardrails, against a scripted model. |
| `EndpointTestKit.of(Cls, …)` | `ankka.testkit` | An endpoint's routes, with no sidecar. |
| `AnkkaTestKit.start(builder)` | `ankka.testkit.integration` | The whole service through the real sidecar image and a throwaway Postgres. `restart()` starts a new sidecar on the same database. |

Unit testkits need nothing running and still round-trip every value through its codec. See
[Testing](../build/testing.md).

## Developing the SDK

From `sdks/python` in the ankka repository:

```bash
uv sync                                        # install, with the development dependencies
uv run python scripts/proto.py                 # copy the protocol in and regenerate the gRPC stubs
uv run pytest                                  # unit testkits, the encoding fixtures, the servicer
uv run pytest -m slow                          # through the real sidecar and Postgres; needs Docker
uv run mypy && uv run mypy examples            # strict type checking
uv run conformance                             # serve the reference service and run the platform's conformance suite
uv run python -m examples.shopping_cart.main   # the sample, beside `docker compose --profile polyglot up`
```

The SDK carries a copy of the protocol so it can be built on its own; `scripts/proto.py` refreshes it. The
conformance suite needs sbt and runs the platform's suite against the SDK's reference service. See
[Adding a language SDK](../contributing/language-sdks.md).
