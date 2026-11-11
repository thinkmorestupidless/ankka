// The view over a topic through the unit testkit: each message is keyed by its subject, the product.
import { test } from "node:test"
import assert from "node:assert/strict"
import { Ankka } from "ankka"
import { ViewTestKit } from "ankka/testkit"
import { StockLevels } from "./stock.ts"

test("a product's level is the sum of the topic's messages about it", async () => {
  const kit = ViewTestKit.of(StockLevels)
  await kit.onChange("p1", { productId: "p1", delta: 5 })
  await kit.onChange("p1", { productId: "p1", delta: -2 })
  await kit.onChange("p2", { productId: "p2", delta: 1 })
  assert.deepEqual(kit.get("p1"), { productId: "p1", level: 3 })
  assert.deepEqual(kit.get("p2"), { productId: "p2", level: 1 })
  assert.equal(kit.get("p3"), null)
})

test("the view declares the topic as its source", () => {
  const view = Ankka.service().register(StockLevels).spec().components.find((c) => c.id === "stock-levels")
  assert.ok(view)
  assert.equal(view.detail.case === "view" ? view.detail.value.source?.source.value : undefined, "stock-events")
})
