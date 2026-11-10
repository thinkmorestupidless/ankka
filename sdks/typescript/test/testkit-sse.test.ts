// The unit kit's SSE reads: text frames, and named events beside them (#233); and the stand-ins it exports.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { Endpoint } from "../src/endpoint.ts"
import { Acl, sse, sseEvent } from "../src/routes.ts"
import { EndpointTestKit, noClient, noSecrets, noServices } from "../src/testkit/index.ts"

class Turns extends Endpoint {
  static readonly prefix = "/turns"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    turn: sse("/{id}", async function* (_ep: Turns, req) {
      yield `checking ${req.params.id}`
      yield sseEvent("approval", [{ id: "a-1", tool: "refund" }])
    }),
  }
}

describe("EndpointTestKit and SSE", () => {
  test("sseEvents keeps a named event, with its name and its value as JSON, after the text before it", async () => {
    const kit = EndpointTestKit.of(Turns)
    assert.deepEqual(await kit.sseEvents("/turns/t-1"), [
      { data: "checking t-1" },
      { event: "approval", data: '[{"id":"a-1","tool":"refund"}]' },
    ])
  })

  test("sse is the text frames alone", async () => {
    const kit = EndpointTestKit.of(Turns)
    assert.deepEqual(await kit.sse("/turns/t-2"), ["checking t-2"])
  })
})

describe("the kit's stand-ins", () => {
  test("noClient comes from ankka/testkit, and a call through it is refused", async () => {
    await assert.rejects(noClient().forWorkflow("checkout", "w1").awaitEnd(1_000), /unit testkit has no sidecar/)
  })

  test("so do the stand-ins beside it, noSecrets and noServices", () => {
    assert.equal(typeof noSecrets, "function")
    assert.equal(typeof noServices, "function")
  })
})
