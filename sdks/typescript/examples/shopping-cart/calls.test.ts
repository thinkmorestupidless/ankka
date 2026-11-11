// The entity pages' samples of registering and calling an entity (docs/build/event-sourced-entities.md,
// docs/build/key-value-entities.md), against a client double that records each call.
import { test } from "node:test"
import assert from "node:assert/strict"
import { Ankka, done, type ComponentClient } from "ankka"
import { CheckoutLog, type CheckoutRecord } from "./checkoutLog.ts"
import type { LineItem, ShoppingCart } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"

// docs:start call-entity
export async function addAndRead(client: ComponentClient, item: LineItem): Promise<ShoppingCart> {
  const cart = client.of(ShoppingCartEntity, "c1")
  await cart.call(ShoppingCartEntity.handlers.addItem).invoke(item) // Done
  return cart.call(ShoppingCartEntity.handlers.getCart).invoke()
}
// docs:end call-entity

// docs:start key-value-call
/** Registered like any other component, and called through the component client. */
export function checkoutLogService() {
  return Ankka.service().register(CheckoutLog)
}

export function checkoutRecord(client: ComponentClient, cartId: string): Promise<CheckoutRecord> {
  return client.of(CheckoutLog, cartId).call(CheckoutLog.handlers.get).invoke()
}
// docs:end key-value-call

/** A client double: each call is recorded as `component/id/handler` and answered by `answer`. */
function recording(answer: (handler: string, id: string) => unknown) {
  const asked: string[] = []
  const client = {
    of: (component: { componentId: string }, id: string) => ({
      call: (handler: { name: string }) => ({
        invoke: async () => {
          asked.push(`${component.componentId}/${id}/${handler.name}`)
          return answer(handler.name, id)
        },
      }),
    }),
  }
  return { client: client as unknown as ComponentClient, asked }
}

test("an entity is called by its class and id", async () => {
  const pen = { productId: "p1", name: "Pen", quantity: 2 }
  const { client, asked } = recording((handler, id) => (handler === "add-item" ? done : { cartId: id, items: [pen], checkedOut: false }))
  assert.deepEqual(await addAndRead(client, pen), { cartId: "c1", items: [pen], checkedOut: false })
  assert.deepEqual(asked, ["shopping-cart/c1/add-item", "shopping-cart/c1/get-cart"])
})

test("a key value entity is registered and called like any other", async () => {
  assert.ok(checkoutLogService().validate().component(CheckoutLog.componentId))
  const { client, asked } = recording((_, id) => ({ cartId: id, at: 42n, notified: true }))
  assert.deepEqual(await checkoutRecord(client, "c1"), { cartId: "c1", at: 42n, notified: true })
  assert.deepEqual(asked, ["checkout-log/c1/get"])
})
