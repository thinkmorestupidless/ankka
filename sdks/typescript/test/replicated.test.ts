// A replicated entity in TypeScript (protocol 1.19): the declaration, the effects, the contexts over the
// protocol, a follower in the unit kit, and the refusal of a runtime from before replication.
import { test } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { command, query } from "../src/handlers.ts"
import { codecFor, jsonCodec } from "../src/codec.ts"
import { s } from "../src/schema.ts"
import { ErrorCode } from "../src/effects/common.ts"
import type { EventContext } from "../src/context.ts"
import { Ankka } from "../src/service.ts"
import { renderSpec } from "../src/spec.ts"
import { refusal } from "../src/server/discovery.ts"
import { handleEventSourced } from "../src/server/eventSourced.ts"
import { encodePayload, decodePayload } from "../src/server/payloads.ts"
import { noClient } from "../src/client.ts"
import { EventSourcedTestKit } from "../src/testkit/unit.ts"
import { EventSourcedInSchema, type EventSourcedIn, type EventSourcedOut } from "../src/_proto/ankka/protocol/v1/event_sourced_pb.ts"

const Vehicle = s.record("Vehicle", { steps: s.list(s.string), writtenAt: s.list(s.string), local: s.list(s.boolean) })
type Vehicle = typeof Vehicle extends { _type?: infer T } ? T : never

const StepAdded = s.record("StepAdded", { step: s.string })
type StepAdded = typeof StepAdded extends { _type?: infer T } ? T : never

const Placement = s.record("Placement", { installation: s.string, home: s.boolean })

class VehicleEntity extends EventSourcedEntity<Vehicle, StepAdded> {
  static readonly componentId = "vehicle"
  static readonly replicated = true
  static readonly state = jsonCodec(Vehicle, "vehicle")
  static readonly events = jsonCodec(StepAdded, "step-added")
  static readonly handlers = {
    addStep: command("add-step", s.string, Placement, (v: VehicleEntity, step) => {
      // Taken while the handler runs: the reply is computed once it has returned.
      const placement = { installation: v.context.installation ?? "", home: v.context.isHome }
      return v.effects.persist({ step }).thenReply(() => placement)
    }),
    handOver: command("hand-over", s.string, s.string, (v: VehicleEntity, target) => v.effects.handOverTo(target).thenReply(() => target)),
    allot: command("allot", s.string, s.string, (v: VehicleEntity, target) =>
      v.effects.persist({ step: "allotted" }).replicateTo(target).stopReplicatingTo("factory").thenReply(() => target)),
    get: query("get", Vehicle, (v: VehicleEntity) => v.effects.reply(v.state)),
  }

  emptyState(): Vehicle {
    return { steps: [], writtenAt: [], local: [] }
  }

  applyEvent(state: Vehicle, event: StepAdded, context?: EventContext): Vehicle {
    return {
      steps: [...state.steps, event.step],
      writtenAt: [...state.writtenAt, context?.installation ?? ""],
      local: [...state.local, context?.isLocal ?? true],
    }
  }
}

function specOf(): ReturnType<ReturnType<typeof Ankka.service>["validate"]> {
  return Ankka.service({ client: noClient(), log: () => {} }).register(VehicleEntity).validate()
}

test("an entity declared replicated says so in discovery", () => {
  const spec = renderSpec(specOf())
  const vehicle = spec.components.find((c) => c.id === "vehicle")!
  assert.equal(vehicle.detail.case, "eventSourced")
  assert.equal(vehicle.detail.case === "eventSourced" && vehicle.detail.value.replicated, true)
})

test("a runtime from before replication is refused naming the protocol version", () => {
  const spec = renderSpec(specOf())
  const refused = refusal(spec, "1.18")
  assert.ok(refused && refused.includes("1.19") && refused.includes("vehicle"), refused)
  assert.equal(refusal(spec, "1.19"), undefined)
})

test("the effects are carried beside the events", async () => {
  const kit = EventSourcedTestKit.of(VehicleEntity, "v1", noClient(), { kind: "home", installation: "leipzig" })
  await kit.call("hand-over", "vienna")
  assert.deepEqual(kit.lastReplication, { kind: "hand-over", to: "vienna" })
  const allotted = await kit.call("allot", "vienna")
  assert.deepEqual(allotted.events, [{ step: "allotted" }])
  assert.deepEqual(kit.lastReplication, { kind: "set", add: ["vienna"], remove: ["factory"] })
})

test("two changes on one command are refused where they are built", () => {
  const effects = new VehicleEntity().effects
  assert.throws(() => effects.handOverTo("a").replicateTo("b"))
  assert.throws(() => effects.replicateTo("b").stopReplicatingTo("b"))
})

test("at the home a command is told it is at the home, and its events are local", async () => {
  const kit = EventSourcedTestKit.of(VehicleEntity, "v1", noClient(), { kind: "home", installation: "leipzig" })
  assert.deepEqual((await kit.call("add-step", "frame")).reply, { installation: "leipzig", home: true })
  assert.deepEqual(kit.state.writtenAt, ["leipzig"])
  assert.deepEqual(kit.state.local, [true])
})

test("a follower refuses a persisting command naming the home and answers a query", async () => {
  const kit = EventSourcedTestKit.of(VehicleEntity, "v1", noClient(), { kind: "follower", home: "leipzig", installation: "bratislava" })
  kit.replay([{ step: "frame" }], "leipzig")
  const refused = await kit.call("add-step", "wheels")
  assert.equal(refused.error?.code, ErrorCode.NotHome)
  assert.ok(refused.error?.message.includes("leipzig"))
  assert.deepEqual((await kit.call("get")).reply, { steps: ["frame"], writtenAt: ["leipzig"], local: [false] })
})

test("an entity not replicated in the kit refuses a replication change", async () => {
  const kit = EventSourcedTestKit.of(VehicleEntity, "v1")
  const refused = await kit.call("hand-over", "vienna")
  assert.equal(refused.error?.code, ErrorCode.BadRequest)
})

async function stream(messages: EventSourcedIn[]): Promise<EventSourcedOut[]> {
  const registry = specOf()
  async function* feed() {
    for (const m of messages) yield m
  }
  const out: EventSourcedOut[] = []
  for await (const m of handleEventSourced(feed(), { registry, client: noClient(), log: () => {} })) out.push(m)
  return out
}

const events = codecFor(VehicleEntity.events)
const vehicles = codecFor(VehicleEntity.state)

test("over the protocol the event handler is told where each event was written", async () => {
  const out = await stream([
    create(EventSourcedInSchema, { message: { case: "init", value: { componentId: "vehicle", entityId: "v1", installation: "bratislava" } } }),
    create(EventSourcedInSchema, { message: { case: "event", value: { sequence: 1n, payload: encodePayload(events, { step: "frame" }), installation: "leipzig" } } }),
    create(EventSourcedInSchema, { message: { case: "command", value: { id: 1n, name: "get", installation: "bratislava", atHome: false } } }),
  ])
  const reply = out[0]!.message
  assert.equal(reply.case, "reply")
  const state = decodePayload(vehicles, reply.case === "reply" && reply.value.outcome?.outcome.case === "reply" ? reply.value.outcome.outcome.value.payload : undefined) as Vehicle
  assert.deepEqual(state.writtenAt, ["leipzig"])
  assert.deepEqual(state.local, [false])
})

test("over the protocol a handover is sent on the reply", async () => {
  const out = await stream([
    create(EventSourcedInSchema, { message: { case: "init", value: { componentId: "vehicle", entityId: "v1", installation: "leipzig" } } }),
    create(EventSourcedInSchema, {
      message: { case: "command", value: { id: 1n, name: "hand-over", payload: encodePayload(codecFor(s.string), "vienna"), installation: "leipzig", atHome: true } },
    }),
  ])
  const reply = out[0]!.message
  assert.equal(reply.case === "reply" && reply.value.replication?.change.case, "handOverTo")
  assert.equal(reply.case === "reply" && reply.value.replication?.change.value, "vienna")
})
