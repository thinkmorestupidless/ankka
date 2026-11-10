# Testing

> Test ankka components at two levels in every language, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

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

**Java**

```java
@Test
void anItemAddedIsInTheCart() {
  var cart = EventSourcedTestKit.of(ShoppingCart.class, "c1");
  var result = cart.call(ShoppingCart::addItem, new LineItem("p1", "Pen", 2));
  assertEquals(Done.DONE, result.reply());
  assertEquals(List.of(new CartEvent.ItemAdded(new LineItem("p1", "Pen", 2))), result.events());
  assertEquals(2, cart.state().totalQuantity());
}
```

**Kotlin**

```kotlin
@Test
fun `an item added is in the cart`() {
    val cart = eventSourcedTestKit(ShoppingCart::class, "c1")
    val result = cart.call(ShoppingCart::addItem, LineItem("p1", "Pen", 2))
    assertEquals(Done.DONE, result.reply())
    assertEquals(listOf<CartEvent>(CartEvent.ItemAdded(LineItem("p1", "Pen", 2))), result.events())
    assertEquals(2, cart.state().totalQuantity)
}
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

**Rust**

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

**Go**

```go
func TestAddingAndRemovingItems(t *testing.T) {
	ctx := context.Background()
	kit := testkit.EventSourced(cart.Entity, testkit.EntityID("c1"))
	if r := kit.Command(ctx, "add-item", pen); r.Refusal() != nil || len(r.Events()) != 1 || r.Events()[0] != (cart.ItemAdded{Item: pen}) {
		t.Fatalf("add-item answered %+v", r)
	}
	kit.Command(ctx, "add-item", ink)
	if q := kit.Query(ctx, "total-quantity", ankka.Unit{}); q.Reply() != 3 {
		t.Fatalf("the total is %v", q.Reply())
	}
	// Items are kept sorted by product, as the Scala sample keeps them.
	if got := kit.State().Items; len(got) != 2 || got[0] != ink || got[1] != pen {
		t.Fatalf("the items are %+v", got)
	}
	if r := kit.Command(ctx, "remove-item", "pen"); r.Refusal() != nil || r.Events()[0] != (cart.ItemRemoved{ProductID: "pen"}) {
		t.Fatalf("remove-item answered %+v", r)
	}
	if got := kit.State(); got.CartID != "c1" || len(got.Items) != 1 || got.Items[0] != ink {
		t.Fatalf("after removing, the cart is %+v", got)
	}
}
```

Scala names the handler with the typed value its companion declared; Java and Kotlin name it by a method
reference (`ShoppingCart::addItem`); Python, TypeScript, Rust and Go name it by its wire name or its handler
reference. What each kit hands back differs a little by language.

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

**Scala**

```scala
val kit    = KeyValueEntityTestKit.of(ProfileEntity, "user-1")
val result = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
assert(result.changed)
assertEquals(kit.currentState, Profile("Ada", "ada@example.com", 1))
```

**Java**

```java
public static Result<Done> recordCheckout() {
  var kit = KeyValueEntityTestKit.of(CheckoutLog.class, "c1");
  var result = kit.call(CheckoutLog::record, 1_700_000_000_000L);

  // result.reply() is Done, and result.state() is CheckoutRecord("c1", 1700000000000, true)
  return result;
}
```

**Kotlin**

```kotlin
val kit = KeyValueEntityTestKit.of(CheckoutLog::class.java, "c1")
val result = kit.call(CheckoutLog::record, 1_700_000_000_000L)

assertEquals(Done.done(), result.reply())
assertEquals(1_700_000_000_000L, kit.state().at)
assertEquals(true, kit.state().notified)
```

**Python**

```python
kit = KeyValueTestKit.of(ProfileEntity, "user-1")
result = kit.call("set", "Ada")
assert result.changed
assert kit.state == Profile("Ada", 0)
```

**TypeScript**

```ts
test("recording a checkout replaces the value", async () => {
  const kit = KeyValueTestKit.of(CheckoutLog, "c1")
  const recorded = await kit.call(CheckoutLog.handlers.record, 1700000000000n)
  assert.equal(recorded.reply, done)
  assert.equal(kit.state.notified, true)
  assert.equal(kit.state.at, 1700000000000n)
})
```

**Rust**

```rust
#[test]
fn recording_a_checkout_replaces_the_value_and_acknowledges() {
    let mut kit = KeyValueEntityTestKit::<CheckoutLog>::new("c1");
    let result = kit.command("record", 1_700_000_000_000_i64);
    assert_eq!(result.answer.reply::<Done>(), Ok(Done));
    assert!(result.written, "recording should write a new value");
    assert_eq!(result.state.at, 1_700_000_000_000);
    assert!(result.state.notified);
    assert_eq!(kit.state().cart_id, "c1");
}
```

**Go**

```go
func TestRecordingACheckoutReplacesTheValue(t *testing.T) {
	ctx := context.Background()
	kit := testkit.KeyValue(CheckoutLog, testkit.EntityID("c1"))
	defer kit.Close()
	r := kit.Command(ctx, "record", int64(1_700_000_000_000))
	if r.Refusal() != nil || r.Reply() != (ankka.Done{}) {
		t.Fatalf("record answered %v, %v", r.Reply(), r.Refusal())
	}
	if s := kit.State(); s.At != 1_700_000_000_000 || !s.Notified || s.CartID != "c1" {
		t.Fatalf("the record is %+v", s)
	}
	want := CheckoutRecord{CartID: "c1", At: 1_700_000_000_000, Notified: true}
	if q := kit.Query(ctx, "get", ankka.Unit{}); q.Reply() != want {
		t.Fatalf("get answered %+v", q.Reply())
	}
}
```

`KeyedViewTestKit(companion)` hands a keyed view's source handler one change and writes the rows it
names to a map the test reads, through the view's own serializer. A declared query is SQL, so the test says
what each answers with `answering(query)`, and a handler that asks one the test has not answered fails the
test, naming it:

**Scala**

```scala
val kit = KeyedViewTestKit(Shipments)
kit.answering(Shipments.ofCustomer)(values => kit.rows.values.filter(_.customer.contains(values("customer"))).toVector)
kit.change(Shipments.customers, "c1", CustomerRenamed("Ada"))
```

**Java**

```java
/** The right's change notes every row holding it, found by the view's own query. */
public static KeyedViewTestKit<JoinedRows.JoinedRow> rightNotesEveryRow() {
  var kit = KeyedViewTestKit.of(JoinedRows.class);
  kit.change(JoinedLeft.class, "a", new Noted("r1|b"));
  kit.change(JoinedLeft.class, "a", new Noted("r2|b"));
  kit.change(JoinedLeft.class, "a", new Noted("r3|c"));
  // The query is the database's to run; the test says what it answers.
  kit.answering(
      JoinedRows.OF_RIGHT,
      values ->
          kit.rows().values().stream()
              .filter(row -> row.holding().equals(values.get("holding")))
              .toList());
  kit.change(JoinedRight.class, "b", new Noted("anything"));
  // kit.row("r1") and kit.row("r2") carry the notes [left, right]; kit.row("r3") only [left]
  return kit;
}
```

**Kotlin**

```kotlin
val kit = KeyedViewTestKit.of(JoinedRows::class.java)
kit.change(JoinedLeft::class.java, "a", Noted("r1|b"))
kit.change(JoinedLeft::class.java, "a", Noted("r2|b"))
kit.change(JoinedLeft::class.java, "a", Noted("r3|c"))
// The query is the database's to run; the test says what it answers.
kit.answering(JoinedRows.OF_RIGHT) { values -> kit.rows().values.filter { it.holding == values["holding"] } }
kit.change(JoinedRight::class.java, "b", Noted("anything"))
assertEquals(listOf("left", "right"), kit.row("r1").orNull()?.notes)
assertEquals(listOf("left", "right"), kit.row("r2").orNull()?.notes)
assertEquals(listOf("left"), kit.row("r3").orNull()?.notes)
```

**Python**

```python
kit = KeyedViewTestKit.of(Shipments)
kit.change(CounterEntity, "s1", Incremented(1))
assert kit.get("s1") == ShipmentRow("s1", "c1", ("shipment",))
with pytest.raises(LookupError, match="of-customer"):
    kit.change(Customer, "c1", Note("renamed"))
kit.answering("of-customer", lambda values: [r for r in kit.rows.values() if r.customer == values["customer"]])
kit.change(Customer, "c1", Note("renamed"))
assert kit.get("s1") == ShipmentRow("s1", "c1", ("shipment", "renamed"))
```

**TypeScript**

```ts
test("the right's change notes every row holding it, found by the view's own query", async () => {
  const kit = KeyedViewTestKit.of(JoinedRows)
  await kit.change(JoinedLeft, "a", { text: "r1|b" })
  await kit.change(JoinedLeft, "a", { text: "r2|b" })
  await kit.change(JoinedLeft, "a", { text: "r3|c" })
  // The query is the database's to run; the test says what it answers.
  kit.answering("of-right", (values) => [...kit.rows.values()].filter((row) => row.holding === values.holding))
  await kit.change(JoinedRight, "b", { text: "anything" })
  assert.deepEqual(kit.get("r1")?.notes, ["left", "right"])
  assert.deepEqual(kit.get("r2")?.notes, ["left", "right"])
  assert.deepEqual(kit.get("r3")?.notes, ["left"])
})
```

**Rust**

```rust
#[test]
fn the_rights_change_notes_every_row_holding_it_found_by_the_views_own_query() {
    // The query is the database's to run; the test says what it answers.
    let mut kit = KeyedViewTestKit::<JoinedRows>::new().answering("of-right", |values, rows| {
        rows.iter()
            .filter(|row| row.holding == values["holding"])
            .cloned()
            .collect()
    });
    kit.change(Source::of(JoinedLeft), "a", noted("r1|b"));
    kit.change(Source::of(JoinedLeft), "a", noted("r2|b"));
    kit.change(Source::of(JoinedLeft), "a", noted("r3|c"));
    kit.change(Source::of(JoinedRight), "b", noted("anything"));
    assert_eq!(
        kit.row("r1").map(|r| r.notes),
        Some(vec!["left".to_string(), "right".to_string()])
    );
    assert_eq!(
        kit.row("r2").map(|r| r.notes),
        Some(vec!["left".to_string(), "right".to_string()])
    );
    assert_eq!(
        kit.row("r3").map(|r| r.notes),
        Some(vec!["left".to_string()])
    );
}
```

**Go**

```go
func TestTheRightsChangeNotesEveryRowHoldingIt(t *testing.T) {
	ctx := context.Background()
	kit := testkit.KeyedView(conformance.JoinedRows)
	defer kit.Close()
	for _, text := range []string{"r1|b", "r2|b", "r3|c"} {
		if _, _, err := kit.Change(ctx, conformance.JoinedLeft.ID(), "a", conformance.Noted{Text: text}); err != nil {
			t.Fatal(err)
		}
	}
	// The query is the database's to run; the test says what it answers.
	kit.Answering("of-right", func(values map[string]string) []conformance.JoinedRow {
		var rows []conformance.JoinedRow
		for _, r := range kit.Rows() {
			if r.Holding == values["holding"] {
				rows = append(rows, r)
			}
		}
		return rows
	})
	written, _, err := kit.Change(ctx, conformance.JoinedRight.ID(), "b", conformance.Noted{Text: "anything"})
	if err != nil || len(written) != 2 {
		t.Fatalf("the right wrote %v, %v", written, err)
	}
	for key, want := range map[string][]string{"r1": {"left", "right"}, "r2": {"left", "right"}, "r3": {"left"}} {
		if row, _ := kit.Row(key); !slices.Equal(row.Notes, want) {
			t.Fatalf("%s is noted %v", key, row.Notes)
		}
	}
}
```

`ConsumerTestKit.of(companion)` hands a consumer one change and returns what it would publish, and
`ConsumerTestKit.graph(companion)` does the same for a [graph consumer](graph.md), returning its deltas;
see [Testing a consumer](#testing-a-consumer).

A component that reads a workflow is handed the workflow's standing with each state. The kits take it as
an argument, `standing = Some(WorkflowLifecycle(...))` on `ConsumerTestKit`'s `onMessage` and on
`KeyedViewTestKit`'s `change`, and hand it to the handler as the change's context would carry it. Without
one, the handler is handed none, as it is for a change from an entity.

Workflows, views, timed actions and agents have unit kits too, which drive one handler with no runtime;
what matters most about them — transitions and recovery, projection, the agent loop — is the runtime's
behaviour, and is tested through the integration test kit.

### What a Python kit gives you


| Kit | Drives |
|---|---|
| `EventSourcedTestKit.of(Entity, id)` | commands; the result has `events`, `reply`, `error`, `persisted`, `retention` |
| `KeyValueTestKit.of(Entity, id)` | commands on a key value entity |
| `WorkflowTestKit.of(Workflow, id)` | `call` a command, `run_step` a step, `run_until_end` to follow transitions |
| `ViewTestKit.of(View)` | `on_change(key, event, standing=None)`, `on_delete(key)`, then `get(key)` for the row; `standing` is a workflow's `Standing`, for a view that reads one |
| `KeyedViewTestKit.of(KeyedView)` | `change(Entity, key, event)`, `deleted(Entity, key)`, `answering(name, fn)`, then `get(key)` or `rows` |
| `ConsumerTestKit.of(Consumer)` | `on_message(message, subject, sequence=…, standing=…)`, `on_delete(subject)`; `messages` holds what it published, each with the key it named |
| `GraphConsumerTestKit.of(GraphConsumer, client)` | `on_message(message, subject, sequence=…)`, `on_delete(subject, sequence=…)`, each returning the elements published |
| `TimedActionTestKit.of(Action)` | `call(name, input, metadata={...})`, the metadata being what the handler reads, such as `ankka.due` |
| `AgentTestKit.of(Agent, session, model)` | a handler plus the loop the sidecar would run, against a `ScriptedModel` |
| `EndpointTestKit.of(Endpoint, *args)` | `get`, `post`, `put`, `delete` against the routes, returning a `Response` |

A component's client calls are refused inside a unit test kit, because there is nothing to call. Test a
component that calls others at the integration level.

A workflow's commands and steps can be run by hand:

**Scala**

```scala
test("a declined charge is retried once, then fails over to compensation") {
  val carts = TestTransport().stub(ShoppingCartEntity.totalQuantity)(() => 3)
  val kit   = WorkflowTestKit.of(CheckoutWorkflow, "c1", carts.client)

  val started = kit.call(CheckoutWorkflow.start)("fail")
  assertEquals(started.steps, Vector("reserve", "charge", "charge", "compensate"))
  assertEquals(kit.state.status, "compensated")
  assert(kit.lifecycle.isCompleted, kit.lifecycle.toString)
  // A second start is refused: the checkout is already compensated.
  assert(kit.call(CheckoutWorkflow.start)("ok").isError)
}
```

**Java**

```java
/** A command and the steps it runs, by hand, until the workflow pauses. */
public static StepResult<Checkout.State, Done> startCheckout() {
  var kit = WorkflowTestKit.of(Checkout.class, "c1");
  var started = kit.call(Checkout::start, new Checkout.Start("cart-1", 60_000));
  // started.steps() is [reserve, charge]: the steps that ran before the pause;
  // started.lifecycle().isPaused(), for a minute (pausedFor()), then the step "expire"
  // (onTimeout()); kit.state().status() is "awaiting", and kit.timeOut() would run "expire".
  return started;
}
```

**Kotlin**

```kotlin
val wallets = TestClient.of(WalletEntity::class.java).stub(WalletEntity::withdraw) { Done.done() }.stub(WalletEntity::deposit) { Done.done() }
val kit = WorkflowTestKit.of(TransferWorkflow::class.java, "t-1", wallets)
val started = kit.call(TransferWorkflow::start, Transfer("a", "b", 10))
assertEquals(Done.done(), started.reply())
assertEquals(listOf("withdraw", "deposit"), started.steps()) // run after the command, in order
assertEquals("completed", kit.state().status)
assertTrue(kit.lifecycle().isCompleted())
assertTrue(kit.call(TransferWorkflow::start, Transfer("a", "b", 10)).isError()) // already started
```

**Python**

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

**TypeScript**

```ts
test("the checkout workflow declares its recovery, and its steps can be run by hand", async () => {
  const kit = WorkflowTestKit.of(CheckoutWorkflow, "c1")
  const started = await kit.call(CheckoutWorkflow.handlers.start, "fail")
  assert.equal(started.transition?.step, "reserve")
  assert.deepEqual([kit.state.status, kit.state.mode], ["reserving", "fail"])
  assert.ok((await kit.call(CheckoutWorkflow.handlers.start, "ok")).error)
  assert.equal((await kit.runStep(CheckoutWorkflow.steps.compensate)).next.kind, "end")
  assert.equal(kit.state.status, "compensated")
  assert.deepEqual(CheckoutWorkflow.settings.steps?.charge?.recovery, { maxRetries: 1, failoverTo: "compensate" })
})
```

**Rust**

```rust
#[test]
fn a_checkout_reserves_what_the_cart_holds_then_charges_it() {
    let mut kit =
        WorkflowTestKit::<CheckoutWorkflow>::new("cart-9").with_service(shopping_cart::build());
    assert_eq!(
        kit.command("start", "ok".to_string()).reply::<Done>(),
        Ok(Done)
    );
    assert_eq!(kit.run_step(), StepNext::TransitionTo("charge".into()));
    // The step asked the cart what it holds — the kit answers from an in-memory cart, empty here.
    assert_eq!(kit.state().reserved, 0);
    assert_eq!(kit.run_to_end(), StepNext::End);
    assert_eq!(kit.state().status, "charged");
}
```

**Go**

```go
func TestATransferStartsAtItsFirstStep(t *testing.T) {
	ctx := context.Background()
	if p := TransferService().Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	kit := testkit.Workflow(TransferWorkflow, testkit.Calling(WalletEntity))
	defer kit.Close()
	transfer := Transfer{From: "a", To: "b", Amount: 10}

	nothing := kit.Command(ctx, "start", Transfer{From: "a", To: "b"})
	if nothing.Refusal() == nil || nothing.Refusal().Code != ankka.BadRequest {
		t.Fatalf("a transfer of nothing answered %v", nothing.Refusal())
	}
	r := kit.Command(ctx, "start", transfer)
	if r.Refusal() != nil || r.Reply() != (ankka.Done{}) || r.Transition() == nil || r.Transition().Step != "withdraw" {
		t.Fatalf("start answered %v, %v, %+v", r.Reply(), r.Refusal(), r.Transition())
	}
	if kit.State().Status != "started" {
		t.Fatalf("the state is %+v", kit.State())
	}
	if r := kit.Command(ctx, "start", transfer); r.Refusal() == nil || r.Refusal().Code != ankka.Conflict {
		t.Fatalf("starting twice answered %v", r.Refusal())
	}
	// The wallet the kit hosts is empty, so the withdrawal is refused and the step fails.
	progress, err := kit.RunStep(ctx)
	if err == nil && progress.Failed == nil && progress.Pending != nil && progress.Pending.Step == "withdraw" {
		t.Fatalf("the withdrawal ran: %+v", progress)
	}
}
```

`AgentTestKit` runs the handler, then the loop the sidecar would run, against a `ScriptedModel`: tools run
in-process with the scripted arguments, and guardrails are checked. `ScriptedModel` fails loudly when it
runs out, like `TestModelProvider`:

**Scala**

```scala
test("the assistant plans a lookup, and the tool reads the cart") {
  val carts = TestTransport().stub(ShoppingCartEntity.getCart)(() => ShoppingCart.empty("c9"))
  val model = TestModelProvider()
    .expectToolCall("lookup", Json.obj("cartId" -> Json.str("c9")))
    .expectText("Your cart is empty.")
  val kit = AgentTestKit.of(CartAssistant, model, "s1", others = carts)

  val answer = kit.call(CartAssistant.ask)("what is in cart c9?")
  assertEquals(answer.reply, "Your cart is empty.")
  // The tool ran in this process against the stubbed cart, and its result went to the model.
  val toolResult = model.requests(1).messages.collectFirst {
    case ChatMessage.ToolResults(results) => results.head.content
  }
  assertEquals(toolResult, Some("cart c9 is empty"))
}
```

**Java**

```java
var model =
    new TestModelProvider()
        .expectToolCall("cart_items", "{\"cartId\":\"c-1\"}")
        .expectText("Cart c-1 holds 2 lines");
var cart =
    TestClient.of(ShoppingCart.class)
        .stub(
            ShoppingCart::getCart,
            () ->
                new CartState(
                    "c-1", List.of(new LineItem("p", "Pen", 2), new LineItem("q", "Pad", 1)), false));
var kit = AgentTestKit.of(CartHelper.class, model, "s-1", cart);

var result = kit.call(CartHelper::ask, "How full is c-1?");
// result.reply() is "Cart c-1 holds 2 lines"; model.lastRequest() holds what the tool answered
```

**Kotlin**

```kotlin
val model =
    TestModelProvider()
        .expectToolCall("current_date", "{}")
        .expectToolCall("get_weather", """{"location":"Lisbon","date":"2026-09-06"}""")
        .expectText("Lisbon: sunny today.")
```

**Python**

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

**TypeScript**

```ts
test("the assistant plans, and the tool reads the cart", async () => {
  const cart = { cartId: "c9", items: [], checkedOut: false }
  const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: async () => cart }) }) }
  const kit = AgentTestKit.of(CartAssistant, "s1", model, client as unknown as ComponentClient)
  assert.equal(await kit.ask(CartAssistant.handlers.ask, "what is in cart c9?"), "Your cart is empty.")
  assert.deepEqual(model.calls[0]!.tools, ["lookup"])
  // The tool ran in this process, and its answer went back to the model.
  assert.match(model.calls[1]!.messages.at(-1)!.text, /c9/)
  // The script is spent: another turn fails loudly rather than answering a default.
  await assert.rejects(kit.ask(CartAssistant.handlers.ask, "and now?"), /script/i)
})
```

**Rust**

```rust
#[test]
fn the_assistant_plans_and_the_tool_reads_the_cart() {
    let model = ScriptedModel::new()
        .expect_tool_call("lookup", json!({"cartId": "c9"}))
        .expect_text("Your cart is empty.");
    let mut kit =
        AgentTestKit::<CartAssistant>::new("s1", model).with_service(shopping_cart::build());
    let answer = kit.call("ask", "what is in cart c9?".to_string());
    assert_eq!(answer.plan.tools, vec!["lookup"]);
    assert_eq!(answer.plan.guardrails, vec!["no-secrets"]);
    // The tool ran in this process, against the kit's in-memory cart, which is empty.
    assert_eq!(answer.tool_results, vec!["cart c9 is empty"]);
    assert_eq!(answer.reply.as_deref(), Some("Your cart is empty."));
}
```

**Go**

```go
func TestTheWeatherAgentsToolsAnswerTheModel(t *testing.T) {
	// The model answers from its script, in order, and fails loudly when it runs out.
	model := testkit.NewScriptedModel().
		ExpectToolCall("current_date", map[string]any{}).
		ExpectToolCall("get_weather", map[string]any{"location": "Lisbon"}).
		ExpectToolCall("get_weather", map[string]any{"location": "Nowhere", "date": "2026-09-07"}).
		ExpectText("Lisbon is sunny; Nowhere is unknown.")
	k := testkit.Agent(WeatherAgent, "s1", model)
	defer k.Close()
	answer, err := k.Ask(context.Background(), "ask", "Lisbon or Nowhere?")
	if err != nil || answer != "Lisbon is sunny; Nowhere is unknown." {
		t.Fatalf("answered %q, %v", answer, err)
	}
	var results []string
	for _, m := range model.Calls[3].Messages {
		if m.Role == "tool" {
			results = append(results, m.Text)
		}
	}
	want := []string{"2026-09-06", "Lisbon: 18C, sunny"}
	if len(results) != 3 || results[0] != want[0] || results[1] != want[1] ||
		!strings.Contains(results[2], "unknown location") {
		t.Fatalf("the model was told %q", results)
	}
}
```

### What a TypeScript kit gives you

`ankka/testkit` holds the same set, and every call is awaited:

| Kit | Drives |
|---|---|
| `EventSourcedTestKit.of(Entity, id)` | `call(handler, input)`; the result has `events`, `newState`, `reply`, `error`, `noReply`, `retention` |
| `KeyValueTestKit.of(Entity, id)` | the same, with `changed` in place of `events` |
| `WorkflowTestKit.of(Workflow, id)` | `call` a command, `runStep` a step, `runUntilEnd` and `resume` to follow transitions |
| `ViewTestKit.of(View)` | `onChange(key, event, metadata?, standing?)`, `onDelete(key)`, then `get(key)` for the row; `standing` is a workflow's, for a view that reads one |
| `KeyedViewTestKit.of(KeyedView)` | `change(Entity, key, event)`, `deleted(Entity, key)`, `answering(name, fn)`, then `get(key)` or `rows` |
| `ConsumerTestKit.of(Consumer)` | `onMessage(message, subject, metadata, standing?)`, `onDelete(subject)`; `produced` holds what it published, each with the key it named |
| `GraphConsumerTestKit.of(GraphConsumer, client)` | `onMessage(message, { subject, sequence })`, `onDelete({ subject, sequence })`, each returning the deltas published |
| `TimedActionTestKit.of(Action)` | `invoke(action, input, metadata)`, the metadata being what the handler reads, such as `ankka.due` |
| `AgentTestKit.of(Agent, session, model)` | a handler plus the loop the sidecar would run, against a `ScriptedModel` |
| `EndpointTestKit.of(Endpoint)` | `get`, `post`, `put`, `delete` against the routes, returning a `Response` |

`ScriptedModel` scripts turns with `expectText`, `expectToolCall` and `expectRefusal`, and fails loudly
when the script runs out.

### What a Rust kit gives you

`ankka::testkit` holds a kit for each kind. They run natively, with `cargo test`, through the same
dispatch the module's exports use:

| Kit | Drives |
|---|---|
| `EventSourcedTestKit::<C>::new(id)` | `command(name, input)`; the outcome has `events`, `new_state`, `retention` and `reply::<R>()` |
| `KeyValueEntityTestKit::<C>::new(id)` | commands on a key value entity |
| `WorkflowTestKit::<C>::new(id)` | `command`, `run_step`, `run_to_end`, `resume`, `state` |
| `ViewTestKit::<C>::new()` | `on_event(key, event)`, `on_deleted(key)`, then `row(key)`; `.standing(Standing)` hands each change a workflow's standing |
| `KeyedViewTestKit::<C>::new()` | `change::<E>(source, key, event)`, `deleted(source, key)`, `answering(name, fn)`, then `row(key)` |
| `ConsumerTestKit::<C>::new()` | `on_message(subject, message)`, `on_deleted(subject)`; `.at(sequence)` sets the sequence number, `.standing(Standing)` a workflow's standing, and `ConsumerTestKit::<C>::messages(&effect)` reads what an effect publishes, each message with its record key |
| `GraphConsumerTestKit::<G>::new()` | `on_message(subject, sequence, message)`, `on_deleted(subject, sequence)`, each returning the elements published |
| `EndpointTestKit::<E>::new()` | an endpoint's routes by method and path |

A kit built `.with_service(build())` answers its component's calls to the service's event sourced
entities in memory; without it those calls are refused.

### What a Go kit gives you

The package `github.com/thinkmorestupidless/ankka-go/testkit` holds a kit for each kind. They run under
`go test` with no sidecar, database or network, and every call takes the test's `context.Context`:

| Kit | Drives |
|---|---|
| `testkit.EventSourced(entity, opts...)` | `Command(ctx, wire, input)` and `Query`; the result has `Events()`, `State()`, `Reply()`, `Refusal()`, `Failure()`, `Deleted()`, `ExpireAfter()`; `testkit.EntityID(id)` names the entity |
| `testkit.KeyValue(entity, opts...)` | the same, without events |
| `testkit.Workflow(workflow, opts...)` | `Command` a command, `RunStep` a step, `RunUntilPause` and `Resume` to follow transitions, `Progress()` |
| `testkit.View(view, opts...)` | `OnChange(ctx, subject, value, opts...)`, `OnDelete(ctx, subject)`, then `Row(subject)` or `Rows()`; `testkit.FromWorkflow(standing)` hands the change a workflow's standing |
| `testkit.KeyedView(view, opts...)` | `Change(ctx, sourceID, subject, value)`, `Deleted(ctx, sourceID, subject)`, `Answering(name, fn)`, then `Row(key)` or `Rows()` |
| `testkit.Consumer(consumer, opts...)` | `OnMessage(ctx, subject, message, opts...)`, `OnDelete(ctx, subject)`, each returning what it published with the record key each message named |
| `testkit.GraphConsumer(consumer, opts...)` | `OnMessage` and `OnDelete`, each returning the deltas published, read back from their bytes |
| `testkit.TimedAction(action, opts...)` | `Invoke(ctx, wire, input, metadata...)` |
| `testkit.Agent(agent, session, model, opts...)` | `Ask`, `Stream` and `Decide`: a handler plus the loop the sidecar would run, against a `testkit.NewScriptedModel()` |
| `testkit.Endpoint(endpoint, components...)` | `Get`, `Post`, `Put`, `Patch`, `Delete` against the routes, answering a `Response`; the components named answer the routes' calls in memory |
| `testkit.Mcp(endpoint, components...)` | `Call(ctx, tool, arguments, principal)` on an MCP endpoint's tools |

`testkit.Calling(components...)` gives an entity, workflow, view, consumer or timed action kit the components its handlers call. `ScriptedModel` scripts
turns with `ExpectText`, `ExpectToolCall` and `ExpectRefusal`, and fails loudly when the script runs out.
Every input, event, state, row and reply is round-tripped through the component's codecs, so a type the
encoding cannot carry fails in the kit rather than on the first deployment.

## Testing a consumer

A consumer's unit test hands it one change, with a subject and a sequence number, and reads back the
messages it would publish: each payload as its bytes decode, and the key each message named.
Nothing is started. This consumer answers a checkout with three messages, the second under a key of its
own:

**Scala**

```scala
test("a checkout is answered with three messages, the second under a key of its own") {
  val kit    = ConsumerTestKit.of(CheckoutFanout)
  val result = kit.onMessage(CheckedOut, subject = "c1", sequenceNumber = 4)

  assertEquals(result.payloads, Vector(Fanned(1), Fanned(2), Fanned(3)))
  // The key each message named; the others are keyed by their subject, the cart's id.
  assertEquals(result.keys, Vector(None, Some("second:c1"), None))
  assertEquals(result.recordKeys, Vector(Some("c1"), Some("second:c1"), Some("c1")))
  assertEquals(result.messages(2).metadata.get("x-n"), Some("3"))
}
```

**Java**

```java
public static ConsumerResult<Fanned> fanout() {
  var kit = ConsumerTestKit.of(CheckoutFanout.class);
  var result = kit.onMessage(new CartEvent.CheckedOut(), "c1", 4);

  // result.payloads() is [Fanned(1), Fanned(2), Fanned(3)];
  // result.recordKeys() is [c1, second:c1, c1]: the key a message named, else its subject;
  // result.messages().get(2).metadata().get("x-n") is "3".
  return result;
}
```

**Kotlin**

```kotlin
val kit = ConsumerTestKit.of(CheckoutFanout::class.java)
val result = kit.onMessage(CartEvent.CheckedOut, "c1", 4)

assertEquals(listOf(Fanned(1), Fanned(2), Fanned(3)), result.payloads())
// The key each record carries: the one a message named, or the subject, the cart's id.
assertEquals(listOf(Optional.of("c1"), Optional.of("second:c1"), Optional.of("c1")), result.recordKeys())
assertEquals("3", result.messages()[2].metadata()["x-n"])
```

**Python**

```python
def test_the_reference_fans_a_checkout_out_into_three_messages() -> None:
    from ankka import Metadata
    from ankka.effects.consumer import Produce, ProduceAll
    from ankka.testkit import Produced
    from examples.shopping_cart.conformance import CheckoutFanout, Fanned

    kit = ConsumerTestKit.of(CheckoutFanout)
    assert kit.on_message(ItemAdded(PEN), "k1") == ProduceAll(())
    assert isinstance(kit.on_message(ItemRemoved("p1"), "k1"), Produce)
    assert kit.messages == [Produced(Fanned(0), None, Metadata())]
    kit.on_message(CheckedOut(), "k1")
    assert kit.messages[1:] == [
        Produced(Fanned(1), None, Metadata()),
        Produced(Fanned(2), "second:k1", Metadata()),
        Produced(Fanned(3), None, Metadata().set("x-n", "3")),
    ]
    assert kit.on_message(Discarded(), "k1").__class__.__name__ == "Ignore"
    assert CheckoutFanout.out_codec.encode(Fanned(2)) == b'{"n":2}'
    assert CheckoutFanout.out_codec.manifest == "fanned"
```

**TypeScript**

```ts
test("a checkout fans out into three messages, the second under a key of its own", async () => {
  const kit = ConsumerTestKit.of(CheckoutFanout)
  // An item added answers with no messages; an item removed with one, keyed by its subject.
  await kit.onMessage({ type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 1 } }, "c1")
  assert.deepEqual(kit.produced, [])
  await kit.onMessage({ type: "CheckedOut" }, "c1", { "ankka.sequence": "4" })
  assert.deepEqual(kit.produced, [
    { payload: { n: 1 }, metadata: {} },
    { payload: { n: 2 }, metadata: {}, key: "second:c1" },
    { payload: { n: 3 }, metadata: { "x-n": "3" } },
  ])
})
```

**Rust**

```rust
/// The fan-out the suite's several-message cases read, as every reference publishes it.
#[test]
fn the_reference_fans_a_checkout_out_into_three_messages() {
    let kit = ConsumerTestKit::<CheckoutFanout>::new().at(2);
    let added = ShoppingCartEvent::ItemAdded {
        item: LineItem {
            product_id: "p1".into(),
            name: "Pen".into(),
            quantity: 1,
        },
    };
    assert!(matches!(kit.on_message("c1", added), ConsumerEffect::Done));

    let messages = ConsumerTestKit::<CheckoutFanout>::messages(
        &kit.on_message("c1", ShoppingCartEvent::CheckedOut),
    );
    let ns: Vec<i32> = messages.iter().map(|m| m.read::<Fanned>().n).collect();
    assert_eq!(ns, vec![1, 2, 3]);
    let keys: Vec<Option<&str>> = messages.iter().map(|m| m.key.as_deref()).collect();
    assert_eq!(keys, vec![None, Some("second:c1"), None]);
    assert_eq!(messages[2].metadata.get("x-n"), Some("3"));
    assert!(messages.iter().all(|m| m.payload.manifest == "fanned"));
    assert_eq!(messages[0].payload.data, br#"{"n":1}"#);

    // An item removed is one message, the old way, on a runtime of any version.
    let removed = ShoppingCartEvent::ItemRemoved {
        product_id: "p1".into(),
    };
    let earlier = ConsumerTestKit::<CheckoutFanout>::new().speaking(None);
    match earlier.on_message("c1", removed) {
        ConsumerEffect::Produce(Ok(payload), _) => {
            assert_eq!(payload.manifest, "fanned");
            assert_eq!(payload.data, br#"{"n":0}"#);
        }
        other => panic!("{other:?}"),
    }
    let discarded = kit.on_message("c1", ShoppingCartEvent::Discarded);
    assert!(matches!(discarded, ConsumerEffect::Ignore));
}
```

**Go**

```go
func TestACheckoutIsAnsweredWithThreeMessages(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Consumer(conformance.CheckoutFanout)
	defer kit.Close()
	out, err := kit.OnMessage(ctx, "c1", cart.CheckedOut{}, testkit.Sequence(4))
	if err != nil || len(out) != 3 {
		t.Fatalf("a checkout published %v, %v", out, err)
	}
	for i, n := range []int{1, 2, 3} {
		if out[i].Payload != (conformance.Fanned{N: n}) {
			t.Fatalf("message %d is %+v", i, out[i].Payload)
		}
	}
	// The key the second message named; the others are keyed by their subject, the cart's id.
	if out[0].Key != "c1" || out[1].Key != "second:c1" || out[2].Key != "c1" {
		t.Fatalf("the keys are %q %q %q", out[0].Key, out[1].Key, out[2].Key)
	}
	if out[2].Metadata["x-n"] != "3" {
		t.Fatalf("the third message's metadata is %v", out[2].Metadata)
	}
}
```

Every kit reports the key a message named, so a message that names none shows no key: it is keyed by
its subject, the entity's id. The Scala result also has `recordKeys`, the key a broker is given for each.

The consumer under test is [the one that publishes several messages](consumers.md#publishing-several-messages-for-one-change).
A [graph consumer](graph.md#testing-a-graph-consumer) has a kit of its own in every language, which
returns elements rather than messages.

A consumer that calls other components is given a client that answers: a `TestTransport` with stubs in
Scala, a client double in Python and TypeScript, a kit built `.with_service(build())` in Rust, and `testkit.Calling(...)` with the
components it calls in Go. With
none, the call is refused, the handler fails, and in a running service the change would be delivered
again.

## Testing a component that calls another service

A component that calls another service is given a scripted set of services in a unit test,
`ScriptedServices` in every language, which answers as the test says, records each request, and fails the
test for a service nothing is scripted for. A test of a whole Scala service plays the other service on
loopback with `ScriptedService.start()` and gives its address to
`AnkkaTestKit.start(…, localServices = Map(name -> scripted.address))`. See
[Calling other services](calling-services.md).

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

**Java**

```java
@Test
void anItemAddedOverHttpIsReadBackAfterARestart() throws Exception {
  var added =
      send(
          HttpRequest.newBuilder(kit.http().resolve("/carts/c1/items"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(
                  "{\"productId\":\"p1\",\"name\":\"Pen\",\"quantity\":2}")));
  assertEquals(204, added.statusCode(), added.body());

  kit.restartService();

  var cart = send(HttpRequest.newBuilder(kit.http().resolve("/carts/c1")).GET());
  assertEquals(200, cart.statusCode(), cart.body());
  assertTrue(cart.body().contains("\"quantity\":2"), cart.body());
}
```

**Kotlin**

```kotlin
@Test
fun `an item added over HTTP is read back after a restart, and reaches the view`() =
    ankkaTestKit(service()) {
        val added =
            send(
                HttpRequest.newBuilder(http().resolve("/carts/c1/items"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"productId":"p1","name":"Pen","quantity":2}"""))
            )
        assertEquals(204, added.statusCode(), added.body())

        restartService()

        val cart = send(HttpRequest.newBuilder(http().resolve("/carts/c1")).GET())
        assertEquals(200, cart.statusCode(), cart.body())
        assertTrue(cart.body().contains("\"quantity\":2"), cart.body())

        runBlocking {
            eventually {
                val row = send(HttpRequest.newBuilder(http().resolve("/carts/c1/row")).GET())
                assertEquals(200, row.statusCode(), row.body())
                assertTrue(row.body().contains("\"p1\":2"), row.body())
            }
        }
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

**Rust**

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

**Go**

```go
func TestEveryRouteAgainstARealSidecar(t *testing.T) {
	kit := testkit.StartT(t, cart.Service())
	ctx := t.Context()
	h := kit.HTTP()

	r, err := h.Post(ctx, "/carts/s1/items", pen)
	status(t, "POST items", r, err, 204)
	r, err = h.Post(ctx, "/carts/s1/items", ink)
	status(t, "POST items", r, err, 204)
	r, err = h.Get(ctx, "/carts/s1/total")
	status(t, "GET total", r, err, 200)
	if r.Text() != "3" {
		t.Fatalf("the total is %q", r.Text())
	}
	r, err = h.Delete(ctx, "/carts/s1/items/ink")
	status(t, "DELETE an item", r, err, 204)
	r, err = h.Delete(ctx, "/carts/s1/items/ink")
	status(t, "DELETE an item not there", r, err, 404)
	r, err = h.Post(ctx, "/carts/s1/items", cart.LineItem{ProductID: "pen", Name: "Pen", Quantity: 0})
	status(t, "POST a zero quantity", r, err, 400)

	if err := kit.Restart(ctx); err != nil {
		t.Fatal(err)
	}
	// The new sidecar answers on a new port.
	h = kit.HTTP()
	got, err := testkit.GetJSON[cart.Cart](ctx, h, "/carts/s1")
	if err != nil || !reflect.DeepEqual(got, cart.Cart{CartID: "s1", Items: []cart.LineItem{pen}}) {
		t.Fatalf("after a restart the cart is %+v (%v)", got, err)
	}

	r, err = h.Post(ctx, "/carts/s1/checkout", nil)
	status(t, "checkout", r, err, 200)
	r, err = h.Post(ctx, "/carts/s1/items", ink)
	status(t, "POST to a checked out cart", r, err, 409)

	r, err = h.Post(ctx, "/carts/s2/items", ink)
	status(t, "POST items", r, err, 204)
	r, err = h.Do(ctx, http.MethodDelete, "/carts/s2", nil, nil)
	status(t, "discard", r, err, 204)
	if got, err := testkit.GetJSON[cart.Cart](ctx, h, "/carts/s2"); err != nil || len(got.Items) != 0 {
		t.Fatalf("after a discard the cart is %+v (%v)", got, err)
	}
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

**Scala**

```scala
val server  = HttpServer.at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))
val testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(server))
val baseUrl = s"http://127.0.0.1:${server.boundPort.get}"
```

**Kotlin**

```kotlin
// Every port is a free loopback one, never 9000: `http()` is where the routes answer.
val kit = AnkkaTestKit.start(service())
val baseUrl = kit.http()
```

### gRPC in an integration test

A gRPC server is served the same way, on a free loopback port, and called through the stub generated from
the `.proto` file over `GrpcChannels.plaintext`. `GrpcChannels.plaintext(port, caller)` makes every call as
a named caller, for an ACL that names callers. See [gRPC endpoints](grpc-endpoints.md#test-it).

### Timers and projections in an integration test

Register the extension the component needs, as the service itself would: `TimerRuntime` for timed
actions, `ProjectionRuntime()` for views and consumers. A shorter timer poll interval keeps a timer test
fast, as in [Timers](timers.md#testing-timers), and a `TimerProbe` given to the `TimerRuntime` records the due
time each run was for, which is what a test of a recurring timer asserts. Views and consumers see changes after the write returns,
so assert on them by retrying until the expected value appears, and retry on the value that changes
rather than on the mere presence of a row.

A test of something that happens in time — a timer a week away, a schedule, an outage — gives
`TimerRuntime` a `MovableClock` and moves it; see [Timers](timers.md#testing-timers). An outage is
`stopService()`, whatever happens while the service is down (moving the clock, say), then
`startService()`, with other extensions if the service comes back changed; `restartService(extensions =
…, whileStopped = …)` is the three in one. The kit's clients are not usable while the service is stopped.

In Java and Kotlin the kit is the Scala one underneath: `AnkkaTestKit.start(service)` (in Kotlin,
`ankkaTestKit(service) { … }`, which closes it) starts the whole service in the test's JVM against
Postgres, and `restartService()` drops everything held in memory.

In Python and TypeScript the kit starts the real sidecar image beside Postgres, serves your
components from the test process, and drives the routes through the sidecar. `restart()` replaces the
sidecar against the same database. Mark the Python tests `@pytest.mark.slow` and run them with
`uv run pytest -m slow`; the TypeScript ones are skipped unless `ANKKA_SLOW=1` is set, so that neither
suite needs Docker by default. The Go kit does the same: `testkit.StartT(t, service)` starts the sidecar
and Postgres and stops them when the test ends, `Restart(ctx)` replaces the sidecar, and the test is
skipped unless `ANKKA_SLOW=1` is set, as one that calls `testkit.Slow(t)` is.

Mark such tests `@pytest.mark.slow` and run them with `uv run pytest -m slow`; `uv run pytest` runs the
unit tests alone.

### Quiet output from a Scala suite

An integration test starts a whole service, and a service logs as it starts, forms its cluster, runs
projections and stops. Across a suite that is hundreds of lines, none of which matter unless a test
fails. Mix `LogCapturing` into a munit suite to hold that log back:

**Scala**

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

## Several installations

A [replicated entity](../concepts/replicated-entities.md) is tested at several installations of one
service in one test. `AnkkaTestKit.installations(names*)` takes a database for each installation in the
shared Postgres and returns a group; `start(name, descriptors)` starts one installation of the service,
which knows every other installation's journal from its first start and replicates through the
runtime's own replication transport. `stopAll()` stops every installation and gives back the databases.

**Scala**

```scala
val fleet = AnkkaTestKit.installations("smoke-a", "smoke-b")
try
  val a       = fleet.start("smoke-a", Seq(Fleet.vehicles.descriptor))
  val b       = fleet.start("smoke-b", Seq(Fleet.vehicles.descriptor))
  val atA     = a.componentClient.forEventSourcedEntity(EntityId("v1"))
  val atB     = b.componentClient.forEventSourcedEntity(EntityId("v1"))
  val created = atA.call(Fleet.vehicles.createVehicle).invoke(CreateVehicle(Vector("smoke-b")))
  assertEquals(created.installation, Some("smoke-a"))
  (1 to 3).foreach(n => atA.call(Fleet.vehicles.addStep).invoke(s"step-$n"))
  val mirrored = AnkkaTestKit.eventually("smoke-b holds the three steps", 20.seconds) {
    val v = atB.call(Fleet.vehicles.get).invoke()
    Option.when(v.steps.size == 3)(v)
  }
  assertEquals(mirrored.writtenAt.distinct, Vector(Some("smoke-a")))
  assertEquals(mirrored.local.distinct, Vector(false))
  val forwarded = atB.call(Fleet.vehicles.recordInspection).invoke("brakes")
  assertEquals(forwarded.installation, Some("smoke-a"))
  val status = atB.replication(Fleet.vehicles)
  assertEquals(status.home, Some("smoke-a"))
  assertEquals(status.replicationSet, Set("smoke-a", "smoke-b"))
finally fleet.stopAll()
```

**Java**

```java
/** Writes at the home and reads the mirrored copy at the follower. */
public static Ledger.State mirrored() {
  try (var fleet = AnkkaTestKit.installations("smoke-a", "smoke-b")) {
    var a = fleet.start("smoke-a", ledgers());
    var b = fleet.start("smoke-b", ledgers());
    var atA = a.componentClient().forEventSourcedEntity("v1");
    var atB = b.componentClient().forEventSourcedEntity("v1");
    atA.call(Ledger::share).invoke("smoke-b");
    for (int n = 1; n <= 3; n++) atA.call(Ledger::enter).invoke("step-" + n);
    b.eventually(
        Duration.ofSeconds(20),
        () -> {
          if (atB.call(Ledger::get).invoke().entries().size() < 3)
            throw new AssertionError("smoke-b does not hold the three steps yet");
        });
    // Each entry says where it was written: "step-1@smoke-a (mirrored)" at smoke-b.
    return atB.call(Ledger::get).invoke();
  }
}
```

**Kotlin**

```kotlin
val fleet = Ankka.service("fleet").register(VehicleEntity::class.java).register(BuildsConsumer::class.java)
AnkkaTestKit.installations("leipzig", "vienna").use { group ->
    val leipzig = group.start("leipzig", fleet)
    val vienna = group.start("vienna", fleet)
    val atHome = leipzig.componentClient().forEventSourcedEntity("v1")
    atHome.call(VehicleEntity::create).invoke(CreateVehicle(listOf("vienna")))
    atHome.call(VehicleEntity::addStep).invoke("frame")

    vienna.eventually(Duration.ofSeconds(30)) {
        val status = vienna.componentClient().forEventSourcedEntity("v1").replication(VehicleEntity::class.java)
        assertEquals("leipzig", status.home().orNull())
        assertEquals(setOf("leipzig", "vienna"), status.replicationSet())
        assertTrue(status.position() > 0) // how many records this copy holds
        // The creation alone moves the position; the step may still be on its way.
        val mirrored = vienna.componentClient().forEventSourcedEntity("v1").call(VehicleEntity::get).invoke()
        assertEquals(listOf("frame"), mirrored.steps)
        assertEquals(listOf(false, false), mirrored.local)
    }

    // The consumer publishes, so it is given the local change only, at the home.
    leipzig.eventually(Duration.ofSeconds(30)) {
        assertTrue(BuildsConsumer.GIVEN.any { it.event == "StepAdded(step=frame)" }, BuildsConsumer.GIVEN.toString())
    }
    val handed = BuildsConsumer.GIVEN.filter { it.event == "StepAdded(step=frame)" }
    assertEquals(listOf(Handed("v1", "StepAdded(step=frame)", "leipzig", true)), handed.distinct())
    assertEquals(listOf("v1"), leipzig.published("vehicle-builds").map { it.key().orNull() })
}
```

A follower reads its home's records a poll at a time, so a test reads a follower with
`AnkkaTestKit.eventually` until it holds what the test wrote, never once.

The group's `transport` is what carries records between its installations, and a test makes it
misbehave:

| Call | What it does |
|---|---|
| `transport.pause(name)` | Stalls every record on its way to `name`, which falls behind; nothing is lost |
| `transport.resume(name)` | Lets what was stalled through, in order |
| `transport.block(from, to)` | Makes `to` unreachable from `from`: a write forwarded there fails at once, and records stop arriving |
| `transport.unblock(from, to)` | Makes it reachable again |

`restart(name, descriptors, settings = …)` stops one installation and starts it again on its own
database with other settings, as a deploy would; `start(name, descriptors, database = Some(kit))` starts
an installation on a database an unreplicated service wrote, which is how a test shows an existing
entity becoming replicated.

Several kits in one test JVM are installations sharing one machine, not installations on a network:
what they prove is the runtime's replication over its own transport. Give each installation a name no
other test in the same JVM uses at the same time.

A single copy is tested without a runtime in the entity test kit, as the home or as a follower. A
follower refuses a command that may persist `NotHome`, naming its home, and answers queries from history
the test replays into it:

**Scala**

```scala
val kit = EventSourcedTestKit.of(
  Fleet.vehicles,
  "v1",
  mode = EventSourcedTestKit.Mode.Follower(home = "leipzig"),
  installation = "bratislava"
)
given EventSourcedTestKit.WrittenAt = EventSourcedTestKit.WrittenAt(Some("leipzig"))
kit.replay(VehicleEvent.Created("v1"), VehicleEvent.StepAdded("frame"))
```

**Java**

```java
public static Ledger.State followerState() {
  var kit = EventSourcedTestKit.follower(Ledger.class, "l1", "leipzig", "bratislava");
  kit.replay("leipzig", List.of(new Ledger.Entered("created"), new Ledger.Entered("frame")));
  return kit.call(Ledger::get).reply();
}
```

**Kotlin**

```kotlin
val kit = EventSourcedTestKit.follower(VehicleEntity::class.java, "v1", "leipzig", "bratislava")
kit.replay("leipzig", listOf(VehicleEvent.Created("v1"), VehicleEvent.StepAdded("frame")))
```

**Python**

```python
kit = EventSourcedTestKit.of(VehicleEntity, "v1", mode="follower", home="leipzig", installation="bratislava")
kit.replay(StepAdded("frame"), written_at="leipzig")
```

**TypeScript**

```ts
test("a follower answers from what it mirrored and refuses to write", async () => {
  const follower = { kind: "follower", home: "leipzig", installation: "bratislava" } as const
  const kit = EventSourcedTestKit.of(Vehicles, "v1", undefined, follower)
  kit.replay([{ type: "StepAdded", step: "frame" }, { type: "StepAdded", step: "paint" }], "leipzig")
  const mirrored = { steps: ["frame", "paint"], dealer: null, writtenAt: ["leipzig", "leipzig"] }
  assert.deepEqual((await kit.call(Vehicles.handlers.get)).reply, mirrored)
  const refused = await kit.call(Vehicles.handlers.addStep, "wheels")
  assert.equal(refused.error?.code, "NOT_HOME")
  assert.match(refused.error?.message ?? "", /leipzig/)
})
```

**Rust**

```rust
#[test]
fn a_follower_answers_queries_from_its_mirrored_history_and_refuses_to_write() {
    let mut kit =
        EventSourcedTestKit::<ReplicatedVehicle>::new("v1").in_mode(ReplicationMode::Follower {
            home: "leipzig".into(),
            installation: "bratislava".into(),
        });
    // History the copy mirrored from its home, written there.
    kit.replay(
        vec![
            StepAdded {
                step: "frame".into(),
            },
            StepAdded {
                step: "paint".into(),
            },
        ],
        Some("leipzig"),
    );
    let vehicle = kit.command("get", ()).reply::<VehicleRecord>().unwrap();
    assert_eq!(vehicle.steps, vec!["frame", "paint"]);
    assert_eq!(vehicle.written_at, vec!["leipzig", "leipzig"]);
    assert_eq!(vehicle.local, vec![false, false]);
    // A command that may persist is refused: the home is the only copy that writes.
    let refused = kit.command("add-step", "wheels".to_string());
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::NotHome));
    assert!(refused.error().unwrap().message.contains("leipzig"));
}
```

As the home, `mode = EventSourcedTestKit.Mode.Home`, every command runs and the handover or change to
the replication set a command asks for is on its result. Java (`EventSourcedTestKit.home` and
`follower`), Kotlin, Python, TypeScript and Rust have the same two modes; see [Event sourced entities](event-sourced-entities.md#testing-a-replicated-entity).

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

**Scala**

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

**Kotlin**

```kotlin
@Given("a cart holding {int} of {string}")
fun aCartHolding(quantity: Int, product: String) = setUp(add(quantity, product))

@Given("a cart holding {int} of {string} and {int} of {string}")
fun aCartHoldingTwo(q1: Int, p1: String, q2: Int, p2: String) {
    setUp(add(q1, p1))
    setUp(add(q2, p2))
}

@Given("a checked-out cart holding {int} of {string}")
fun aCheckedOutCart(quantity: Int, product: String) {
    setUp(add(quantity, product))
    assertEquals(200, send("POST", "$cart/checkout").first)
}

@When("the customer adds {int} of {string}")
fun adds(quantity: Int, product: String) {
    last = add(quantity, product)
}

@When("the customer removes {string}")
fun removes(product: String) {
    last = send("DELETE", "$cart/items/${id(product)}")
}

@When("the customer checks out")
fun checksOut() {
    last = send("POST", "$cart/checkout")
}

@When("the customer discards the cart")
fun discards() {
    last = send("DELETE", cart)
}

@Then("the cart holds {int} of {string}")
fun holds(quantity: Int, product: String) = assertEquals(quantity, quantityOf(product))
```

The suite takes the directory of its features, relative to the working directory; a forked sbt test runs
in its project's directory, so `GherkinSuite("features")` reads the project's own `features/`. It also
takes the path of one `.feature` file, `GherkinSuite("features/cart/items.feature")`, and then runs that
file's scenarios and no others, which is how several suites share one directory of features. It mixes
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

**Scala**

```scala
val model = TestModelProvider()
  .expectToolCall("get_weather", Json.obj("location" -> Json.str("Lisbon")))
  .expectText("Lisbon is 18C and sunny.")
```

**Java**

```java
var model =
    new TestModelProvider()
        .expectToolCall("get_weather", "{\"location\":\"Lisbon\"}")
        .expectText("Lisbon is 18C and sunny.");
```

**Kotlin**

```kotlin
val model =
    TestModelProvider()
        .expectToolCall("current_date", "{}")
        .expectToolCall("get_weather", """{"location":"Lisbon","date":"2026-09-06"}""")
        .expectText("Lisbon: sunny today.")
```

**Python**

```python
model = ScriptedModel().expect_tool_call("get_weather", {"location": "Lisbon"}).expect_text("Lisbon: 18C, sunny.")
```

**TypeScript**

```ts
const model = new ScriptedModel().expectToolCall("lookup", { cartId: "c9" }).expectText("Your cart is empty.")
```

**Rust**

```rust
#[test]
fn the_agent_plans_with_its_tools_and_the_loop_runs_them() {
    let model = ScriptedModel::new()
        .expect_tool_call("get_weather", json!({"location": "Lisbon"}))
        .expect_tool_call("get_weather", json!({"location": "Nowhere"}))
        .expect_text("Lisbon: 18C, sunny");
    let mut kit = AgentTestKit::<WeatherAgent>::new("s1", model);
    let reply = kit.call("ask", "Weather in Lisbon?".to_string());
    assert_eq!(
        reply.plan.system.as_deref(),
        Some(WeatherAgent::SYSTEM_MESSAGE)
    );
    assert_eq!(reply.plan.tools, vec!["get_weather", "current_date"]);
    // The tool's `Err` reaches the model as a message, and the loop goes on.
    assert_eq!(
        reply.tool_results,
        vec!["Lisbon: 18C, sunny", "error: unknown location"]
    );
    assert_eq!(reply.reply.as_deref(), Some("Lisbon: 18C, sunny"));
}

#[test]
fn a_guardrail_rejects_before_any_model_is_called() {
    let mut kit = AgentTestKit::<WeatherAgent>::new("s2", ScriptedModel::new());
    let question = "Will it rain in Lisbon on every day of the coming fortnight?".to_string();
    let rejected = kit.call("guarded", question);
    assert_eq!(rejected.error.map(|e| e.code), Some(ErrorCode::Forbidden));
    assert!(rejected.tool_calls.is_empty());
}
```

**Go**

```go
// The model answers from its script, in order, and fails loudly when it runs out.
model := testkit.NewScriptedModel().
	ExpectToolCall("current_date", map[string]any{}).
	ExpectToolCall("get_weather", map[string]any{"location": "Lisbon"}).
	ExpectToolCall("get_weather", map[string]any{"location": "Nowhere", "date": "2026-09-07"}).
	ExpectText("Lisbon is sunny; Nowhere is unknown.")
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

**Scala**

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

**Java**

```java
try (var kit = AnkkaTestKit.start(PlannerCalls.service(model))) {
```

**Kotlin**

```kotlin
val model = TestModelProvider().expectText("Oslo reaches 21")
val service =
    Ankka.service("weather")
        .withModule(Agents.module().withModelProvider(model))
        .register(WeatherAgent::class.java)
        .withEndpoint(ChatEndpoint::class.java)
AnkkaTestKit.start(service).use { kit ->
```

**Python**

```python
@pytest.mark.slow
async def test_the_selector_decides_which_specialists_run_and_only_those_run() -> None:
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": plan_script()}) as kit:
        planner = kit.client.for_workflow("planner", "p-select")
        assert await planner.call("start").invoke(Start("u-1", "Lisbon"), reply=type(DONE)) == DONE

        plan = await planner.await_end(90, reply=PlanState)
        assert plan.status == COMPLETED
        assert plan.selection is not None and plan.selection.specialists == [WEATHER, BUDGET]
        assert [c.specialist for c in plan.contributions] == [WEATHER, BUDGET]
        # The activity specialist was never consulted, so it contributed nothing.
        assert plan.contribution_from(ACTIVITY) is None
        assert plan.summary == "A mild, affordable trip."
```

**Go**

```go
func TestAnApprovalIsDecidedThroughTheSidecar(t *testing.T) {
	// The sidecar's model, scripted: turns consumed in order, across every session.
	script := `[{"tool":"issue_refund","arguments":{"order":"o-7","amount":12}},` +
		`{"text":"Refunded 12 on o-7."},` +
		`{"tool":"issue_refund","arguments":{"order":"o-7","amount":12}}]`
	kit := testkit.StartT(t, ankka.NewService(ApprovalAgent, SupportEndpoint),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": script}))
	ctx := t.Context()
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

**Scala**

```scala
test("the assistant plans a lookup, and the tool reads the cart") {
  val carts = TestTransport().stub(ShoppingCartEntity.getCart)(() => ShoppingCart.empty("c9"))
  val model = TestModelProvider()
    .expectToolCall("lookup", Json.obj("cartId" -> Json.str("c9")))
    .expectText("Your cart is empty.")
  val kit = AgentTestKit.of(CartAssistant, model, "s1", others = carts)

  val answer = kit.call(CartAssistant.ask)("what is in cart c9?")
  assertEquals(answer.reply, "Your cart is empty.")
  // The tool ran in this process against the stubbed cart, and its result went to the model.
  val toolResult = model.requests(1).messages.collectFirst {
    case ChatMessage.ToolResults(results) => results.head.content
  }
  assertEquals(toolResult, Some("cart c9 is empty"))
}
```

**Java**

```java
var model =
    new TestModelProvider()
        .expectToolCall("cart_items", "{\"cartId\":\"c-1\"}")
        .expectText("Cart c-1 holds 2 lines");
var cart =
    TestClient.of(ShoppingCart.class)
        .stub(
            ShoppingCart::getCart,
            () ->
                new CartState(
                    "c-1", List.of(new LineItem("p", "Pen", 2), new LineItem("q", "Pad", 1)), false));
var kit = AgentTestKit.of(CartHelper.class, model, "s-1", cart);

var result = kit.call(CartHelper::ask, "How full is c-1?");
// result.reply() is "Cart c-1 holds 2 lines"; model.lastRequest() holds what the tool answered
```

**Kotlin**

```kotlin
val model =
    TestModelProvider()
        .expectToolCall("current_date", "{}")
        .expectToolCall("get_weather", """{"location":"Lisbon","date":"2026-09-06"}""")
        .expectText("Lisbon: sunny today.")
```

**Python**

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

**TypeScript**

```ts
test("the assistant plans, and the tool reads the cart", async () => {
  const cart = { cartId: "c9", items: [], checkedOut: false }
  const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: async () => cart }) }) }
  const kit = AgentTestKit.of(CartAssistant, "s1", model, client as unknown as ComponentClient)
  assert.equal(await kit.ask(CartAssistant.handlers.ask, "what is in cart c9?"), "Your cart is empty.")
  assert.deepEqual(model.calls[0]!.tools, ["lookup"])
  // The tool ran in this process, and its answer went back to the model.
  assert.match(model.calls[1]!.messages.at(-1)!.text, /c9/)
  // The script is spent: another turn fails loudly rather than answering a default.
  await assert.rejects(kit.ask(CartAssistant.handlers.ask, "and now?"), /script/i)
})
```

**Rust**

```rust
#[test]
fn the_assistant_plans_and_the_tool_reads_the_cart() {
    let model = ScriptedModel::new()
        .expect_tool_call("lookup", json!({"cartId": "c9"}))
        .expect_text("Your cart is empty.");
    let mut kit =
        AgentTestKit::<CartAssistant>::new("s1", model).with_service(shopping_cart::build());
    let answer = kit.call("ask", "what is in cart c9?".to_string());
    assert_eq!(answer.plan.tools, vec!["lookup"]);
    assert_eq!(answer.plan.guardrails, vec!["no-secrets"]);
    // The tool ran in this process, against the kit's in-memory cart, which is empty.
    assert_eq!(answer.tool_results, vec!["cart c9 is empty"]);
    assert_eq!(answer.reply.as_deref(), Some("Your cart is empty."));
}
```

**Go**

```go
func TestTheWeatherAgentsToolsAnswerTheModel(t *testing.T) {
	// The model answers from its script, in order, and fails loudly when it runs out.
	model := testkit.NewScriptedModel().
		ExpectToolCall("current_date", map[string]any{}).
		ExpectToolCall("get_weather", map[string]any{"location": "Lisbon"}).
		ExpectToolCall("get_weather", map[string]any{"location": "Nowhere", "date": "2026-09-07"}).
		ExpectText("Lisbon is sunny; Nowhere is unknown.")
	k := testkit.Agent(WeatherAgent, "s1", model)
	defer k.Close()
	answer, err := k.Ask(context.Background(), "ask", "Lisbon or Nowhere?")
	if err != nil || answer != "Lisbon is sunny; Nowhere is unknown." {
		t.Fatalf("answered %q, %v", answer, err)
	}
	var results []string
	for _, m := range model.Calls[3].Messages {
		if m.Role == "tool" {
			results = append(results, m.Text)
		}
	}
	want := []string{"2026-09-06", "Lisbon: 18C, sunny"}
	if len(results) != 3 || results[0] != want[0] || results[1] != want[1] ||
		!strings.Contains(results[2], "unknown location") {
		t.Fatalf("the model was told %q", results)
	}
}
```

To test an agent through the real sidecar, script the sidecar's model with `ANKKA_MODEL_SCRIPT`, passed
through `env`. The script is a JSON array of turns — `{"text": ...}`, `{"tool": name, "arguments": {...}}`,
`{"tools": [...]}` for several at once, `{"refusal": ...}` — consumed in order, plus standing rules of the
form `{"when": "<substring of the user's message>", "text": ...}` used once the turns run out:

**Scala**

```scala
test("the lookup tool reads the real cart and feeds its contents back to the model") {
  fill("cart-a")
  model
    .expectToolCall("lookup", Json.obj("cartId" -> Json.str("cart-a")))
    .expectText("You have a Widget and a Gadget.")

  val reply = assistant("s-tool").call(CartAssistant.ask).invoke("What is in cart-a?")

  assertEquals(reply, "You have a Widget and a Gadget.")
  assertEquals(model.callCount, 2, "the tool result should have earned a second model turn")
  // Items are held sorted by product id, so this order is stable.
  assertEquals(toolResultIn(1), Some("2 x Widget, 1 x Gadget"))
}
```

**Kotlin**

```kotlin
val model = TestModelProvider().expectText("Oslo reaches 21")
val service =
    Ankka.service("weather")
        .withModule(Agents.module().withModelProvider(model))
        .register(WeatherAgent::class.java)
        .withEndpoint(ChatEndpoint::class.java)
AnkkaTestKit.start(service).use { kit ->
```

**Python**

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

**TypeScript**

```ts
const ANSWER_SCRIPT = JSON.stringify([
  { tool: "cart_total", arguments: { cartId: "q1" } },
  // The first answer cites nothing, so the rule in this process sends it back.
  { tool: "complete_task", arguments: { answer: "3 items", sources: [] } },
  { tool: "complete_task", arguments: { answer: "Cart q1 holds 3 items.", sources: ["cart_total"] } },
])

test("the loop runs in the sidecar, the tool and the rule here, and the typed result is read back", async () => {
  const kit = await AnkkaTestKit.start(service(), { env: { ANKKA_MODEL_SCRIPT: ANSWER_SCRIPT } })
  try {
    assert.equal((await kit.http.post("/carts/q1/items", { productId: "p1", name: "Pen", quantity: 2 })).status, 204)
    assert.equal((await kit.http.post("/carts/q1/items", { productId: "p2", name: "Ink", quantity: 1 })).status, 204)
    const asked = await kit.http.post("/questions/ask", "How many items are in q1?")
    assert.equal(asked.status, 200, asked.text())
    const { taskId } = asked.json() as { taskId: string }

    const done = await kit.client.forTask(taskId).wait(ANSWER, { timeoutMs: 60_000 })
    assert.equal(done.status, "completed")
    assert.deepEqual(done.result, { answer: "Cart q1 holds 3 items.", sources: ["cart_total"] })
    assert.equal(done.iterations, 3)
  } finally {
    await kit.stop()
  }
})
```

**Go**

```go
func TestAnApprovalIsDecidedThroughTheSidecar(t *testing.T) {
	// The sidecar's model, scripted: turns consumed in order, across every session.
	script := `[{"tool":"issue_refund","arguments":{"order":"o-7","amount":12}},` +
		`{"text":"Refunded 12 on o-7."},` +
		`{"tool":"issue_refund","arguments":{"order":"o-7","amount":12}}]`
	kit := testkit.StartT(t, ankka.NewService(ApprovalAgent, SupportEndpoint),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": script}))
	ctx := t.Context()
```

`ANKKA_MODEL_SCRIPT` may also name a file holding the script. A sidecar with a real provider configured
— a vendor's key, or a provider of its own — uses it as the default even when a script is set; the script
is still nameable as `scripted`.

### Testing a model provider

A scripted model proves ankka's behaviour, not a provider's wire. To test a provider at an address — a
preset, a server of your own, a proxy — point it at `ModelStandIn`, a model server on loopback that
speaks Anthropic's protocol and both shapes of OpenAI's from one script of `ModelResponse` values,
streams when asked, and records every request as JSON:

**Scala**

```scala
import com.thinkmorestupidless.ankka.agent.{ModelResponse, ModelStandIn}
import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider

val standIn = ModelStandIn.start()
standIn.answer(ModelResponse("Paris"))
val local = OpenAiProvider.at("local", s"${standIn.address}/v1", "qwen3:8b")
// … call it, then assert on standIn.lastRequest.body
standIn.close()
```

`failNext(status)`, `reportNoUsage()`, `rejectSetting(field)` and `hang()` make it answer as a real
endpoint sometimes does; with nothing scripted it answers 418, so a test that forgot a reply fails.
`TestModelProvider(model, name)` names a scripted model as a service would name a vendor's, for a test
with several providers in one service.

Tests against real endpoints are skipped unless their variables are set: `OPENAI_API_KEY` and
`ANTHROPIC_API_KEY` for the vendors' own APIs, `ANKKA_MODEL_<PRESET>_KEY` for a preset's, and
`ANKKA_LOCAL_MODEL_URL` for a model server on the machine.
