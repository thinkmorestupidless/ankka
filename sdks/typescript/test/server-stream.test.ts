// The servicers through an in-process Connect client: discovery, and the event sourced conversation
// (init, replay, commands in order, a snapshot on request, a failure that leaves the state alone).
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { Ankka } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { s } from "../src/schema.ts"
import { Kind } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { ErrorCode as ProtoErrorCode } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { Counter, CounterState } from "./fixtures/counter.ts"
import { Conversation, decode, failureOf, payload, replyOf, startServer, type Started } from "./helpers.ts"
import { VERSION } from "../src/version.ts"

describe("the event sourced servicer", () => {
  let started: Started
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Counter))
  })
  after(() => started.stop())

  test("discovery lists the component, its handlers and the SDK", async () => {
    const spec = await started.discovery.discover({ protocolVersion: "1.0", runtimeVersion: "test" })
    assert.equal(spec.protocolVersion, "1.0")
    assert.deepEqual({ name: spec.sdk?.name, version: spec.sdk?.version }, { name: "ankka-typescript", version: VERSION })
    assert.equal(spec.components.length, 1)
    const c = spec.components[0]!
    assert.equal(c.kind, Kind.EVENT_SOURCED_ENTITY)
    assert.equal(c.id, "counter")
    assert.equal(c.detail.case, "eventSourced")
    assert.equal(c.detail.case === "eventSourced" && c.detail.value.snapshotEvery, 3)
    const names = c.handlers.map((h) => h.name)
    assert.deepEqual(names, [...names].sort(), "handlers are sorted by name")
    assert.deepEqual(
      c.handlers.filter((h) => h.readOnly).map((h) => h.name),
      ["get", "get-state", "who-am-i"],
    )
    assert.ok(c.handlers.every((h) => !h.streaming))
  })

  test("init from empty, then commands in order, each replied from the post-event state", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c1")
    const r1 = replyOf(await conv.call("increment", payload(s.int, 2)))
    assert.equal(r1.commandId, 1n)
    assert.equal(r1.events.length, 1)
    assert.equal(r1.events[0]!.manifest, "counter-event")
    assert.equal(new TextDecoder().decode(r1.events[0]!.data), '{"type":"Incremented","by":2}')
    assert.equal(r1.outcome?.outcome.case, "reply")
    assert.equal(decode(s.int, r1.outcome?.outcome.case === "reply" ? r1.outcome.outcome.value.payload : undefined), 2)
    assert.equal(r1.snapshot, undefined)

    const r2 = replyOf(await conv.call("increment", payload(s.int, 3)))
    assert.equal(decode(s.int, r2.outcome?.outcome.case === "reply" ? r2.outcome.outcome.value.payload : undefined), 5)

    const r3 = replyOf(await conv.call("get"))
    assert.equal(r3.events.length, 0)
    assert.equal(decode(s.int, r3.outcome?.outcome.case === "reply" ? r3.outcome.outcome.value.payload : undefined), 5)
    conv.close()
    assert.ok(await conv.ended())
  })

  test("init from a snapshot and replay of the events after it", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c2", { sequence: 3n, payload: payload(CounterState, { value: 10, history: [10] }) })
    conv.event(4n, payload(Counter.events, { type: "Incremented", by: 5 }))
    const r = replyOf(await conv.call("who-am-i"))
    assert.equal(decode(s.string, r.outcome?.outcome.case === "reply" ? r.outcome.outcome.value.payload : undefined), "counter/c2@4")
    const g = replyOf(await conv.call("get"))
    assert.equal(decode(s.int, g.outcome?.outcome.case === "reply" ? g.outcome.outcome.value.payload : undefined), 15)
    conv.close()
  })

  test("a snapshot is included exactly when requested, describing the state after the reply's events", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c3")
    const r = replyOf(await conv.call("increment", payload(s.int, 7), true))
    assert.ok(r.snapshot)
    assert.deepEqual(decode(CounterState, r.snapshot), { value: 7, history: [7] })
    const r2 = replyOf(await conv.call("increment", payload(s.int, 1)))
    assert.equal(r2.snapshot, undefined)
    conv.close()
  })

  test("a refusal persists nothing and leaves the state unchanged", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c4")
    replyOf(await conv.call("increment", payload(s.int, 1)))
    const r = replyOf(await conv.call("refuse"))
    assert.equal(r.events.length, 0)
    assert.equal(r.outcome?.outcome.case, "error")
    assert.equal(r.outcome?.outcome.case === "error" && r.outcome.outcome.value.code, ProtoErrorCode.CONFLICT)
    const bad = replyOf(await conv.call("increment", payload(s.int, -1)))
    assert.equal(bad.outcome?.outcome.case, "error")
    const g = replyOf(await conv.call("get"))
    assert.equal(decode(s.int, g.outcome?.outcome.case === "reply" ? g.outcome.outcome.value.payload : undefined), 1)
    conv.close()
  })

  test("a thrown handler is a failure with the command's id, and the instance keeps serving", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c5")
    const id = conv.send("boom")
    const f = failureOf(await conv.reply())
    assert.equal(f.commandId, id)
    assert.match(f.error?.message ?? "", /kaboom/)
    const g = replyOf(await conv.call("get"))
    assert.equal(decode(s.int, g.outcome?.outcome.case === "reply" ? g.outcome.outcome.value.payload : undefined), 0)
    conv.close()
  })

  test("an unknown handler is NOT_FOUND; an undecodable input is BAD_REQUEST", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c6")
    const f = failureOf(await conv.call("nope"))
    assert.equal(f.error?.code, ProtoErrorCode.NOT_FOUND)
    const g = failureOf(await conv.call("increment", payload(s.string, "not a number")))
    assert.equal(g.error?.code, ProtoErrorCode.BAD_REQUEST)
    conv.close()
  })

  test("no reply, reply-with-state, delete and expire cross as the protocol says", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c7")
    const n = replyOf(await conv.call("no-reply"))
    assert.equal(n.events.length, 1)
    assert.equal(n.outcome?.outcome.case, "noReply")
    const r = replyOf(await conv.call("reset"))
    assert.deepEqual(decode(CounterState, r.outcome?.outcome.case === "reply" ? r.outcome.outcome.value.payload : undefined), { value: 0, history: [] })
    const d = replyOf(await conv.call("remove"))
    assert.equal(d.retention?.retention.case, "deleteNow")
    assert.equal(d.events.length, 0)
    const e = replyOf(await conv.call("expire"))
    assert.equal(e.retention?.retention.case, "expireAfter")
    assert.equal(e.retention?.retention.case === "expireAfter" && e.retention.retention.value.millis, 1000n)
    conv.close()
  })

  test("async handlers are awaited, strictly in order", async () => {
    const conv = new Conversation(started)
    conv.init("counter", "c8")
    for (let i = 1; i <= 5; i++) conv.send("increment-async", payload(s.int, i))
    for (let i = 1; i <= 5; i++) {
      const r = replyOf(await conv.reply())
      assert.equal(r.commandId, BigInt(i))
    }
    const g = replyOf(await conv.call("get"))
    assert.equal(decode(s.int, g.outcome?.outcome.case === "reply" ? g.outcome.outcome.value.payload : undefined), 15)
    conv.close()
  })

  test("an unknown component is refused at init", async () => {
    const conv = new Conversation(started)
    conv.init("nope", "x")
    const f = failureOf(await conv.reply())
    assert.equal(f.error?.code, ProtoErrorCode.NOT_FOUND)
    assert.ok(await conv.ended())
  })

  test("the process's ReportError log is kept", async () => {
    const { problems } = await import("../src/server/discovery.ts")
    await started.discovery.reportError({ message: "test problem" })
    assert.ok(problems.includes("test problem"))
  })
})

describe("binding", () => {
  test("loopback and 0.0.0.0 are accepted; another interface is refused", async () => {
    const any = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Counter), "0.0.0.0")
    assert.ok(any.port > 0)
    await any.stop()
    await assert.rejects(() => Ankka.service({ client: noClient() }).server({ host: "192.168.1.10", port: 0 }).start(), /loopback only/)
  })

  test("stop ends an open stream and resolves closed", async () => {
    const started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Counter))
    const conv = new Conversation(started)
    conv.init("counter", "c9")
    replyOf(await conv.call("increment", payload(s.int, 1)))
    await started.stop()
    await started.server.closed
    await assert.rejects(conv.reply())
  })
})
