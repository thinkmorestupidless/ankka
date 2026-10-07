// A socket route through the Http servicer, over the in-process Connect transport: what the sidecar's
// conversation sees on `Http.HandleSocket`.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { ConnectError, Code } from "@connectrpc/connect"
import { Ankka } from "../src/service.ts"
import { PROTOCOL_VERSION } from "../src/spec.ts"
import { noClient } from "../src/client.ts"
import { Endpoint } from "../src/endpoint.ts"
import { Acl, get, socket } from "../src/routes.ts"
import { s } from "../src/schema.ts"
import { SocketInSchema, type SocketIn, type SocketOut } from "../src/_proto/ankka/protocol/v1/endpoint_pb.ts"
import { AsyncQueue } from "../src/server/queue.ts"
import { EndpointTestKit } from "../src/testkit/unit.ts"
import { startServer, type Started } from "./helpers.ts"

class Rooms extends Endpoint {
  static readonly prefix = "/rooms"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    chat: socket("/{room}", async (_ep: Rooms, req, socket) => {
      for await (const text of socket) {
        await socket.send(text === "context" ? `${req.params.room} ${req.query.get("tag") ?? ""}` : text)
      }
    }),
    fail: socket("/fail", async (_ep: Rooms, _req, socket) => {
      await socket.receive()
      throw new Error("the socket handler broke")
    }),
  }
}

class Plain extends Endpoint {
  static readonly prefix = "/plain"
  static readonly acl = Acl.allowAll
  static readonly routes = { hello: get("/", s.string, () => "hello") }
}

const open = (route: string, pathArgs: string[] = [], query: { name: string; value: string }[] = []): SocketIn =>
  create(SocketInSchema, { message: { case: "open", value: { endpointId: "Rooms", routeId: route, pathArgs, query } } })
const frame = (text: string): SocketIn => create(SocketInSchema, { message: { case: "frame", value: { kind: { case: "text", value: text } } } })

describe("HandleSocket", () => {
  let started: Started
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Rooms))
  })
  after(async () => started.stop())

  test("discovery marks a socket route, and is refused by a runtime older than sockets", async () => {
    const spec = await started.discovery.discover({ protocolVersion: "1.9" })
    const chat = spec.endpoints.find((e) => e.id === "Rooms")!.routes.find((r) => r.id === "chat")!
    assert.equal(chat.socket, true)
    assert.equal(chat.method, "GET")
    // What the SDK declares, which is its own version, whatever the runtime asked with.
    assert.equal(spec.protocolVersion, PROTOCOL_VERSION)
    await assert.rejects(started.discovery.discover({ protocolVersion: "1.8" }), (e: unknown) =>
      e instanceof ConnectError && e.code === Code.FailedPrecondition && e.message.includes("1.9"),
    )
  })

  test("frames cross both ways, and the opening request is read after them", async () => {
    const requests = new AsyncQueue<SocketIn>()
    const replies = started.http.handleSocket(requests)[Symbol.asyncIterator]()
    requests.push(open("chat", ["lobby"], [{ name: "tag", value: "a" }]))
    for (const text of ["x", "y", "context"]) requests.push(frame(text))
    const texts: string[] = []
    for (let i = 0; i < 3; i++) {
      const out = (await replies.next()).value as SocketOut
      if (out.message.case === "frame" && out.message.value.kind.case === "text") texts.push(out.message.value.kind.value)
    }
    assert.deepEqual(texts, ["x", "y", "lobby a"])
    requests.push(create(SocketInSchema, { message: { case: "closed", value: { reason: "client" } } }))
    requests.close()
    const last = (await replies.next()).value as SocketOut
    assert.equal(last.message.case, "completed")
  })

  test("a handler that throws is failed", async () => {
    const requests = new AsyncQueue<SocketIn>()
    const replies = started.http.handleSocket(requests)[Symbol.asyncIterator]()
    requests.push(open("fail"))
    requests.push(frame("go"))
    const out = (await replies.next()).value as SocketOut
    assert.equal(out.message.case, "failed")
    assert.match(out.message.case === "failed" ? out.message.value.message : "", /broke/)
    requests.close()
  })

  test("Handle on a socket route names the version", async () => {
    const reply = await started.http.handle({ endpointId: "Rooms", routeId: "chat" })
    assert.equal(reply.message.case, "failure")
    assert.match(reply.message.case === "failure" ? reply.message.value.error?.message ?? "" : "", /1\.9/)
  })
})

test("a service without a socket route is discovered by an older runtime", async () => {
  const started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Plain))
  try {
    const spec = await started.discovery.discover({ protocolVersion: "1.8" })
    assert.deepEqual(spec.endpoints.map((e) => e.id), ["Plain"])
  } finally {
    await started.stop()
  }
})

test("the unit kit runs a socket route", async () => {
  const kit = EndpointTestKit.of(Rooms)
  assert.deepEqual(await kit.socket("/rooms/lobby?tag=b", ["x", "context"]), { sent: ["x", "lobby b"], ended: "finished" })
  const failed = await kit.socket("/rooms/fail", ["go"])
  assert.equal(failed.ended, "failed")
  assert.match(failed.error ?? "", /broke/)
})
