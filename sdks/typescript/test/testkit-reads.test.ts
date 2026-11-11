// The unit kit's answers for a workflow's standing and how a copy stands (protocol 1.24): the TypeScript rows
// of "a component test reads a stubbed standing" (features/polyglot/standing.feature) and "a component test
// reads a stubbed copy" (features/replication/status.feature). The endpoint reads through its own client,
// which the kit's `client` argument replaces with `stubClient`.

import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { Endpoint } from "../src/endpoint.ts"
import { Acl, get } from "../src/routes.ts"
import { s } from "../src/schema.ts"
import { EndpointTestKit, stubClient } from "../src/testkit/index.ts"
import { CheckoutWorkflow } from "../examples/shopping-cart/checkoutWorkflow.ts"

class Reads extends Endpoint {
  static readonly prefix = "/reads"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    standing: get("/plans/{id}", s.string, async (ep: Reads, req) => {
      const read = await ep.client.forWorkflow("plan", req.params.id).lifecycle()
      return `${read.status} ${read.pendingStep ?? "-"} ${read.failure ?? "-"} ${JSON.stringify(read.retries)}`
    }),
    checkout: get("/checkouts/{id}", s.string, async (ep: Reads, req) => (await ep.client.of(CheckoutWorkflow, req.params.id).lifecycle()).status),
    copy: get("/vehicles/{id}", s.string, async (ep: Reads, req) => {
      const read = await ep.client.forEventSourcedEntity("vehicle", req.params.id).replication()
      return `${read.home} ${read.replicationSet.join(",")} ${read.pendingHandover ?? "-"} ${read.conflict ?? "-"} ${read.position}`
    }),
  }
}

describe("stubClient", () => {
  test("a stubbed standing is read: a failed workflow, its reason and its retries", async () => {
    const client = stubClient({ lifecycle: [["plan", "p1", { status: "failed", pendingStep: "deposit", retries: { deposit: 2 }, failure: "the bank was shut" }]] })
    const answer = await EndpointTestKit.of(Reads, client).get("/reads/plans/p1")
    assert.equal(answer.status, 200)
    assert.equal(answer.text(), `failed deposit the bank was shut {"deposit":2}`)
  })

  test("a component named by its class is the same component", async () => {
    const client = stubClient({ lifecycle: [[CheckoutWorkflow, "c1", { status: "running", pendingStep: "reserve", retries: {} }]] })
    assert.equal((await EndpointTestKit.of(Reads, client).get("/reads/checkouts/c1")).text(), "running")
  })

  test("a stubbed copy is read: its home, its set, a handover waiting, a conflict and its position", async () => {
    const client = stubClient({
      replication: [["vehicle", "v1", { home: "leipzig", replicationSet: ["leipzig", "vienna"], pendingHandover: "vienna", conflict: "graz", position: 4n }]],
    })
    assert.equal((await EndpointTestKit.of(Reads, client).get("/reads/vehicles/v1")).text(), "leipzig leipzig,vienna vienna graz 4")
  })

  test("an unstubbed read is refused, naming what was not stubbed", async () => {
    const client = stubClient({ lifecycle: [["plan", "p1", { status: "completed", retries: {} }]] })
    const kit = EndpointTestKit.of(Reads, client)
    const standing = await kit.get("/reads/plans/p2")
    assert.equal(standing.status, 500)
    const copy = await kit.get("/reads/vehicles/v9")
    assert.equal(copy.status, 500)
    await assert.rejects(client.forWorkflow("plan", "p2").lifecycle(), /the standing of 'p2' of 'plan' was not stubbed/)
    await assert.rejects(client.forEventSourcedEntity("vehicle", "v9").replication(), /how 'v9' of 'vehicle' stands was not stubbed/)
  })

  test("every other call is refused as noClient refuses it", async () => {
    const client = stubClient({})
    await assert.rejects(client.forEventSourcedEntity("shopping-cart", "c1").call("get-cart").invoke(), /the unit testkit has no sidecar/)
  })
})
