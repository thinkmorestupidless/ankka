// What a view or consumer that reads a topic declares about it: where it starts, and its version.
import { test } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { Ankka, RegistrationError } from "../src/service.ts"
import { Consumer } from "../src/consumer.ts"
import { View } from "../src/view.ts"
import { jsonCodec } from "../src/codec.ts"
import { s, type Infer } from "../src/schema.ts"
import { StartFrom } from "../src/startFrom.ts"
import { refusal } from "../src/server/discovery.ts"
import { SpecSchema, StartFrom_Named } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { Counter } from "./fixtures/counter.ts"

const Message = s.record("Message", { n: s.int })
type Message = Infer<typeof Message>
const codec = jsonCodec(Message, "message")

function consumer(declared: { startFrom?: unknown; version?: unknown; topic?: string; source?: unknown }) {
  return class Notifier extends Consumer<Message> {
    static readonly componentId = "notifier"
    static readonly topic = "topic" in declared ? declared.topic : "orders"
    static readonly source = declared.source as never
    static readonly startFrom = declared.startFrom as never
    static readonly version = declared.version as never
    static readonly message = codec
    onMessage() {
      return this.effects.done()
    }
  }
}

function view(declared: { startFrom?: unknown; version?: unknown; topic?: string; source?: unknown }) {
  return class Summary extends View<Message, Message> {
    static readonly componentId = "summary"
    static readonly topic = "topic" in declared ? declared.topic : "orders"
    static readonly source = declared.source as never
    static readonly startFrom = declared.startFrom as never
    static readonly version = declared.version as never
    static readonly events = codec
    static readonly row = codec
    onChange(message: Message) {
      return this.effects.updateRow(message)
    }
  }
}

function specOf(cls: unknown) {
  return Ankka.service().register(cls as never).spec()
}

function problemsOf(cls: unknown): readonly string[] {
  try {
    specOf(cls)
  } catch (e) {
    assert.ok(e instanceof RegistrationError, String(e))
    return e.problems
  }
  return []
}

test("each start position is written into discovery", () => {
  const when = new Date("2026-10-01T12:00:00Z")
  const start = (s: unknown) => {
    const d = specOf(consumer({ startFrom: s })).components[0].detail
    assert.equal(d.case, "consumer")
    return d.value.source?.startFrom?.position
  }
  assert.deepEqual(start(StartFrom.earliest), { case: "named", value: StartFrom_Named.EARLIEST })
  assert.deepEqual(start(StartFrom.latest), { case: "named", value: StartFrom_Named.LATEST })
  assert.deepEqual(start(StartFrom.at(when)), { case: "atMillis", value: BigInt(when.getTime()) })
})

test("a view declaring nothing writes no start position and no version; one declaring a version writes it", () => {
  const plain = specOf(view({})).components[0].detail
  assert.equal(plain.case, "view")
  assert.equal(plain.value.source?.startFrom, undefined)
  assert.equal(plain.value.version, undefined)
  const versioned = specOf(view({ version: 2 })).components[0].detail
  assert.equal(versioned.case === "view" && versioned.value.version, 2)
})

test("a consumer reading a topic must declare its start position", () => {
  assert.deepEqual(problemsOf(consumer({})), [
    'Notifier: reads topic "orders" and declares no start position; declare static startFrom = StartFrom.earliest, StartFrom.latest or StartFrom.at(...)',
  ])
})

test("a version that is not a positive whole number is refused", () => {
  for (const version of [0, -1, 2.5, Number.NaN, "2"]) {
    const problems = problemsOf(view({ version }))
    assert.equal(problems.length, 1, String(version))
    assert.match(problems[0], /a version is a whole number of 1 or more/)
  }
})

test("a version on a view that reads an entity is accepted and sent: raising it rebuilds the view from the journal", () => {
  assert.deepEqual(problemsOf(view({ topic: undefined, source: Counter, version: 2 })), [])
  const d = specOf(view({ topic: undefined, source: Counter, version: 2 })).components[0].detail
  assert.equal(d.case === "view" && d.value.version, 2)
  assert.equal(d.case === "view" && d.value.source?.source.case, "component")
})

test("a version on a consumer that reads an entity is refused", () => {
  assert.match(problemsOf(consumer({ topic: undefined, source: Counter, startFrom: undefined, version: 2 }))[0], /declares a version, which applies to a topic/)
})

test("a start position on a component that reads an entity is refused", () => {
  assert.match(
    problemsOf(consumer({ topic: undefined, source: Counter, startFrom: StartFrom.latest }))[0],
    /declares a start position, which applies to a topic/,
  )
})

test("a start time must be a valid date", () => {
  assert.throws(() => StartFrom.at(new Date("not a date")), /valid Date/)
})

test("a sidecar too old for start positions is refused, naming what declares one", () => {
  const spec = specOf(consumer({ startFrom: StartFrom.latest }))
  const why = refusal(spec, "1.6")
  assert.ok(why !== undefined)
  assert.match(why, /notifier/)
  assert.match(why, /1\.6/)
  assert.match(why, /1\.7/)
  assert.equal(refusal(spec, "1.7"), undefined)
  assert.equal(refusal(create(SpecSchema, {}), "1.6"), undefined)
})
