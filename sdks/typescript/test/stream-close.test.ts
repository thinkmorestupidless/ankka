// A stream that ends cleanly (passivation) and one the sidecar aborts (it went away) cannot be told
// apart from this side, and both must release the instance's state: the next Init for the same id starts
// from what the sidecar sends, never from what we remembered.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { Ankka } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { s } from "../src/schema.ts"
import { EventSourcedInSchema, type EventSourcedIn } from "../src/_proto/ankka/protocol/v1/event_sourced_pb.ts"
import { AsyncQueue } from "../src/server/queue.ts"
import { Counter } from "./fixtures/counter.ts"
import { Conversation, decode, payload, replyOf, startServer, type Started } from "./helpers.ts"

describe("stream close", () => {
  let started: Started
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Counter))
  })
  after(() => started.stop())

  test("a clean close releases the state: the next Init starts over", async () => {
    const first = new Conversation(started)
    first.init("counter", "same")
    replyOf(await first.call("increment", payload(s.int, 5)))
    first.close()
    assert.ok(await first.ended())

    const second = new Conversation(started)
    second.init("counter", "same")
    const g = replyOf(await second.call("get"))
    assert.equal(decode(s.int, g.outcome?.outcome.case === "reply" ? g.outcome.outcome.value.payload : undefined), 0)
    second.close()
  })

  test("an aborted stream releases the state too", async () => {
    const requests = new AsyncQueue<EventSourcedIn>()
    const controller = new AbortController()
    const replies = started.eventSourced.handle(requests, { signal: controller.signal })[Symbol.asyncIterator]()
    requests.push(create(EventSourcedInSchema, { message: { case: "init", value: { componentId: "counter", entityId: "aborted" } } }))
    requests.push(
      create(EventSourcedInSchema, {
        message: { case: "command", value: { id: 1n, name: "increment", payload: payload(s.int, 9), metadata: { entries: [] }, snapshotRequested: false } },
      }),
    )
    const r = await replies.next()
    assert.ok(!r.done && r.value.message.case === "reply")
    controller.abort()
    await assert.rejects(replies.next())

    const again = new Conversation(started)
    again.init("counter", "aborted")
    const g = replyOf(await again.call("get"))
    assert.equal(decode(s.int, g.outcome?.outcome.case === "reply" ? g.outcome.outcome.value.payload : undefined), 0)
    again.close()
  })
})
