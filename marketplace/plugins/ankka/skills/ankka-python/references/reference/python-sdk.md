# Python SDK

> A compact map of the Python SDK — installing it, and for every component kind its base class, class attributes, decorators, effect builders and testkit — plus the SDK's own development commands.

Source: https://docs.ankka.cloud/reference/python-sdk/
[![pypi](https://img.shields.io/pypi/v/ankka?label=pypi)](https://pypi.org/project/ankka/)
[![python](https://img.shields.io/pypi/pyversions/ankka)](https://pypi.org/project/ankka/)

The Python SDK is the package `ankka`, for Python 3.12 and later. A Python service is a process that the
ankka sidecar hosts: the sidecar owns the journal, sharding, projections, timers, HTTP and the agent loop,
and your classes decide what each command does. This page lists what each component kind is made of.
[Services in other languages](../concepts/polyglot.md) explains the model.

## Installing

The SDK is on PyPI as [`ankka`](https://pypi.org/project/ankka/). Every ankka release publishes the SDK
at the same version, so pin the version of the platform you deploy to:

```bash
uv add "ankka==0.5.0"                   # the SDK
uv add "ankka[testkit]==0.5.0"          # with the integration testkit's dependencies
```

`pip install ankka==0.5.0` does the same for a project that does not use uv. To work against an unreleased
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
| Class attributes | `component_id`, `source` (an entity class) or `topic` (a topic name), `event_codec`, `row_codec`; optionally `version`, and declared queries as `query(name, statement)` from `ankka.view` |
| Must define | `on_change(self, event) -> ViewEffect` |
| May override | `on_delete(self) -> ViewEffect`, which deletes the row by default |
| In a handler | `self.row` (the current row or `None`), `self.metadata` (`subject`, `sequence_number`), `self.effects` |
| Effects | `update_row(row)`, `delete_row()`, `ignore()` |
| Querying | `client.views.get(view_id, key, RowType)`, `client.views.all(view_id, RowType)`, `client.views.ask(view_id, name, RowType, {"value": ...}, limit=None)` |
| Table name | `table_of(component_id)`, for a statement to name |

## Keyed view

| Part | API |
|---|---|
| Base class | `ankka.keyed_view.KeyedView[Row]` |
| Class attributes | `component_id`, `row_codec`; optionally `version` and declared queries |
| Sources | a method per source marked `@on(EntityClass, event_codec)`; optionally `@on_deleted(EntityClass)` |
| In a handler | `self.subject`, `self.metadata`, `self.rows.get(key)`, `self.rows.ask(name, ...)`, `self.effects` |
| Effects | `update_row(key, row)`, `delete_row(key)`, `update_rows(...)`, `delete_rows(keys)`, `ignore()`, combined |
| Testing | `KeyedViewTestKit.of(View)` |

See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Base class | `ankka.consumer.Consumer[Src, Out]` |
| Class attributes | `component_id`, `source` or `topic`, `message_codec`; for a topic, `start_from`, `contract`, `broker`, `parallel`; to publish, `produces_to` (a topic, or a `Publication` with its contract and broker) and `out_codec` |
| Must define | `async on_message(self, message) -> ConsumerEffect` |
| May override | `on_delete(self)`, which ignores by default |
| In a handler | `self.metadata` (`subject`, `sequence_number`), `self.client`, `self.effects` |
| Effects | `produce(out, metadata=None)`, `produce_all(messages)`, `done()`, `ignore()` |
| One of several messages | `self.effects.message(out, key=None, metadata=None)`; `key` is a record key other than the message's subject |

Delivery is at least once. A consumer that produces needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the sidecar.
`produce_all` publishes its messages in order, and the change is handled when the broker has accepted all
of them; an empty list is handled at once. See [Consumers](../build/consumers.md).

## Graph consumer

A consumer that publishes its source as graph deltas. It is registered as a consumer and can publish
nothing but deltas.

| Part | API |
|---|---|
| Base class | `ankka.GraphConsumer[Src]`, in `ankka.graph` |
| Class attributes | `component_id`, `source` or `topic`, `message_codec`, `produces_to`; `contract`, `broker`, `parallel` for a topic; no `out_codec` |
| Must define | `on_message(self, message) -> GraphEffect`, which may be `async` |
| May override | `on_delete(self)`, which ignores by default |
| In a handler | `self.metadata`, `self.client`, `self.graph`, `self.effects` |
| Elements | `self.graph.node(id, labels=(), properties=None, version=None)`, `edge(id, type=, from_id=, to_id=, properties=None, version=None)`, `tombstone_node(id, version=None)`, `tombstone_edge(id, type=, from_id=, to_id=, version=None)` |
| Effects | `self.effects.publish(elements)`, `done()`, `ignore()` |
| A refusal | `graph.RefusedElement`, a `ValueError` whose `why` names the rule broken |

| In `ankka.graph` | What it is |
|---|---|
| `Element` | A frozen dataclass: `kind`, `element`, `id`, `version`, `labels`, `type`, `from_id`, `to_id`, `properties`, and `key`, the record key |
| `read(value, key=None)` | Reads a record's value into an `Element`; given a key, also checks it is the delta's element key. Raises `ValueError` naming the fault. |
| `CODEC` | The delta codec, under the manifest `ankka.graph-delta.v1`: a `message_codec` for a consumer that reads a delta topic |
| `node_key(id)`, `edge_key(id)`, `SCHEMA_NAME` | The element keys and the contract's name |

A property value is a `str`, an `int`, a `float`, a `bool`, or a list of one of those; a `bool` is not an
integer. See [Publish a graph](../build/graph.md).

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
| In a handler | `self.metadata` (the timer's name and attempt count), `self.due_time` (the due time the run is for, a UTC `datetime`), `self.client`, `self.effects` |
| Effects | `done()`, `fail(msg, code)` |
| Scheduling | `await client.timers.schedule(timer_id, timedelta, component_id, action, input)`, `await client.timers.schedule_recurring(timer_id, delay, period, component_id, action, input)`, `await client.timers.cancel(timer_id)` |

Scheduling twice under one id replaces the earlier timer, except that a recurring timer set again for
the same handler with the same period keeps its next due time. See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Base class | `ankka.agent.Agent` |
| Class attributes | `component_id`, `tools` (`{name: Tool(description, function, InputDataclass, approval=…)}`), `guardrails` (`{name: Guardrail(check)}`), optionally `role`, `max_tool_call_steps`, `mcp_servers` (`{name: McpServer(url=…, service=…, project=…, path=…, headers={header: "ANKKA_MCP_…"}, approval=…)}`), `result_guardrails` (`{name: ResultGuardrail(check)}`) |
| Approval | `approval=True`, or `Approval(within=timedelta(…))` for a time limit |
| Calling | `client.for_agent(id, session).call(name)`: `await .ask(input, reply=T)` → `Answered(value)` or `AwaitingApproval(requests)`; `await .decide(approval_id, approved=…, by=…, note=…)`, answered as `ask`; `.stream_parts(input)`; `.invoke` and `.stream` raise `ApprovalAwaited` |
| Decorators | `@command("name")`, `@stream("name")` from `ankka.agent` |
| In a handler | `self.session_id`, `self.metadata`, `self.client`, `self.effects` |
| Effects | `system_message(t)`, `user_message(t)`, `model(name)`, then `.with_model(name)`, `.with_context(t)`, `.memory(bool)`, `.tools(*names)`, `.guardrails(*names)`, `.then_reply()`, `.then_reply_json()`; `error(msg, code)` |

A handler returns a plan; the sidecar runs the model loop, calls tools back in the process with the model's
arguments, and checks guardrails. A guardrail's check takes the stage (`"input"` or `"output"`) and the text,
and returns `None` to pass or a reason to block; a result guardrail's takes the MCP tool's name and what it
answered. The sidecar connects to the MCP servers and enforces approval, so the process never runs a server's
tool nor a tool that awaits a decision. A runtime before protocol 1.11 answers `decide` with a `CommandError`
saying it is too old. The unit kit takes scripted servers, `AgentTestKit.of(Agent, mcp={server: {tool: fn}})`,
and its reply's `awaiting` lists what a turn waits on; `kit.decide(approval_id, approved, by, note)` goes on.
See [Agents](../build/agents.md) and [MCP servers](../build/mcp-servers.md).

## Autonomous agent

| Part | API |
|---|---|
| Task type | `TaskType(name, description, result=Dataclass, rules=(TaskRule(name, check),))`; a check returns `Accepted()` or `Rejected(reason)` |
| Base class | `ankka.autonomous.AutonomousAgent` |
| Class attributes | `component_id`, `description`, `accepts = [TaskAcceptance(task_type, max_iterations=n)]`, optionally `instructions`, `tools`, `guardrails`, `model`, `settings`, `mcp_servers`, `result_guardrails` |
| In a tool | `self.client`, `self.task_id` |
| Tasks | `await client.tasks.create(task_type, instructions, id=…, attachments=[…], depends_on=[…])` |
| A task | `client.for_task(id)`: `await .get(task_type)`, `.wait(task_type, timeout)`, `.cancel(reason)` |
| An instance | `client.for_autonomous_agent(Agent, instance_id)`: `.assign(*ids)`, `.suspend()`, `.resume()`, `.terminate()`, `.state()` (with `awaiting`), `.notifications()`, `.decide(approval_id, approved, by, note=None)` |
| One task | `await client.for_autonomous_agent(Agent).run_single_task(task_type, instructions)` |
| Testing | `AutonomousAgentTestKit.of(Agent)`: `run_tool`, `check_result`, `check_guardrail`; the integration kit's `await_task` and `notifications`, with `ANKKA_MODEL_SCRIPT` turns that may carry `when` or `when_tool_result` |

The sidecar runs the loop and keeps every record; the process runs the tools, checks guardrails, and checks a
result against its task type and rules. See [Autonomous agents](../build/autonomous-agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Base class | `Endpoint` |
| Class attributes | `prefix`, `acl` (required: `Acl.ALLOW_ALL`, `Acl.DENY_ALL`, `Acl.AUTHENTICATED` or `Acl.allow_callers(...)`) |
| Named events | an `@sse` handler may yield `SseEvent(name, value)` among its text, sent as an event of that name with the value as JSON (protocol 1.11) |
| Decorators | `@get`, `@post`, `@put`, `@patch`, `@delete`, `@sse`, `@socket`, each with a path template and an optional `acl=` for that route alone |
| A socket | the handler's parameter annotated `Socket`: `async for text in socket`, `await socket.receive()` (`None` once closed), `await socket.send(text)`, which raises `SocketClosed` once closed; testing, `EndpointTestKit.socket(path, frames)` answers what the handler sent and how it ended |
| Handlers | `async` methods; path parameters bind by name, one further typed parameter is the body, the return value is encoded by its type |
| Callers | `Callers.internet`, `Callers.service(name, project=None)`, `Callers.any_in_project`, `Callers.self_` |
| In a handler | `self.request`: `query_param`, `query_params`, `header`, `principal` (`subject`, `name`, `email`, `email_verified`, `roles`, `claims`, `issuer`), `metadata`, `caller` — a `Gateway`, `ServiceCaller(project, name)` or `LocalCaller` |
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

## Secret store

The service's secret store, for a value it must keep and never record, such as a credential a person
gave it ([Secrets a service keeps](../build/secrets.md)). An endpoint, a consumer, a graph consumer, a
timed action, an agent and an autonomous agent reach it as `self.secrets`, and a workflow in a step; an
entity's `CommandContext` and a `View` have no `secrets` at all, and a workflow's command handler is
refused. `await self.secrets.put(name, value)`, `await self.secrets.get(name)` (the value, or `None`) and
`await self.secrets.delete(name)`. A refusal is a `CommandError` with the runtime's code.

The runtime beside the process holds the store and its key; the process never sees the key. For a unit
test, assign `component.secrets = InMemorySecrets()`, which applies the runtime's rules. The integration
test kit puts a generated `ANKKA_SECRET_KEY` on the runtime it starts; `env={"ANKKA_SECRET_KEY": ""}`
starts one with none. Needs protocol 1.4: an earlier runtime is reported as too old for the store.

## Other services

An endpoint, a consumer, a graph consumer, a timed action, an agent and an autonomous agent call another
service as this one through `self.services`, and a workflow in a step; an entity's `CommandContext` and a
`View` have none. `self.services("orders")` or `self.services("invoices", project="billing")` answers a
client with `await get(path, returns)`, `get_text(path)`, `post(path, body, returns)`,
`put(path, body, returns)`, `delete(path)` and `request(method, path, body=, content_type=, headers=)`.
No answer is `ServiceUnresolvable`, `ServiceIdentityMismatch` or `ServiceUnanswered`; an answer outside 2xx
to a typed helper is `ServiceCallFailed`; all four are `ServiceError`. For a unit test, assign
`component.services = ScriptedServices()`. Needs protocol 1.8: an earlier runtime is reported as too old
to call another service. See [Calling other services](../build/calling-services.md).

## Running a service

```python
import asyncio
from ankka import Ankka

service = (
    Ankka.service()
    .register(ShoppingCartEntity)
    .register(ShoppingCartEndpoint)
)

asyncio.run(service.listen())
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
| `ConsumerTestKit.of(Cls)` | `ankka.testkit` | A consumer's `on_message(message, subject, sequence=None)` and `on_delete`. `produced` holds each payload it published and `messages` each `Produced(payload, key, metadata)`. |
| `GraphConsumerTestKit.of(Cls, client=None)` | `ankka.testkit` | A graph consumer's `on_message(message, subject, sequence=1)` and `on_delete(subject, sequence=1)`, each returning the `Element`s published, read back from their bytes. |
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
