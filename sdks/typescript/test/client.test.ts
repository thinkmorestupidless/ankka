// The component client against a fake sidecar: a Connect server implementing `Client` that records what
// it was asked and answers from a script.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createServer, type Http2Server } from "node:http2"
import type { AddressInfo } from "node:net"
import { create } from "@bufbuild/protobuf"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import { Client, InvokeReplySchema, QueryReplySchema, StreamTokenSchema, type InvokeRequest, type ScheduleRequest } from "../src/_proto/ankka/protocol/v1/client_pb.ts"
import { EmptySchema, ErrorCode as ProtoErrorCode, PayloadSchema } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { Kind } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { ComponentClient } from "../src/client.ts"
import { CommandError } from "../src/effects/common.ts"
import { Done, s } from "../src/schema.ts"
import { Duration } from "../src/time.ts"
import { Counter } from "./fixtures/counter.ts"

const utf8 = new TextEncoder()
const text = (b: Uint8Array | undefined) => new TextDecoder().decode(b ?? new Uint8Array())

describe("the component client", () => {
  let server: Http2Server
  let client: ComponentClient
  const invokes: InvokeRequest[] = []
  const schedules: ScheduleRequest[] = []
  const cancels: string[] = []

  before(async () => {
    server = createServer(
      connectNodeAdapter({
        routes: (router) =>
          router.service(Client, {
            async invoke(req) {
              invokes.push(req)
              if (req.name === "refuse") return create(InvokeReplySchema, { result: { case: "error", value: { message: "no", code: ProtoErrorCode.CONFLICT } } })
              if (req.name === "get") return create(InvokeReplySchema, { result: { case: "reply", value: { payload: { contentType: "text/plain", manifest: "int", data: utf8.encode("42") } } } })
              if (req.name === "done") return create(InvokeReplySchema, { result: { case: "reply", value: { payload: { contentType: "application/octet-stream", manifest: "done", data: new Uint8Array() } } } })
              return create(InvokeReplySchema, { result: { case: "reply", value: { payload: create(PayloadSchema, { contentType: "text/plain", manifest: "int", data: utf8.encode(String(text(req.payload?.data).length)) }) } } })
            },
            async *invokeStream(req) {
              invokes.push(req)
              yield create(StreamTokenSchema, { token: { case: "text", value: "hel" } })
              yield create(StreamTokenSchema, { token: { case: "text", value: "lo" } })
              if (req.name === "stream-fail") yield create(StreamTokenSchema, { token: { case: "failed", value: { message: "cut", code: ProtoErrorCode.UNAVAILABLE } } })
              else yield create(StreamTokenSchema, { token: { case: "completed", value: {} } })
            },
            async query(req) {
              if (req.name === "boom") return create(QueryReplySchema, { result: { case: "error", value: { message: "no such view", code: ProtoErrorCode.NOT_FOUND } } })
              const key = text(req.payload?.data)
              const rows = req.name === "all" ? '[{"id":"a","big":9007199254740993},{"id":"b","big":1}]' : key === "missing" ? "[]" : `[{"id":${JSON.stringify(key)},"big":2}]`
              return create(QueryReplySchema, { result: { case: "rows", value: { contentType: "application/json", manifest: "rows", data: utf8.encode(rows) } } })
            },
            async schedule(req) {
              schedules.push(req)
              return create(EmptySchema)
            },
            async cancel(req) {
              cancels.push(req.timerId)
              return create(EmptySchema)
            },
          }),
      }),
    )
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()))
    client = new ComponentClient(`127.0.0.1:${(server.address() as AddressInfo).port}`)
  })
  after(() => new Promise<void>((r) => server.close(() => r())))

  test("invoke encodes the input with its shape and decodes the reply with its shape", async () => {
    const n = await client.forEventSourcedEntity("counter", "c1").call("increment", s.int, s.int).invoke(5)
    assert.equal(n, 1) // the fake answers the input's text length: "5"
    const last = invokes.at(-1)!
    assert.equal(last.kind, Kind.EVENT_SOURCED_ENTITY)
    assert.equal(last.componentId, "counter")
    assert.equal(last.entityId, "c1")
    assert.equal(last.name, "increment")
    assert.equal(last.payload?.manifest, "int")
    assert.equal(text(last.payload?.data), "5")
  })

  test("a refusal rejects with CommandError carrying its code", async () => {
    await assert.rejects(client.forKeyValueEntity("profile", "p1").call("refuse").invoke(), (e: unknown) => e instanceof CommandError && e.code === "CONFLICT" && e.message === "no")
  })

  test("no reply shape: a primitive decodes by its manifest, and done is done", async () => {
    assert.equal(await client.forWorkflow("wf", "w1").call("get").invoke(), 42)
    const d = await client.forAgent("assistant", "s1").call("done").invoke()
    assert.deepEqual(d, { done: true })
  })

  test("the typed form takes the wire name and shapes from the handler declaration", async () => {
    const n = await client.of(Counter, "c9").call(Counter.handlers.increment).invoke(123)
    assert.equal(n, 3)
    const last = invokes.at(-1)!
    assert.equal(last.kind, Kind.EVENT_SOURCED_ENTITY)
    assert.equal(last.componentId, "counter")
    assert.equal(last.name, "increment")
    assert.equal(last.entityId, "c9")
  })

  test("metadata on a scoped client travels with every call", async () => {
    await client.withMetadata({ traceparent: "00-abc" }).forEventSourcedEntity("counter", "c1").call("get").invoke()
    assert.deepEqual(invokes.at(-1)!.metadata?.entries.map((e) => [e.key, e.value]), [["traceparent", "00-abc"]])
  })

  test("stream yields tokens in order and fails as a CommandError", async () => {
    const tokens: string[] = []
    for await (const t of client.forAgent("assistant", "s1").call("stream", s.string).stream("hi")) tokens.push(t)
    assert.deepEqual(tokens, ["hel", "lo"])
    await assert.rejects(async () => {
      for await (const _ of client.forAgent("assistant", "s1").call("stream-fail", s.string).stream("hi")) void _
    }, /cut/)
  })

  test("views decode rows with the row shape, longs intact", async () => {
    const Row = s.record("Row", { id: s.string, big: s.long })
    const rows = await client.views.all("cart-rows", Row)
    assert.deepEqual(rows, [
      { id: "a", big: 9007199254740993n },
      { id: "b", big: 1n },
    ])
    assert.deepEqual(await client.views.get("cart-rows", "k1", Row), { id: "k1", big: 2n })
    assert.equal(await client.views.get("cart-rows", "missing", Row), null)
    await assert.rejects(client.views.query("nope", "boom", null, Row), (e: unknown) => e instanceof CommandError && e.code === "NOT_FOUND")
  })

  test("timers schedule by class and handler, or by name, and cancel", async () => {
    await client.timers.schedule("t1", Duration.ofSeconds(2), { component: Counter, handler: Counter.handlers.increment, entityId: "c1" }, 3)
    let last = schedules.at(-1)!
    assert.deepEqual({ id: last.timerId, delay: last.delayMillis, kind: last.kind, component: last.componentId, entity: last.entityId, name: last.name, payload: text(last.payload?.data) }, { id: "t1", delay: 2000n, kind: Kind.EVENT_SOURCED_ENTITY, component: "counter", entity: "c1", name: "increment", payload: "3" })
    await client.timers.schedule("t2", Duration.ofMillis(1500), { kind: "timed-action", componentId: "reminder", name: "remind", input: s.string }, "c1")
    last = schedules.at(-1)!
    assert.deepEqual({ kind: last.kind, name: last.name, payload: text(last.payload?.data), entity: last.entityId }, { kind: Kind.TIMED_ACTION, name: "remind", payload: "c1", entity: undefined })
    await client.timers.cancel("t1")
    assert.deepEqual(cancels, ["t1"])
  })

  test("reconnect repoints every client sharing the connection", async () => {
    const scoped = client.withMetadata({ a: "b" })
    const before = client.address
    client.reconnect("127.0.0.1:1")
    assert.equal(scoped.address, "127.0.0.1:1")
    client.reconnect(before)
    assert.equal(await client.forWorkflow("wf", "w1").call("get").invoke(), 42)
  })

  test("Done as a declared reply shape decodes to done", async () => {
    assert.deepEqual(await client.forWorkflow("wf", "w1").call("done", undefined, Done).invoke(), { done: true })
  })
})
