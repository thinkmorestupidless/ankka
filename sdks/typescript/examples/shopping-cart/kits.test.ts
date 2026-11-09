// What each unit kit gives you, on the cart's own components: a view, a keyed view, a workflow, an
// agent against a scripted model, and a timed action told its due time.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { type ComponentClient } from "ankka"
import { AgentTestKit, KeyedViewTestKit, ScriptedModel, TimedActionTestKit, ViewTestKit, WorkflowTestKit } from "ankka/testkit"
import { CartRows } from "./cartRows.ts"
import { CheckoutWorkflow } from "./checkoutWorkflow.ts"
import { CartAssistant } from "./assistant.ts"
import { JoinedLeft, JoinedRight, JoinedRows, Reminder } from "./conformance.ts"
import { Vehicles } from "./fleet.ts"
import { Shipments } from "./shipments.ts"

describe("a view, without a sidecar", () => {
  // docs:start view-test
  test("the rows follow the cart's events, one row per cart", async () => {
    const kit = ViewTestKit.of(CartRows)
    await kit.onChange("c1", { type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } })
    assert.deepEqual(kit.get("c1"), { cartId: "c1", quantities: { p1: 2 }, checkedOut: false })
    await kit.onChange("c1", { type: "CheckedOut" })
    assert.equal(kit.get("c1")?.checkedOut, true)
    assert.equal((await kit.onDelete("c1")).kind, "delete-row")
    assert.equal(kit.get("c1"), null)
  })
  // docs:end view-test
})

describe("a keyed view, without a sidecar", () => {
  // docs:start keyed-view-test
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
  // docs:end keyed-view-test
})

describe("a keyed view at its second version", () => {
  test("writes a row per allotted vehicle, and declares the version that rebuilds it", async () => {
    const kit = KeyedViewTestKit.of(Shipments)
    await kit.change(Vehicles, "v1", { type: "StepAdded", step: "paint" })
    assert.equal(kit.get("v1"), null)
    await kit.change(Vehicles, "v1", { type: "Allotted", dealer: "vienna" })
    assert.deepEqual(kit.get("v1"), { vehicle: "v1", dealer: "vienna" })
    assert.equal(Shipments.version, 2)
  })
})

describe("a workflow, without a sidecar", () => {
  // docs:start workflow-test
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
  // docs:end workflow-test
})

describe("an agent against a scripted model, without a sidecar", () => {
  // docs:start scripted-model
  const model = new ScriptedModel().expectToolCall("lookup", { cartId: "c9" }).expectText("Your cart is empty.")
  // docs:end scripted-model

  // docs:start agent-test
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
  // docs:end agent-test
})

describe("a timed action, without a sidecar", () => {
  // docs:start timed-action-test
  test("the handler reads the due time the runtime tells it", async () => {
    const recorded: string[] = []
    const record = async (text: string) => recorded.push(text)
    const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: record }) }) }
    const kit = TimedActionTestKit.of(Reminder, client as unknown as ComponentClient)
    const effect = await kit.invoke(Reminder.actions.tick, "c1", { "ankka.due": "1767225600000" })
    assert.equal(effect.kind, "done")
    assert.deepEqual(recorded, ["due:1767225600000"])
  })
  // docs:end timed-action-test
})
