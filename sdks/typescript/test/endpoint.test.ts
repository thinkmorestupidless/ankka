// Endpoints through the Http servicer: path parameters (typed), body, query, headers, replies and
// statuses, SSE, and the request-scoped `this.request`.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { Ankka } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { Endpoint } from "../src/endpoint.ts"
import { Acl, HttpProblem, del, get, post, sse } from "../src/routes.ts"
import { Done, done, s } from "../src/schema.ts"
import { CommandError, ErrorCode } from "../src/effects/common.ts"
import { Endpoint_Acl } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { create, type MessageInitShape } from "@bufbuild/protobuf"
import { HttpRequestSchema } from "../src/_proto/ankka/protocol/v1/endpoint_pb.ts"
import { startServer, type Started } from "./helpers.ts"

const Thing = s.record("Thing", { id: s.string, n: s.int, tag: s.option(s.string) })
type Thing = { id: string; n: number; tag: string | null }

class Things extends Endpoint {
  static readonly prefix = "/things"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    get: get("/{id}", Thing, (ep: Things, req) => ep.find(req.params.id)),
    create: post("/{id}", Thing, Thing, (_ep: Things, req, body) => ({ ...body, id: req.params.id })),
    bump: post("/{id}/bump", Done, (): typeof done => done),
    remove: del("/{id}", Done, () => undefined as unknown as typeof done),
    text: get("/text", s.string, () => "hello"),
    twice: get("/n/{n}", s.int, (_ep: Things, req) => req.params.n * 2, { params: { n: s.int } }),
    teapot: get("/teapot", Thing, () => {
      throw new HttpProblem(418, "short and stout")
    }),
    conflict: get("/conflict", Thing, () => {
      throw new CommandError({ message: "already", code: ErrorCode.Conflict })
    }),
    boom: get("/boom", Thing, () => {
      throw new Error("kaboom")
    }),
    admin: get("/admin", s.string, (_ep: Things, req) => req.principal?.subject ?? "nobody", { acl: Acl.authenticated }),
    events: sse("/{id}/events", async function* (_ep: Things, req) {
      yield `start ${req.params.id}`
      yield " leading space"
      yield "two\nlines"
    }),
  }

  find(id: string): Thing {
    const n = Number(this.request.query.get("n") ?? 0)
    return { id, n, tag: this.request.headers.get("x-tag") }
  }
}

const utf8 = new TextEncoder()

function request(routeId: string, pathArgs: string[], extra: MessageInitShape<typeof HttpRequestSchema> = {}) {
  return create(HttpRequestSchema, { endpointId: "Things", routeId, pathArgs, ...extra })
}

describe("the Http servicer", () => {
  let started: Started
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Things))
  })
  after(() => started.stop())

  test("discovery declares the endpoint, its routes and the one route's own acl", async () => {
    const spec = await started.discovery.discover({ protocolVersion: "1.0", runtimeVersion: "t" })
    const ep = spec.endpoints[0]!
    assert.equal(ep.id, "Things")
    assert.equal(ep.prefix, "/things")
    assert.equal(ep.acl, Endpoint_Acl.ALLOW_ALL)
    const byId = Object.fromEntries(ep.routes.map((r) => [r.id, r]))
    assert.deepEqual({ method: byId["get"]!.method, template: byId["get"]!.template, hasBody: byId["get"]!.hasBody, streaming: byId["get"]!.streaming }, { method: "GET", template: "/{id}", hasBody: false, streaming: false })
    assert.equal(byId["create"]!.hasBody, true)
    assert.equal(byId["events"]!.streaming, true)
    assert.equal(byId["admin"]!.acl, Endpoint_Acl.AUTHENTICATED)
    assert.equal(byId["get"]!.acl, undefined, "a route without its own acl leaves the field unset")
  })

  test("path parameters, query and headers reach the handler; the reply is encoded as JSON", async () => {
    const r = await started.http.handle(request("get", ["t1"], { query: [{ name: "n", value: "4" }], headers: [{ name: "X-Tag", value: "blue" }] }))
    assert.equal(r.message.case, "response")
    const res = r.message.case === "response" ? r.message.value : undefined
    assert.equal(res?.status, 200)
    assert.equal(res?.contentType, "application/json")
    assert.equal(new TextDecoder().decode(res?.body), '{"id":"t1","n":4,"tag":"blue"}')
  })

  test("a body is decoded with its shape; a typed parameter is parsed", async () => {
    const r = await started.http.handle(request("create", ["t2"], { body: utf8.encode('{"id":"ignored","n":7,"tag":null}') }))
    assert.equal(new TextDecoder().decode(r.message.case === "response" ? r.message.value.body : undefined), '{"id":"t2","n":7,"tag":null}')
    const t = await started.http.handle(request("twice", ["21"]))
    const res = t.message.case === "response" ? t.message.value : undefined
    assert.equal(res?.contentType, "text/plain; charset=utf-8")
    assert.equal(new TextDecoder().decode(res?.body), "42")
    const bad = await started.http.handle(request("twice", ["x"]))
    assert.equal(bad.message.case === "response" && bad.message.value.status, 400)
    const badBody = await started.http.handle(request("create", ["t3"], { body: utf8.encode('{"n":"seven"}') }))
    assert.equal(badBody.message.case === "response" && badBody.message.value.status, 400)
  })

  test("done and undefined answer 204; a string answers text/plain", async () => {
    const d = await started.http.handle(request("bump", ["t1"]))
    assert.equal(d.message.case === "response" && d.message.value.status, 204)
    const u = await started.http.handle(request("remove", ["t1"]))
    assert.equal(u.message.case === "response" && u.message.value.status, 204)
    const t = await started.http.handle(request("text", []))
    assert.equal(new TextDecoder().decode(t.message.case === "response" ? t.message.value.body : undefined), "hello")
  })

  test("HttpProblem answers its status; CommandError maps its code; anything else is a failure", async () => {
    const teapot = await started.http.handle(request("teapot", []))
    assert.equal(teapot.message.case === "response" && teapot.message.value.status, 418)
    assert.equal(new TextDecoder().decode(teapot.message.case === "response" ? teapot.message.value.body : undefined), "short and stout")
    const conflict = await started.http.handle(request("conflict", []))
    assert.equal(conflict.message.case === "response" && conflict.message.value.status, 409)
    const boom = await started.http.handle(request("boom", []))
    assert.equal(boom.message.case, "failure")
    assert.match(boom.message.case === "failure" ? boom.message.value.error?.message ?? "" : "", /kaboom/)
  })

  test("an unknown route id is 404", async () => {
    const r = await started.http.handle(request("nope", []))
    assert.equal(r.message.case === "response" && r.message.value.status, 404)
  })

  test("the principal reaches an authenticated route", async () => {
    const r = await started.http.handle(request("admin", [], { principal: { subject: "u1", emailVerified: true, roles: [] } }))
    assert.equal(new TextDecoder().decode(r.message.case === "response" ? r.message.value.body : undefined), "u1")
  })

  test("an SSE route streams its frames intact, then completes", async () => {
    const frames: string[] = []
    let completed = false
    for await (const f of started.http.handleStream(request("events", ["e1"]))) {
      if (f.frame.case === "text") frames.push(f.frame.value)
      if (f.frame.case === "completed") completed = true
    }
    assert.deepEqual(frames, ["start e1", " leading space", "two\nlines"])
    assert.ok(completed)
  })

  test("this.request is refused outside a handler", () => {
    assert.throws(() => new Things().request, /only available inside an endpoint handler/)
  })
})
