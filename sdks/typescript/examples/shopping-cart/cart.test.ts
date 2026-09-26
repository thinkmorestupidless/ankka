// The cart's own tests: the entity through the unit testkit (no sidecar), and, with ANKKA_SLOW=1 and
// Docker, the whole service through the real sidecar and a throwaway Postgres.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { done } from "ankka"
import { AnkkaTestKit, EventSourcedTestKit } from "ankka/testkit"
import { ShoppingCartEntity } from "./entity.ts"
import { service } from "./main.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

describe("the cart entity, without a sidecar", () => {
  // docs:start unit-test
  test("adds an item and replies done", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    const result = await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
    assert.deepEqual(result.events, [{ type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } }])
    assert.equal(result.reply, done)
    assert.equal(kit.state.items.length, 1)
  })
  // docs:end unit-test

  test("merges quantities for a product already in the cart, sorted by product", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p2", name: "Ink", quantity: 1 })
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 3 })
    assert.deepEqual(kit.state.items, [
      { productId: "p1", name: "Pen", quantity: 5 },
      { productId: "p2", name: "Ink", quantity: 1 },
    ])
    const total = await kit.call(ShoppingCartEntity.handlers.totalQuantity)
    assert.equal(total.reply, 6)
  })

  test("refuses a non-positive quantity and a checkout of an empty cart, persisting nothing", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    const bad = await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 0 })
    assert.equal(bad.error?.code, "BAD_REQUEST")
    assert.deepEqual(bad.events, [])
    const empty = await kit.call(ShoppingCartEntity.handlers.checkout)
    assert.match(empty.error?.message ?? "", /empty cart/)
    assert.equal(kit.sequence, 0n)
  })

  test("checkout persists the event, replies the checked-out cart and deletes the entity", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
    const result = await kit.call(ShoppingCartEntity.handlers.checkout)
    assert.deepEqual(result.events, [{ type: "CheckedOut" }])
    assert.equal(result.reply?.checkedOut, true)
    assert.equal(result.retention?.kind, "delete-now")
    const again = await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 1 })
    assert.equal(again.error?.code, "CONFLICT")
  })

  test("removing a product that is not there is NOT_FOUND", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    const r = await kit.call(ShoppingCartEntity.handlers.removeItem, "nope")
    assert.equal(r.error?.code, "NOT_FOUND")
  })
})

describe("the cart through the real sidecar", { skip: slow }, () => {
  // docs:start integration-test
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
  // docs:end integration-test

  test("every route answers as the entity decides", async () => {
    const kit = await AnkkaTestKit.start(service())
    try {
      assert.equal((await kit.http.post("/carts/c2/items", { productId: "p2", name: "Ink", quantity: 1 })).status, 204)
      assert.equal((await kit.http.post("/carts/c2/items", { productId: "p1", name: "Pen", quantity: 2 })).status, 204)
      assert.equal((await kit.http.get("/carts/c2/total")).text(), "3")
      assert.equal((await kit.http.get("/carts/awkward")).text(), "literal")
      const bad = await kit.http.post("/carts/c2/items", { productId: "p3", name: "X", quantity: 0 })
      assert.equal(bad.status, 400)
      assert.equal((await kit.http.delete("/carts/c2/items/nope")).status, 404)
      assert.equal((await kit.http.delete("/carts/c2/items/p2")).status, 204)
      const checkedOut = (await kit.http.post("/carts/c2/checkout")).json() as { checkedOut: boolean; items: unknown[] }
      assert.equal(checkedOut.checkedOut, true)
      assert.equal(checkedOut.items.length, 1)
      const fresh = (await kit.http.get("/carts/c2")).json() as { items: unknown[]; checkedOut: boolean }
      assert.deepEqual(fresh, { cartId: "c2", items: [], checkedOut: false })
    } finally {
      await kit.stop()
    }
  })
})
