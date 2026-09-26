// The first service's tests, as the getting-started page shows them: the entity with no sidecar, and the
// whole service through the real sidecar (ANKKA_SLOW=1 and Docker).
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { AnkkaTestKit, EventSourcedTestKit } from "ankka/testkit"
import { ShoppingCartEntity } from "./cart.ts"
import { service } from "./main.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

// docs:start unit-test
const pen = { productId: "p1", name: "Pen", quantity: 2 }

test("adding an item persists one event", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, pen)
  assert.deepEqual(result.events, [{ type: "ItemAdded", item: pen }])
  assert.deepEqual((await kit.call(ShoppingCartEntity.handlers.getCart)).reply?.items, [pen])
})

test("a refused command persists nothing", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, { ...pen, quantity: 0 })
  assert.equal(result.error?.code, "BAD_REQUEST")
  assert.deepEqual(result.events, [])
})
// docs:end unit-test

describe("through the real sidecar", { skip: slow }, () => {
  // docs:start integration-test
  test("a cart survives a restart", async () => {
    const kit = await AnkkaTestKit.start(service())
    try {
      await kit.http.post("/carts/c1/items", { productId: "p1", name: "Pen", quantity: 2 })
      await kit.restart()                                  // a new sidecar, the same database
      const cart = (await kit.http.get("/carts/c1")).json() as { items: { name: string }[] }
      assert.equal(cart.items[0]?.name, "Pen")
    } finally {
      await kit.stop()
    }
  })
  // docs:end integration-test
})
