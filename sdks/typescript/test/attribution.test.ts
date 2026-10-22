// Who acted, and where a command was forwarded from (protocol 1.22): read off the command into the
// handler's context, and off each event into the event handler's, replayed or just persisted.

import { test } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { command, query } from "../src/handlers.ts"
import { codecFor, jsonCodec } from "../src/codec.ts"
import { s } from "../src/schema.ts"
import type { EventContext } from "../src/context.ts"
import { Ankka } from "../src/service.ts"
import { PROTOCOL_VERSION } from "../src/spec.ts"
import { handleEventSourced } from "../src/server/eventSourced.ts"
import { encodePayload, decodePayload } from "../src/server/payloads.ts"
import { noClient } from "../src/client.ts"
import { EventSourcedInSchema, type EventSourcedIn, type EventSourcedOut } from "../src/_proto/ankka/protocol/v1/event_sourced_pb.ts"

const Inspections = s.record("Inspections", { actedBy: s.list(s.string), forwardedFrom: s.list(s.string) })
type Inspections = typeof Inspections extends { _type?: infer T } ? T : never
const Inspected = s.record("Inspected", { note: s.string })
type Inspected = typeof Inspected extends { _type?: infer T } ? T : never
const Seen = s.record("Seen", { attribution: s.string, forwardedFrom: s.string })

class Vehicle extends EventSourcedEntity<Inspections, Inspected> {
  static readonly componentId = "vehicle"
  static readonly state = jsonCodec(Inspections, "inspections")
  static readonly events = jsonCodec(Inspected, "inspected")
  static readonly handlers = {
    inspect: command("inspect", s.string, Seen, (v: Vehicle, note) => {
      const seen = { attribution: v.context.attribution ?? "-", forwardedFrom: v.context.forwardedFrom ?? "-" }
      return v.effects.persist({ note }).thenReply(() => seen)
    }),
    get: query("get", Inspections, (v: Vehicle) => v.effects.reply(v.state)),
  }

  emptyState(): Inspections {
    return { actedBy: [], forwardedFrom: [] }
  }

  applyEvent(state: Inspections, _event: Inspected, context?: EventContext): Inspections {
    return {
      actedBy: [...state.actedBy, context?.attribution ?? "-"],
      forwardedFrom: [...state.forwardedFrom, context?.forwardedFrom ?? "-"],
    }
  }
}

async function stream(messages: EventSourcedIn[]): Promise<EventSourcedOut[]> {
  const registry = Ankka.service({ client: noClient(), log: () => {} }).register(Vehicle).validate()
  async function* feed() {
    for (const m of messages) yield m
  }
  const out: EventSourcedOut[] = []
  for await (const m of handleEventSourced(feed(), { registry, client: noClient(), log: () => {} })) out.push(m)
  return out
}

function replied(out: EventSourcedOut, codec: ReturnType<typeof codecFor>): unknown {
  const m = out.message
  assert.equal(m.case, "reply")
  return decodePayload(codec, m.case === "reply" && m.value.outcome?.outcome.case === "reply" ? m.value.outcome.outcome.value.payload : undefined)
}

const init = create(EventSourcedInSchema, { message: { case: "init", value: { componentId: "vehicle", entityId: "v1" } } })
const get = create(EventSourcedInSchema, { message: { case: "command", value: { id: 2n, name: "get" } } })

test("the protocol version is 1.22", () => {
  assert.equal(PROTOCOL_VERSION, "1.22")
})

test("a command tells its handler and its events who acted and where it was forwarded from", async () => {
  const out = await stream([
    init,
    create(EventSourcedInSchema, {
      message: { case: "command", value: { id: 1n, name: "inspect", payload: encodePayload(codecFor(s.string), "brakes"), attribution: "ada", forwardedFrom: "bratislava" } },
    }),
    get,
  ])
  assert.deepEqual(replied(out[0]!, codecFor(Seen)), { attribution: "ada", forwardedFrom: "bratislava" })
  assert.deepEqual(replied(out[1]!, codecFor(Inspections)), { actedBy: ["ada"], forwardedFrom: ["bratislava"] })
})

test("a replayed event tells the event handler what its record says", async () => {
  const events = codecFor(Vehicle.events)
  const out = await stream([
    init,
    create(EventSourcedInSchema, { message: { case: "event", value: { sequence: 1n, payload: encodePayload(events, { note: "brakes" }), attribution: "ada", forwardedFrom: "bratislava" } } }),
    create(EventSourcedInSchema, { message: { case: "event", value: { sequence: 2n, payload: encodePayload(events, { note: "tyres" }) } } }),
    get,
  ])
  assert.deepEqual(replied(out[0]!, codecFor(Inspections)), { actedBy: ["ada", "-"], forwardedFrom: ["bratislava", "-"] })
})

test("a command nobody attributed has neither", async () => {
  const out = await stream([
    init,
    create(EventSourcedInSchema, { message: { case: "command", value: { id: 1n, name: "inspect", payload: encodePayload(codecFor(s.string), "brakes") } } }),
  ])
  assert.deepEqual(replied(out[0]!, codecFor(Seen)), { attribution: "-", forwardedFrom: "-" })
})
