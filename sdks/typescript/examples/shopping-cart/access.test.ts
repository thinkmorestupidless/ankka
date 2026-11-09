// The access rules, the request's parameters and headers, the wallet and the fleet, with no sidecar.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { Ankka, type ComponentClient } from "ankka"
import { ConsumerTestKit, EndpointTestKit, EventSourcedTestKit } from "ankka/testkit"
import { AccountEndpoint, CallersEndpoint, CartsEndpoint, MixedAclEndpoint, SearchEndpoint, WalletEndpoint } from "./access.ts"
import { WalletEntity } from "./wallet.ts"
import { BuildsConsumer, Vehicles } from "./fleet.ts"
import { CheckoutNotices } from "./checkoutNotice.ts"

describe("query parameters and headers", () => {
  test("required, optional-with-default, repeated and flag parameters", async () => {
    const kit = EndpointTestKit.of(SearchEndpoint)
    assert.deepEqual((await kit.get("/search/?q=pens&tag=a&tag=b&verbose")).json(), { term: "pens", limit: 20, tags: ["a", "b"], verbose: true })
    assert.deepEqual((await kit.get("/search/?q=ink&limit=5")).json(), { term: "ink", limit: 5, tags: [], verbose: false })
    const missing = await kit.get("/search/")
    assert.equal(missing.status, 400)
    assert.match(missing.text(), /'q' is required/)
  })

  test("a header, and its absence", async () => {
    const kit = EndpointTestKit.of(SearchEndpoint)
    assert.equal((await kit.get("/search/trace", { headers: { "x-trace-id": "t-1" } })).text(), "t-1")
    assert.equal((await kit.get("/search/trace")).text(), "none")
  })

  test("a stream reads its parameters from the request it was given", async () => {
    assert.deepEqual(await EndpointTestKit.of(SearchEndpoint).sse("/search/stream?q=pen&count=3"), ["pen-1", "pen-2", "pen-3"])
  })
})

describe("who may call", () => {
  test("an authenticated route reads its principal", async () => {
    const principal = { subject: "ada", name: null, email: null, emailVerified: false, roles: ["admin", "shopper"], claims: { tier: "gold" }, issuer: "corp" }
    assert.equal((await EndpointTestKit.of(AccountEndpoint).get("/account/me", { principal })).text(), "ada from corp roles=admin,shopper tier=gold")
  })

  test("the caller is always present; outside a cluster it is the local machine", async () => {
    const kit = EndpointTestKit.of(CallersEndpoint)
    assert.equal((await kit.get("/callers/whoami")).text(), "this machine")
    assert.equal((await kit.get("/callers/only-orders")).text(), "admitted: local")
  })

  test("a route's acl is declared on the route, the endpoint's on the endpoint", () => {
    const spec = Ankka.service().register(MixedAclEndpoint).register(CartsEndpoint).register(WalletEndpoint).spec()
    const endpoint = (prefix: string) => spec.endpoints.find((e) => e.prefix === prefix)!
    const route = (prefix: string, id: string) => endpoint(prefix).routes.find((r) => r.id === id)!
    assert.notEqual(route("/mixed", "purge").acl, undefined)
    assert.notEqual(route("/carts", "purge").acl, undefined)
    assert.equal(route("/carts", "getCart").acl, undefined)
    assert.deepEqual(endpoint("/v1/wallets").allowCallers.map((m) => m.kind.case), ["granted"])
  })

  test("a granted route deposits once per idempotency key and names the caller", async () => {
    const client = {
      withMetadata: () => client,
      of: () => ({ call: () => ({ invoke: async (d: { key: string }) => ({ currency: "EUR", balance: 10n, applied: d.key === "k-1" }) }) }),
    } as unknown as ComponentClient
    const kit = EndpointTestKit.of(WalletEndpoint, client)
    const made = await kit.post("/v1/wallets/p-1/EUR/deposits", { amount: 10n }, { headers: { "Idempotency-Key": "k-1" } })
    assert.deepEqual(made.json(), { currency: "EUR", balance: 10, applied: true, by: "local" })
    assert.equal((await kit.post("/v1/wallets/p-1/EUR/deposits", { amount: 10n })).status, 400)
  })

  test("the wallet applies each key once", async () => {
    const kit = EventSourcedTestKit.of(WalletEntity, "p-1")
    const first = await kit.call(WalletEntity.handlers.deposit, { key: "k-1", currency: "EUR", amount: 10n })
    assert.deepEqual(first.reply, { currency: "EUR", balance: 10n, applied: true })
    const again = await kit.call(WalletEntity.handlers.deposit, { key: "k-1", currency: "EUR", amount: 10n })
    assert.deepEqual([again.reply, again.events], [{ currency: "EUR", balance: 10n, applied: false }, []])
  })
})

describe("the fleet", () => {
  test("allotting replicates to the dealer and shipping hands the home over", async () => {
    const kit = EventSourcedTestKit.of(Vehicles, "v1", undefined, { kind: "home", installation: "leipzig" })
    await kit.call(Vehicles.handlers.addStep, "chassis")
    const allotted = await kit.call(Vehicles.handlers.allot, "vienna")
    assert.deepEqual(allotted.events, [{ type: "Allotted", dealer: "vienna" }])
    assert.deepEqual(kit.lastReplication, { kind: "set", add: ["vienna"], remove: [] })
    await kit.call(Vehicles.handlers.handOver, "vienna")
    assert.deepEqual(kit.lastReplication, { kind: "hand-over", to: "vienna" })
    assert.deepEqual(kit.state, { steps: ["chassis"], dealer: "vienna", writtenAt: ["leipzig", "leipzig"] })
  })

  test("a build step is published from every installation, saying where it was written", async () => {
    const kit = ConsumerTestKit.of(BuildsConsumer)
    await kit.onMessage({ type: "StepAdded", step: "paint" }, "v1", { "ankka.installation": "leipzig" })
    await kit.onMessage({ type: "StepAdded", step: "wheels" }, "v1", { "ankka.installation": "vienna", "ankka.local": "false" })
    await kit.onMessage({ type: "Allotted", dealer: "vienna" }, "v1")
    assert.deepEqual(
      kit.produced.map((p) => p.payload),
      [
        { vehicle: "v1", step: "paint", installation: "leipzig", local: true },
        { vehicle: "v1", step: "wheels", installation: "vienna", local: false },
      ],
    )
  })
})

describe("a consumer that publishes onward", () => {
  test("a checkout leaves as a notice; nothing else does", async () => {
    const kit = ConsumerTestKit.of(CheckoutNotices)
    await kit.onMessage({ type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 1 } }, "c1")
    await kit.onMessage({ type: "CheckedOut" }, "c1")
    assert.equal(kit.produced.length, 1)
    assert.equal(kit.produced[0]!.payload.cartId, "c1")
    assert.ok(kit.produced[0]!.payload.at > 0n)
  })
})
