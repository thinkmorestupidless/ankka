# Testing

> Test ankka components at two levels in Scala, Python and TypeScript — unit test kits that run a component with nothing else, and integration test kits that run the whole service against a real database — with scripted models for agents.

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

## Unit testing a component

A unit test kit hosts one component with nothing else — no actor system, no cluster, no database, no
sidecar. `call` runs a handler and returns what the effect it produced would have done:

**Scala**

```scala
val kit    = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")
val result = kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 2))

assertEquals(result.replyValue, Done)
assertEquals(result.events, Vector(ItemAdded(LineItem("p1", "Widget", 2))))
assertEquals(kit.currentState.totalQuantity, 2)
```

**Python**

```python
from ankka.testkit import EventSourcedTestKit

kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
assert kit.call("add-item", LineItem("p1", "Pen", 2)).events == (ItemAdded(LineItem("p1", "Pen", 2)),)
assert kit.call("get-cart").reply.items[0].name == "Pen"
```

**TypeScript**

```ts
test("adds an item and replies done", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
  assert.deepEqual(result.events, [{ type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } }])
  assert.equal(result.reply, done)
  assert.equal(kit.state.items.length, 1)
})
```

Scala names the handler with the typed value its companion declared; Python and TypeScript name it
by its wire name or its handler reference. What each kit hands back differs a little by language.

### What a Scala kit gives you


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

### What a Python kit gives you


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

### What a TypeScript kit gives you

`ankka/testkit` holds the same set, and every call is awaited:

| Kit | Drives |
|---|---|
| `EventSourcedTestKit.of(Entity, id)` | `call(handler, input)`; the result has `events`, `newState`, `reply`, `error`, `noReply`, `retention` |
| `KeyValueTestKit.of(Entity, id)` | the same, with `changed` in place of `events` |
| `WorkflowTestKit.of(Workflow, id)` | `call` a command, `runStep` a step, `runUntilEnd` and `resume` to follow transitions |
| `ViewTestKit.of(View)` | `onChange(key, event)`, `onDelete(key)`, then `get(key)` for the row |
| `ConsumerTestKit.of(Consumer)` | `onMessage(message, subject)`, `onDelete(subject)` |
| `TimedActionTestKit.of(Action)` | `invoke(action, input)` |
| `AgentTestKit.of(Agent, session, model)` | a handler plus the loop the sidecar would run, against a `ScriptedModel` |
| `EndpointTestKit.of(Endpoint)` | `get`, `post`, `put`, `delete` against the routes, returning a `Response` |

`ScriptedModel` scripts turns with `expectText`, `expectToolCall` and `expectRefusal`, and fails loudly
when the script runs out.

## Integration testing

An integration test kit starts the whole service against a throwaway Postgres in Docker and drives it
as a caller would. Restarting inside a test drops everything held in memory, so a test that passes
across a restart has proved durability rather than caching:

**Scala**

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

**Python**

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
        # Kept after the checkout, as the Scala cart, and refusing changes after a restart too.
        await kit.restart()
        assert (await kit.http.get("/carts/c1")).json() == {"cartId": "c1", "items": [PEN_JSON, INK_JSON], "checkedOut": True}
        assert (await kit.http.post("/carts/c1/items", json=PEN_JSON)).status_code == 409

        # Discarding deletes a cart, so the id is fresh again.
        assert (await kit.http.post("/carts/c2/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.delete("/carts/c2")).status_code == 204
        assert (await kit.http.get("/carts/c2")).json() == {"cartId": "c2", "items": [], "checkedOut": False}
```

**TypeScript**

```ts
test("items survive the sidecar restarting", async () => {
  const kit = await AnkkaTestKit.start(service())
  try {
    const added = await kit.http.post("/carts/c1/items", { productId: "p1", name: "Pen", quantity: 2 })
    assert.equal(added.status, 204)
    await kit.restart()                                  // a new sidecar, the same database
    const cart = (await kit.http.get("/carts/c1")).json() as { items: unknown[] }
    assert.equal(cart.items.length, 1)
  } finally {
    await kit.stop()
  }
})
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

In Python and TypeScript the kit starts the real sidecar image beside Postgres, serves your
components from the test process, and drives the routes through the sidecar. `restart()` replaces the
sidecar against the same database. Mark the Python tests `@pytest.mark.slow` and run them with
`uv run pytest -m slow`; the TypeScript ones are skipped unless `ANKKA_SLOW=1` is set, so that neither
suite needs Docker by default.

Mark such tests `@pytest.mark.slow` and run them with `uv run pytest -m slow`; `uv run pytest` runs the
unit tests alone.

### Quiet output from a Scala suite

An integration test starts a whole service, and a service logs as it starts, forms its cluster, runs
projections and stops. Across a suite that is hundreds of lines, none of which matter unless a test
fails. Mix `LogCapturing` into a munit suite to hold that log back:

```scala
import com.thinkmorestupidless.ankka.testkit.LogCapturing

class ShoppingCartIntegrationSuite extends munit.FunSuite with LogCapturing
```

While the suite runs, everything logged through logback's root logger is held in memory. A test that
passes discards what was logged since the previous test finished. A test that fails prints it, under a
header naming the test, so the log that explains the failure is still there. A suite whose `beforeAll`
fails, so that no test runs, prints what its setup logged, and so does a test whose `beforeEach` fails.

- **Levels still come from your logback configuration.** Capture changes where an event goes, not
  whether it is logged, so a logger set to `WARN` is no noisier when a test fails.
- **It ends when the suite does.** A suite without `LogCapturing` that runs after one with it, in the
  same test JVM, logs as it always did. The suite's own `afterAll` runs after capture has ended, so what
  a teardown logs is printed.
- **Output printed directly** to standard output or standard error, not through a logger, is not
  captured.
- **To see everything**, as when a test passes and you want to know why, set `ANKKA_TEST_LOGS=all`, or
  `-Dankka.test.logs=all` on the test JVM.

## A test must be able to fail

A test is worth what it would catch. Before relying on one, ask of it: **could this pass while the
behaviour it names is broken?** If you can describe such a case, the test is not yet checking what its
name says. The same question applies to any check that gates a change: a CI step, a smoke test, a
readiness wait. These are the ways an ankka test most often passes for the wrong reason:

- **Asserting a status and not the answer.** A `204` or a `200` says the request was handled, not that
  it did the right thing, and a `404` from a mistyped path is a successful HTTP exchange. Assert the
  body, or read the state the request should have changed.
- **Retrying on the wrong thing.** A retry that waits for "a row exists" is satisfied by a row from an
  earlier write. Retry on the value that changes, and assert what must not change.
- **Proving memory instead of durability.** A test that writes and reads back without a restart passes
  on state that was never persisted. A durability claim needs `restartService()` or `restart()` between
  the write and the read.
- **A test that never ran.** A filter that matches no test, a skip condition that is always true, or a
  test marked slow that no run includes reports green for work that did not happen. Check the count of
  tests run, not only the exit code.
- **Asserting that something appears, not its shape.** A string found somewhere in a rendered document
  or a response passes when it appears in the wrong place too. Assert the structure the behaviour must
  produce.
- **Deciding from a view what must be exact.** A view lags its source. A test that asserts on a view once,
  immediately after a write, passes or fails by timing; read your own write from the entity.
- **Checking the code against itself.** A test whose expected value was copied from the implementation's
  output agrees with the implementation by construction. Take expected values from the requirement.

The quickest proof that a test can fail is to break the behaviour on purpose and watch the test go red,
then put it back.

## Acceptance scenarios as integration tests

An acceptance scenario — given some state, when a caller does something, then an answer and a resulting
state — is an integration test waiting to be written. Written as one, it runs on every change, so the
scenario keeps holding after the feature is finished, and a build that must pass its tests cannot ship a
regression of it.

Each part of the scenario has one place in the test:

| Scenario | In the test |
|---|---|
| Given | calls that put the service in the starting state, on ids this test alone uses |
| When | the request the scenario describes, through the same route a caller uses (`kit.http` or an HTTP client against the kit's port), so the endpoint's ACL and error mapping are part of what is tested |
| Then, the answer | the status and the part of the body the scenario names |
| Then, the state | a query to the entity through the component client or a route; a view or consumer effect by retrying on the value that changes |
| Must survive a restart | `restartService()` or `restart()`, then the same reads |

Name the test after the scenario, in the scenario's words, so a failure reads as the requirement that
broke. A scenario that cannot be written this way, because nothing observable distinguishes "met" from
"not met", is not yet a requirement a test can hold; it needs a sharper statement before it needs a test.

### Scenarios written in Gherkin

Scenarios can live in Gherkin `.feature` files, in the domain's own words, and run as a Scala suite.
`GherkinSuite` makes each scenario a munit test, and each row of a `Scenario Outline`'s `Examples`
its own test, named with its file and line:

```gherkin
Scenario: adding a product the cart already holds adds to that product's quantity
  Given a cart holding 2 of "Widget"
  When the customer adds 3 of "Widget"
  Then the cart holds 5 of "Widget"
  And the cart holds 5 items in total
```

Steps are defined with Cucumber Expressions, `{int}`, `{string}` and the rest, and a definition
matches whatever keyword a step was written with. These drive the shopping cart through its HTTP routes,
so a scenario checks the routes, the entity's rules and the journal together:

```scala
Given("a cart holding {int} of {string}") { (quantity: Int, product: String) =>
  setUp(add(quantity, product))
}

When("the customer adds {int} of {string}") { (quantity: Int, product: String) =>
  last = add(quantity, product)
}

Then("the cart holds {int} of {string}") { (quantity: Int, product: String) =>
  assertEquals(quantityOf(product), quantity)
}
```

The suite takes the directory of its features, relative to the working directory; a forked sbt test runs
in its project's directory, so `GherkinSuite("features")` reads the project's own `features/`. It mixes
in like any suite, `LogCapturing` included, and starts its service in `beforeAll` as the integration test
kit does.

- **An undefined step fails its scenario**, naming the step and its line and printing a definition to
  paste. A step two definitions match fails too, naming both, and so does one whose values do not match
  its definition's parameters.
- **A directory with no scenarios fails the suite.** A suite that ran no scenarios has checked nothing.
- **A scenario tagged `@ignore` is reported ignored**, never passed.
- **Each scenario's state is its own**: `scenarioId` is unique to the running scenario, for entity ids no
  other scenario uses. Scenarios run one at a time on one suite instance.
- **A step's `DocString` or `DataTable` is the definition's last value**, as a `String` or a
  `Seq[Seq[String]]`.
- **Assert what the scenario names.** A refusal is checked by its status and by the entity's own message:
  a status alone passes for any refusal of that kind, including the wrong one.

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

### Scripting judgments

`TestJudgmentProvider` does for [judgments](judgments.md) what `TestModelProvider` does for text, and it
is a separate script, so neither consumes the other's. Give it to the runtime with
`AgentRuntime.withDefaultModel(model).withJudgments(judge)`.

| Method | Scripts |
|---|---|
| `expect(answers*)` | One judgment's answers, taken in order. |
| `always(answers*)` | Standing answers by question, used whenever the question is asked without consuming the queue — for a guardrail asked on every request. |
| `failNext(message, timedOut)` | One judgment that fails as a provider's outage would; each call queues one. |
| `reporting(usage)` | The tokens every judgment reports; zero unless set. |

`Answers.choice(question, value, confidence)`, `Answers.score(question, score)` and
`Answers.yesNo(question, probability)` script an answer by its value, with probabilities and a confidence
consistent with it; `Answers.choiceWith` and `Answers.scoreWith` take the full probabilities. Each is
checked against its question as it is written. A question the script has no answer for, or an answer for a
question that was not asked, fails the call naming the question. `requests`, `lastRequest` and `callCount`
show what was asked, and `reset()` clears everything, the reported usage included.

### Scripting a model in Python and TypeScript

`AgentTestKit` runs the handler and then the loop the sidecar would run, against a `ScriptedModel`
that fails loudly the same way:

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
