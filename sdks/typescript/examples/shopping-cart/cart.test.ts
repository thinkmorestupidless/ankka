// The cart's own tests: the entity through the unit testkit (no sidecar), and, with ANKKA_SLOW=1 and
// Docker, the whole service through the real sidecar and a throwaway Postgres.
import { test, describe } from "node:test"
import { createServer as createHttpServer } from "node:http"
import type { AddressInfo } from "node:net"
import assert from "node:assert/strict"
import { Ankka, done, ScriptedServices, type ComponentClient, type Services } from "ankka"
import { AnkkaTestKit, ConsumerTestKit, EndpointTestKit, EventSourcedTestKit, GraphConsumerTestKit, KeyValueTestKit } from "ankka/testkit"
import { CallingEndpoint } from "./calling.ts"
import { CartGraph } from "./cartGraph.ts"
import { CartContentsGraph } from "./cartContentsGraph.ts"
import type { ShoppingCart } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"
import { CheckoutLog } from "./checkoutLog.ts"
import { service } from "./main.ts"
import { CheckoutFanout } from "./conformance.ts"

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

  test("checkout persists the event, replies the checked-out cart and keeps it", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
    const result = await kit.call(ShoppingCartEntity.handlers.checkout)
    assert.deepEqual(result.events, [{ type: "CheckedOut" }])
    assert.equal(result.reply?.checkedOut, true)
    assert.equal(result.retention, null)
    assert.deepEqual(kit.state, { cartId: "c1", items: [{ productId: "p1", name: "Pen", quantity: 2 }], checkedOut: true })
  })

  test("a checked-out cart refuses every change, and none of them changes it", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
    await kit.call(ShoppingCartEntity.handlers.checkout)
    const refusals = [
      await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p2", name: "Ink", quantity: 1 }),
      await kit.call(ShoppingCartEntity.handlers.removeItem, "p1"),
      await kit.call(ShoppingCartEntity.handlers.checkout),
      await kit.call(ShoppingCartEntity.handlers.discard),
    ]
    for (const refused of refusals) {
      assert.equal(refused.error?.code, "CONFLICT")
      assert.match(refused.error?.message ?? "", /already checked out/)
      assert.deepEqual(refused.events, [])
    }
    assert.equal(kit.state.items.length, 1)
  })

  test("discard persists the event and deletes the entity", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
    const result = await kit.call(ShoppingCartEntity.handlers.discard)
    assert.deepEqual(result.events, [{ type: "Discarded" }])
    assert.equal(result.reply, done)
    assert.equal(result.retention?.kind, "delete-now")
  })

  test("removing a product that is not there is NOT_FOUND", async () => {
    const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    const r = await kit.call(ShoppingCartEntity.handlers.removeItem, "nope")
    assert.equal(r.error?.code, "NOT_FOUND")
  })
})

describe("the checkout log, without a sidecar", () => {
  // docs:start key-value-test
  test("recording a checkout replaces the value", async () => {
    const kit = KeyValueTestKit.of(CheckoutLog, "c1")
    const recorded = await kit.call(CheckoutLog.handlers.record, 1700000000000n)
    assert.equal(recorded.reply, done)
    assert.equal(kit.state.notified, true)
    assert.equal(kit.state.at, 1700000000000n)
  })
  // docs:end key-value-test

  test("an unrecorded cart starts from the empty state, carrying its own id", async () => {
    const kit = KeyValueTestKit.of(CheckoutLog, "c2")
    const got = await kit.call(CheckoutLog.handlers.get)
    assert.deepEqual(got.reply, { cartId: "c2", at: 0n, notified: false })
  })
})

describe("a consumer that publishes several messages, without a sidecar or a broker", () => {
  // docs:start consumer-test
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
  // docs:end consumer-test
})

describe("the cart's graph, without a sidecar or a broker", () => {
  const pen = { productId: "p1", name: "Pen", quantity: 1 }

  // docs:start graph-test
  test("a checkout publishes the cart, its checkout and the edge between them, at the event's sequence number", async () => {
    const kit = GraphConsumerTestKit.of(CartGraph)
    const elements = await kit.onMessage({ type: "CheckedOut" }, { subject: "c1", sequence: 4 })
    assert.deepEqual(elements, [
      { kind: "node", id: "cart:c1", version: 4, labels: ["Cart"], properties: { cartId: "c1", checkedOut: true } },
      { kind: "node", id: "checkout:c1", version: 4, labels: ["Checkout"], properties: { cartId: "c1" } },
      { kind: "edge", id: "checked-out:c1", version: 4, type: "CHECKED_OUT", from: "cart:c1", to: "checkout:c1", properties: {} },
    ])
  })
  // docs:end graph-test

  test("a change to its items publishes the cart, not yet checked out", async () => {
    const kit = GraphConsumerTestKit.of(CartGraph)
    const cart = (version: number) => [{ kind: "node", id: "cart:c1", version, labels: ["Cart"], properties: { cartId: "c1", checkedOut: false } }]
    assert.deepEqual(await kit.onMessage({ type: "ItemAdded", item: pen }, { subject: "c1", sequence: 1 }), cart(1))
    assert.deepEqual(await kit.onMessage({ type: "ItemRemoved", productId: "p1" }, { subject: "c1", sequence: 2 }), cart(2))
  })

  test("a discarded cart publishes nothing until its deletion, which marks its node above everything before", async () => {
    const kit = GraphConsumerTestKit.of(CartGraph)
    assert.deepEqual(await kit.onMessage({ type: "Discarded" }, { subject: "c2", sequence: 2 }), [])
    assert.deepEqual(await kit.onDelete({ subject: "c2", sequence: 3 }), [{ kind: "tombstone", element: "node", id: "cart:c2", version: 3 }])
    // Taking items again, the cart is published above its tombstone.
    const again = await kit.onMessage({ type: "ItemAdded", item: pen }, { subject: "c2", sequence: 4 })
    assert.equal(again[0]!.version, 4)
  })

  test("the cart graph for a history: the versions are the sequence numbers", async () => {
    const kit = GraphConsumerTestKit.of(CartGraph)
    const history = [{ type: "ItemAdded", item: pen }, { type: "ItemAdded", item: { ...pen, productId: "p2" } }, { type: "ItemRemoved", productId: "p2" }, { type: "CheckedOut" }] as const
    const published: [string, number | bigint][] = []
    for (const [i, event] of history.entries()) {
      for (const e of await kit.onMessage(event, { subject: "c3", sequence: i + 1 })) published.push([`${e.kind === "edge" ? "edge" : "node"}:${e.id}`, e.version])
    }
    assert.deepEqual(published, [["node:cart:c3", 1], ["node:cart:c3", 2], ["node:cart:c3", 3], ["node:cart:c3", 4], ["node:checkout:c3", 4], ["edge:checked-out:c3", 4]])
  })

  /** A client double for a consumer that reads the cart: every call answers this cart. */
  function cartAsRead(cart: ShoppingCart): ComponentClient {
    const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: async () => cart }) }) }
    return client as unknown as ComponentClient
  }

  test("the cart's contents are published from the cart as read, at the version of the event being handled", async () => {
    const ink = { productId: "p2", name: "Ink", quantity: 3 }
    const kit = GraphConsumerTestKit.of(CartContentsGraph, cartAsRead({ cartId: "c1", items: [pen, ink], checkedOut: false }))
    const contents = (version: number) => [
      { kind: "node", id: "cart-contents:c1", version, labels: ["CartContents"], properties: { cartId: "c1", lines: 2, quantity: 4 } },
    ]
    assert.deepEqual(await kit.onMessage({ type: "ItemAdded", item: ink }, { subject: "c1", sequence: 2 }), contents(2))
    // A cart read ahead of the event is published at the event's version; the later event publishes the
    // same state at its own, which is what a graph keeps.
    assert.deepEqual(await kit.onMessage({ type: "ItemAdded", item: pen }, { subject: "c1", sequence: 1 }), contents(1))
  })

  test("the cart's contents are tombstoned when the cart is deleted, and a cart that cannot be read fails the change", async () => {
    const kit = GraphConsumerTestKit.of(CartContentsGraph, cartAsRead({ cartId: "c1", items: [], checkedOut: false }))
    assert.deepEqual(await kit.onMessage({ type: "Discarded" }, { subject: "c1", sequence: 3 }), [])
    assert.deepEqual(await kit.onDelete({ subject: "c1", sequence: 4 }), [{ kind: "tombstone", element: "node", id: "cart-contents:c1", version: 4 }])
    // The unit kit's own client refuses, as a failed call does: the change is delivered again.
    await assert.rejects(GraphConsumerTestKit.of(CartContentsGraph).onMessage({ type: "ItemAdded", item: pen }, { subject: "c1" }))
  })

  test("it is registered where there is a broker to publish to, and only there", () => {
    const ids = () => [...service().validate().components.keys()]
    const before = process.env.ANKKA_KAFKA_BOOTSTRAP_SERVERS
    try {
      delete process.env.ANKKA_KAFKA_BOOTSTRAP_SERVERS
      assert.equal(ids().includes("cart-graph"), false)
      process.env.ANKKA_KAFKA_BOOTSTRAP_SERVERS = "kafka:9092"
      assert.equal(ids().includes("cart-graph"), true)
      assert.equal(ids().includes("cart-contents-graph"), true)
    } finally {
      if (before === undefined) delete process.env.ANKKA_KAFKA_BOOTSTRAP_SERVERS
      else process.env.ANKKA_KAFKA_BOOTSTRAP_SERVERS = before
    }
  })
})

describe("calling another service, without a sidecar", () => {
  test("the calling endpoint answers what the other service answered", async () => {
    const scripted = new ScriptedServices()
      .answer("carts", (request) => ScriptedServices.text(`you asked ${request.path}`))
      .unresolvable("ledger")
    class Scripted extends CallingEndpoint {
      override get services(): Services {
        return scripted
      }
    }
    const kit = EndpointTestKit.of(Scripted)
    assert.equal((await kit.get("/calling/call/carts")).text(), "you asked /callers/whoami")
    assert.equal((await kit.get("/calling/call/carts", { query: { path: "/callers/orders-alone" } })).text(), "you asked /callers/orders-alone")
    assert.equal((await kit.get("/calling/call/ledger")).status, 503)
  })
})

describe("calling another service through the real sidecar", { skip: slow }, () => {
  test("a call to another service goes through the sidecar to where it was told", async () => {
    // The sidecar in its container is told where another service is as a Scala service would be
    // (`ankka.local-services.<name>`, through `JAVA_OPTS`), and the call reaches a stand-in here.
    const received: string[] = []
    const standIn = createHttpServer((request, response) => {
      received.push(request.url ?? "")
      response.writeHead(200, { "Content-Type": "text/plain" }).end("the stand-in answered")
    })
    await new Promise<void>((r) => standIn.listen(0, "0.0.0.0", () => r()))
    const address = `http://host.docker.internal:${(standIn.address() as AddressInfo).port}`
    try {
      const kit = await AnkkaTestKit.start(Ankka.service().register(CallingEndpoint), { env: { JAVA_OPTS: `-Dankka.local-services.carts=${address}` } })
      try {
        const answered = await kit.http.get("/calling/call/carts")
        assert.deepEqual([answered.status, answered.text()], [200, "the stand-in answered"])
        assert.deepEqual(received, ["/callers/whoami"])
      } finally {
        await kit.stop()
      }
    } finally {
      standIn.closeAllConnections()
      await new Promise<void>((r) => standIn.close(() => r()))
    }
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
      // Kept after the checkout, and refusing changes after a restart too.
      await kit.restart()
      const kept = (await kit.http.get("/carts/c2")).json() as { items: unknown[]; checkedOut: boolean }
      assert.deepEqual(kept, { cartId: "c2", items: [{ productId: "p1", name: "Pen", quantity: 2 }], checkedOut: true })
      assert.equal((await kit.http.post("/carts/c2/items", { productId: "p1", name: "Pen", quantity: 1 })).status, 409)
      assert.equal((await kit.http.delete("/carts/c2")).status, 409)
      // Discarding deletes a cart, so the id is fresh again.
      assert.equal((await kit.http.post("/carts/c3/items", { productId: "p1", name: "Pen", quantity: 1 })).status, 204)
      assert.equal((await kit.http.delete("/carts/c3")).status, 204)
      const fresh = (await kit.http.get("/carts/c3")).json() as { items: unknown[]; checkedOut: boolean }
      assert.deepEqual(fresh, { cartId: "c3", items: [], checkedOut: false })
    } finally {
      await kit.stop()
    }
  })
})

test("the watch socket answers what it does not understand, and ends with its socket", async () => {
  const { EndpointTestKit } = await import("ankka/testkit")
  const { ShoppingCartEndpoint } = await import("./endpoint.ts")
  const run = await EndpointTestKit.of(ShoppingCartEndpoint).socket("/carts/c1/watch", ["nonsense"])
  assert.deepEqual(run, { sent: [JSON.stringify({ error: "unknown request 'nonsense'; send refresh" })], ended: "finished" })
})
