// The entity and the view with no sidecar, no database and no network: milliseconds.
//
// Inputs, events, state and replies still round-trip through the codecs, so a shape the codec cannot
// express fails here rather than on first deployment.
import { test } from "node:test"
import assert from "node:assert/strict"
import { EventSourcedTestKit, ViewTestKit } from "ankka/testkit"
import { ItemEntity } from "../src/itemEntity.ts"
import { ItemRows } from "../src/itemRows.ts"

test("adding persists an event and updates the state", async () => {
  const kit = EventSourcedTestKit.of(ItemEntity, "i1")
  const added = await kit.call(ItemEntity.handlers.addItem, { name: "Widget", count: 2 })
  assert.deepEqual(added.events, [{ type: "ItemAdded", name: "Widget", count: 2 }])
  await kit.call(ItemEntity.handlers.addItem, { name: "Widget", count: 3 })
  assert.deepEqual((await kit.call(ItemEntity.handlers.getItem)).reply, { id: "i1", name: "Widget", count: 5 })
})

test("removing more than there is is refused and persists nothing", async () => {
  const kit = EventSourcedTestKit.of(ItemEntity, "i1")
  await kit.call(ItemEntity.handlers.addItem, { name: "Widget", count: 2 })
  const refused = await kit.call(ItemEntity.handlers.removeItem, { count: 3 })
  assert.equal(refused.error?.code, "CONFLICT")
  assert.deepEqual(refused.events, [])
  const removed = await kit.call(ItemEntity.handlers.removeItem, { count: 2 })
  assert.deepEqual(removed.events, [{ type: "ItemRemoved", count: 2 }])
})

test("a count below one is a bad request", async () => {
  const kit = EventSourcedTestKit.of(ItemEntity, "i1")
  const refused = await kit.call(ItemEntity.handlers.addItem, { name: "Widget", count: 0 })
  assert.equal(refused.error?.code, "BAD_REQUEST")
})

test("the view keeps one row per item", async () => {
  const kit = ViewTestKit.of(ItemRows)
  await kit.onChange("i1", { type: "ItemAdded", name: "Widget", count: 2 })
  await kit.onChange("i1", { type: "ItemRemoved", count: 1 })
  assert.deepEqual(kit.get("i1"), { id: "i1", name: "Widget", count: 1 })
})
