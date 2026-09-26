# Testing

> Test ankka components at two levels in Scala and Python — unit test kits that run a component with nothing else, and integration test kits that run the whole service against a real database — with scripted models for agents.

Source: https://docs.ankka.cloud/build/testing/
ankka services are tested at two levels, and both are real.

- **Unit tests** run one component with no actor system, no cluster, no database and no sidecar. A
  handler returns an effect, which is a plain value, so the test kit can apply it and show what it would
  have done. These run in milliseconds. Inputs, events, state and replies still pass through the
  component's own serialisers, so a type the codec cannot encode fails here rather than on the first
  deployment.
- **Integration tests** start the whole service against a throwaway Postgres in Docker, and drive it
  through the component client or over HTTP. Restarting the service inside a test drops everything held
  in memory, so a test can prove that state was persisted rather than cached.

Docker is the only requirement for integration tests. No model API key is needed at either level: agents
are tested against a scripted model.

## Unit testing an entity in Scala

`EventSourcedTestKit.of(companion, entityId)` hosts one entity instance. `call` runs a handler and
returns a result holding the events it persisted, its reply or its refusal:

```scala
val kit    = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")
val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))

assertEquals(result.replyValue, Done)
assertEquals(result.events, Vector(ItemAdded(LineItem("p1", "Widget", 2))))
assertEquals(kit.currentState.totalQuantity, 2)
```

| On the result | Meaning |
|---|---|
| `replyValue` | The reply; fails the test if the command was refused. |
| `events` | The events this call persisted. |
| `eventOfType[T]` | The one event of type `T`. |
| `persisted` | Whether any event was persisted. |
| `isError`, `error`, `errorMessage` | The refusal, with its `ErrorCode`. |
| `retention` | A deletion or expiry the call requested. |

| On the kit | Meaning |
|---|---|
| `currentState` | The state after every call so far. |
| `allEvents` | Every event persisted so far, in order. |
| `isDeleted` | Whether the entity has been deleted. |

A refused command persists nothing, and the kit shows exactly that: `result.events` is empty and the
state is unchanged. `KeyValueEntityTestKit.of(companion, entityId)` does the same for a key value entity,
with `changed` on the result instead of `events`:

```scala
val kit    = KeyValueEntityTestKit.of(ProfileEntity, "user-1")
val result = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
assert(result.changed)
assertEquals(kit.currentState, Profile("Ada", "ada@example.com", 1))
```

Workflows, views, consumers, timed actions and agents are tested in Scala through the integration test
kit, because what matters about them — transitions and recovery, projection, delivery, the agent loop —
is the runtime's behaviour.

## Integration testing in Scala

`AnkkaTestKit.start(descriptors, extensions)` starts Postgres in Docker, applies the runtime's schema,
and hosts the components with the given extensions. It returns once the service's node has joined its
cluster, so a test's first call cannot race startup.

```scala
class ShoppingCartIntegrationSuite extends munit.FunSuite:
  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(ShoppingCartEntity.descriptor)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  private def cart(id: String) = testKit.componentClient.forEventSourcedEntity(EntityId(id))

  test("state survives losing every entity from memory") {
    cart("c1").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 5))
    val before = cart("c1").call(ShoppingCartEntity.getCart).invoke()

    testKit.restartService()

    assertEquals(cart("c1").call(ShoppingCartEntity.getCart).invoke(), before)
  }
```

`restartService()` stops the service and starts a new one against the same database, so every entity
must rebuild itself from the journal. A test that passes across a restart has proved durability. One
that does not restart has only proved that something was in memory.

The schema the test kit applies is the same one local development and the platform use, so a test cannot
pass against a schema a deployment does not have.

### HTTP in an integration test

Serve endpoints on a free loopback port, never the default 9000. A developer running a service locally
while running the tests would otherwise see `Address already in use`:

```scala
val server  = HttpServer.at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))
val testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(server))
val baseUrl = s"http://127.0.0.1:\${server.boundPort.get}"
```

### Timers and projections in an integration test

Register the extension the component needs, as the service itself would: `TimerRuntime` for timed
actions, `ProjectionRuntime()` for views and consumers. A shorter timer poll interval keeps a timer test
fast, as in [Timers](timers.md#testing-timers). Views and consumers see changes after the write returns,
so assert on them by retrying until the expected value appears, and retry on the value that changes
rather than on the mere presence of a row.

## Testing agents with a scripted model

`TestModelProvider` answers from a script, in order, and **fails loudly when the script runs out**. A
test whose model quietly returned a default would no longer be testing what it says.

```scala
val model = TestModelProvider()
  .expectToolCall("get_weather", Json.obj("location" -> Json.str("Lisbon")))
  .expectText("Lisbon is 18C and sunny.")
```

| Method | Scripts |
|---|---|
| `expectText(text)` | A plain reply. |
| `expectToolCall(name, arguments)` | A turn in which the model calls one tool. |
| `expectParallelToolCalls(calls*)` | A turn calling several tools at once. |
| `expectRefusal(reason)` | The model declining. |
| `whenUserSays(substring)(reply)` | A standing rule, used once the ordered script is exhausted. |

`requests`, `lastRequest` and `callCount` show what the model was sent, so a test can assert that a tool
result or a piece of context reached it. `reset()` clears the script between tests. Hand the provider to
the runtime as its default model:

```scala
override def beforeAll(): Unit =
  testKit = AnkkaTestKit.start(
    Seq(
      PreferencesEntity.descriptor,
      PlannerWorkflow.descriptor,
      SelectorAgent.descriptor,
      WeatherAgent.descriptor,
      ActivityAgent.descriptor,
      BudgetAgent.descriptor,
      SummaryAgent.descriptor
    ) ++ AgentRuntime.descriptors,
    Seq(AgentRuntime.withDefaultModel(model))
  )

override def afterAll(): Unit = if testKit != null then testKit.stop()

override def beforeEach(context: BeforeEach): Unit = model.reset()
```

Assert on coordination and state, not on prose: which tools ran with which arguments, which agents
contributed, what reached memory. [Multi-agent orchestration](multi-agent-orchestration.md#testing-orchestration)
shows a full example.

Two rules keep scripted-model tests honest:

- **One provider per consumer of it.** Compaction runs asynchronously, as a consumer. If the agent and
  the summariser share one scripted provider, which of them takes the next scripted response is a race.
  Give each its own provider.
- **Let a workflow finish before the test ends.** A workflow left mid-flight keeps taking responses from
  a shared script, starving the next test.

## Unit testing in Python

`ankka.testkit` holds a unit test kit for every component kind, and none of them needs a sidecar. Calls
take the handler's wire name:

```python
from ankka.testkit import EventSourcedTestKit

kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
assert kit.call("add-item", LineItem("p1", "Pen", 2)).events == (ItemAdded(LineItem("p1", "Pen", 2)),)
assert kit.call("get-cart").reply.items[0].name == "Pen"
```

| Kit | Drives |
|---|---|
| `EventSourcedTestKit.of(Entity, id)` | commands; the result has `events`, `reply`, `error`, `persisted`, `retention` |
| `KeyValueTestKit.of(Entity, id)` | commands on a key value entity |
| `WorkflowTestKit.of(Workflow, id)` | `call` a command, `run_step` a step, `run_until_end` to follow transitions |
| `ViewTestKit.of(View)` | `on_change(key, event)`, `on_delete(key)`, then `get(key)` for the row |
| `ConsumerTestKit.of(Consumer)` | `on_message(message, subject)`, `on_delete(subject)` |
| `TimedActionTestKit.of(Action)` | `call(name, input)` |
| `AgentTestKit.of(Agent, session, model)` | a handler plus the loop the sidecar would run, against a `ScriptedModel` |
| `EndpointTestKit.of(Endpoint, *args)` | `get`, `post`, `put`, `delete` against the routes, returning a `Response` |

A component's client calls are refused inside a unit test kit, because there is nothing to call. Test a
component that calls others at the integration level.

A workflow's commands and steps can be run by hand:

```python
def test_checkout_workflow_declares_its_recovery() -> None:
    kit = WorkflowTestKit.of(CheckoutWorkflow, "c1")
    started = kit.call("start", "fail")
    assert started.transition is not None and started.transition.step == "reserve"
    assert kit.state.status == "reserving" and kit.state.mode == "fail"
    assert kit.call("start", "ok").error is not None
    assert kit.run_step("compensate").next == End()
    assert kit.state.status == "compensated"
    settings = CheckoutWorkflow.to_component().workflow.settings
    assert {s.step: s.recovery.failover_to for s in settings.steps} == {"charge": "compensate"}
```

`AgentTestKit` runs the handler, then the loop the sidecar would run, against a `ScriptedModel`: tools run
in-process with the scripted arguments, and guardrails are checked. `ScriptedModel` fails loudly when it
runs out, like `TestModelProvider`:

```python
def test_assistant_plans_and_the_tool_reads_the_cart() -> None:
    from ankka.testkit import AgentTestKit, ScriptedModel
    from examples.shopping_cart.assistant import CartAssistant

    model = ScriptedModel().expect_tool_call("lookup", {"cartId": "c9"}).expect_text("Your cart is empty.")
    answer = AgentTestKit.of(CartAssistant, "s1", model).call("ask", "what is in cart c9?")
    assert answer.plan.tool_names == ("lookup",) and answer.plan.guardrail_names == ("no-secrets",)
    assert answer.reply == "Your cart is empty."
    # The tool ran in this process — the unit testkit's client answers nothing, so it reports that.
    assert answer.tool_results and answer.tool_results[0].startswith("error:")
```

## Integration testing in Python

`ankka.testkit.integration.AnkkaTestKit` starts Postgres and the real sidecar image in Docker, serves
your components from the test process, and drives the routes through the sidecar with `kit.http`, an
`httpx` client pointed at it. `restart()` replaces the sidecar against the same database, so the next read
has to rebuild from the journal:

```python
@pytest.mark.slow
async def test_cart_through_the_sidecar_survives_a_restart() -> None:
    service = Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)
    async with await AnkkaTestKit.start(service) as kit:
        assert (await kit.http.post("/carts/c1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/c1/items", json=INK_JSON)).status_code == 204
        cart = (await kit.http.get("/carts/c1")).json()
        assert cart == {"cartId": "c1", "items": [PEN_JSON, INK_JSON], "checkedOut": False}
        assert (await kit.http.get("/carts/c1/total")).json() == 3
        # A refusal reaches the caller as its status.
        assert (await kit.http.post("/carts/c1/items", json={**PEN_JSON, "quantity": 0})).status_code == 400
        assert (await kit.http.delete("/carts/c1/items/nope")).status_code == 404

        await kit.restart()
        assert (await kit.http.get("/carts/c1")).json()["items"] == [PEN_JSON, INK_JSON]

        checked = (await kit.http.post("/carts/c1/checkout")).json()
        assert checked["checkedOut"] is True
        # Deleted after the checkout, as the Scala cart: the id is fresh again.
        assert (await kit.http.get("/carts/c1")).json() == {"cartId": "c1", "items": [], "checkedOut": False}
```

Mark such tests `@pytest.mark.slow` and run them with `uv run pytest -m slow`; `uv run pytest` runs the
unit tests alone.

To test an agent through the real sidecar, script the sidecar's model with `ANKKA_MODEL_SCRIPT`, passed
through `env`. The script is a JSON array of turns — `{"text": ...}`, `{"tool": name, "arguments": {...}}`,
`{"tools": [...]}` for several at once, `{"refusal": ...}` — consumed in order, plus standing rules of the
form `{"when": "<substring of the user's message>", "text": ...}` used once the turns run out:

```python
SCRIPT = json.dumps(
    [
        {"tool": "lookup", "arguments": {"cartId": "a1"}},
        {"text": "Your cart holds 2 x Pen and 1 x Ink."},
        {"text": "Streamed answer here"},
        {"text": "the key is sk-000"},
    ]
)


@pytest.mark.slow
async def test_assistant_through_the_sidecar_with_a_scripted_model() -> None:
    """The loop runs in the sidecar against its scripted model; the tool runs here and reads the
    cart through the client; tokens stream back as SSE; the guardrail here blocks a leak."""
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": SCRIPT}) as kit:
        assert (await kit.http.post("/carts/a1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/a1/items", json=INK_JSON)).status_code == 204
        # A str body and a str reply are text/plain, as a Scala endpoint's String is.
        asked = await kit.http.post("/carts/ask/s1", content="what is in cart a1?", headers={"content-type": "text/plain"})
        assert asked.status_code == 200, asked.text
        assert asked.text == "Your cart holds 2 x Pen and 1 x Ink."
        async with kit.http.stream("GET", "/carts/chat/s2?q=hello") as r:
            body = "".join([chunk async for chunk in r.aiter_text()])
        frames = [line[len("data:") :].strip() for line in body.splitlines() if line.startswith("data:")]
        assert [json.loads(f) for f in frames] == ["Streamed", " answer", " here"], f"body: {body!r}\n{kit.sidecar_logs()[-2500:]}"
        leaked = await kit.http.post("/carts/ask/s3", content="key?", headers={"content-type": "text/plain"})
        assert leaked.status_code == 403, leaked.text
```

`ANKKA_MODEL_SCRIPT` may also name a file holding the script. A sidecar with an `ANTHROPIC_API_KEY` uses
the real model even when a script is set.
