// Caller-naming ACLs and the caller on the request. The sidecar applies the ACL and establishes the
// caller; the SDK declares the ACL in discovery exactly and hands the handler the caller it was sent.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { Ankka } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { Endpoint } from "../src/endpoint.ts"
import { Acl, Callers, get, isAcl } from "../src/routes.ts"
import { s } from "../src/schema.ts"
import { Endpoint_Acl } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { HttpRequestSchema } from "../src/_proto/ankka/protocol/v1/endpoint_pb.ts"
import type { Caller } from "../src/context.ts"
import { startServer, type Started } from "./helpers.ts"

function describeCaller(c: Caller): string {
  switch (c.kind) {
    case "service":
      return `service:${c.project}/${c.name}`
    default:
      return c.kind
  }
}

class Carts extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))
  static readonly routes = {
    whoami: get("/whoami", s.string, (_ep: Carts, req) => describeCaller(req.caller)),
    onlySelf: get("/self", s.string, () => "self", {
      acl: Acl.allowCallers(Callers.self, Callers.service("orders", { project: "billing" }), Callers.anyInProject),
    }),
  }
}

describe("caller-naming acls", () => {
  let started: Started
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Carts))
  })
  after(() => started.stop())

  test("an endpoint's callers are declared in discovery, and a route's on the route", async () => {
    const spec = await started.discovery.discover({ protocolVersion: "1.1", runtimeVersion: "t" })
    const ep = spec.endpoints[0]!
    assert.equal(ep.acl, Endpoint_Acl.CALLERS)
    assert.deepEqual(ep.allowCallers.map((m) => m.kind.case), ["internet", "service"])
    const named = ep.allowCallers[1]!.kind
    assert.equal(named.case === "service" ? named.value.name : "", "orders")
    assert.equal(named.case === "service" ? named.value.project : "x", undefined, "no project means this service's own")
    const route = ep.routes.find((r) => r.id === "onlySelf")!
    assert.equal(route.acl, Endpoint_Acl.CALLERS)
    assert.deepEqual(route.allowCallers.map((m) => m.kind.case), ["self", "service", "anyInProject"])
    assert.equal(ep.routes.find((r) => r.id === "whoami")!.acl, undefined)
  })

  test("the handler reads the caller the sidecar sent, and none reads as local", async () => {
    const ask = async (caller?: Parameters<typeof create<typeof HttpRequestSchema>>[1]) => {
      const r = await started.http.handle(create(HttpRequestSchema, { endpointId: "Carts", routeId: "whoami", ...(caller ?? {}) }))
      return r.message.case === "response" ? new TextDecoder().decode(r.message.value.body) : "failed"
    }
    assert.equal(await ask(), "local")
    assert.equal(await ask({ caller: { kind: { case: "gateway", value: {} } } }), "gateway")
    assert.equal(await ask({ caller: { kind: { case: "service", value: { project: "p", name: "s" } } } }), "service:p/s")
  })

  test("an acl is one of the three words or a callers rule naming someone", () => {
    assert.ok(isAcl(Acl.allowAll) && isAcl(Acl.denyAll) && isAcl(Acl.authenticated))
    assert.ok(isAcl(Acl.allowCallers(Callers.internet)))
    assert.ok(!isAcl({ kind: "callers", callers: [] }))
    assert.ok(!isAcl("everyone"))
  })
})
