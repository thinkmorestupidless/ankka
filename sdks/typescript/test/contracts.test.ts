// A contract's fingerprint is the one every SDK computes (protocol/fixtures/contracts), and what a
// consumer or view declares about a topic's contract, broker and parallel reading reaches discovery.
import { test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, readFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { Ankka, RegistrationError } from "../src/service.ts"
import { Consumer } from "../src/consumer.ts"
import { View } from "../src/view.ts"
import { Contract, fingerprintOf } from "../src/contract.ts"
import { jsonCodec } from "../src/codec.ts"
import { s, type Infer } from "../src/schema.ts"
import { StartFrom } from "../src/startFrom.ts"
import { refusal } from "../src/server/discovery.ts"
import { Counter } from "./fixtures/counter.ts"

const here = dirname(fileURLToPath(import.meta.url))
const copied = resolve(here, "..", "proto", "fixtures", "contracts", "fingerprints.json")
const original = resolve(here, "..", "..", "..", "protocol", "fixtures", "contracts", "fingerprints.json")
const FIXTURE = existsSync(copied) ? copied : original

type Row = { name: string; schema: unknown; fingerprint: string }

/** The same document, keys reversed and whitespace added: canonicalisation must see one document. */
function reshuffled(value: unknown): string {
  const shuffle = (v: unknown): unknown => {
    if (Array.isArray(v)) return v.map(shuffle)
    if (typeof v === "object" && v !== null) {
      return Object.fromEntries(
        Object.entries(v as Record<string, unknown>)
          .reverse()
          .map(([k, x]) => [k, shuffle(x)]),
      )
    }
    return v
  }
  return JSON.stringify(shuffle(value), null, 2) + "\n"
}

test("every fixture row fingerprints to its value, however the document is laid out", () => {
  const rows = JSON.parse(readFileSync(FIXTURE, "utf8")) as Row[]
  assert.ok(rows.length > 0, `no rows in ${FIXTURE}`)
  for (const row of rows) {
    assert.equal(fingerprintOf(JSON.stringify(row.schema)), row.fingerprint, row.name)
    assert.equal(fingerprintOf(reshuffled(row.schema)), row.fingerprint, `${row.name}, reshuffled`)
    assert.equal(Contract.fromBytes(new TextEncoder().encode(reshuffled(row.schema)), row.name).fingerprint, row.fingerprint)
  }
})

test("a document that is not JSON is refused", () => {
  assert.throws(() => fingerprintOf("{not json"), /must be JSON/)
  assert.throws(() => Contract.fromBytes("[", "order.v1"), /must be JSON/)
})

test("the name rule", () => {
  assert.equal(Contract.fromBytes("{}", "order.v1").name, "order.v1")
  assert.equal(Contract.fromBytes("{}", "cart-events_v2").name, "cart-events_v2")
  for (const name of ["Order", ".v1", "a", "order/v1"]) assert.throws(() => Contract.fromBytes("{}", name), /is not/, name)
})

test("a changed field changes the fingerprint; key order and whitespace do not", () => {
  const a = fingerprintOf('{"b": 1, "a": {"y": [1, 2], "x": "s"}}')
  assert.equal(a, fingerprintOf('{"a":{"x":"s","y":[1,2]},"b":1}\n'))
  assert.notEqual(a, fingerprintOf('{"a":{"x":"s","y":[1,2]},"b":2}'))
  assert.match(a, /^sha256:[0-9a-f]{64}$/)
})

const Message = s.record("Message", { n: s.int })
type Message = Infer<typeof Message>
const codec = jsonCodec(Message, "message")
const orders = Contract.fromBytes('{"type": "object"}', "order.v1")
const enriched = Contract.fromBytes('{"type": "object", "required": ["id"]}', "enriched.v1")

function consumer(declared: Record<string, unknown>) {
  return class Relay extends Consumer<Message, Message> {
    static readonly componentId = "relay"
    static readonly topic = "topic" in declared ? (declared.topic as never) : "orders"
    static readonly source = declared.source as never
    static readonly startFrom = ("startFrom" in declared ? declared.startFrom : StartFrom.earliest) as never
    static readonly contract = declared.contract as never
    static readonly broker = declared.broker as never
    static readonly parallel = declared.parallel as never
    static readonly producesTo = declared.producesTo as never
    static readonly message = codec
    static readonly out = codec
    onMessage(message: Message) {
      return this.effects.produce(message)
    }
  }
}

function view(declared: Record<string, unknown>) {
  return class Summary extends View<Message, Message> {
    static readonly componentId = "summary"
    static readonly topic = "orders"
    static readonly contract = declared.contract as never
    static readonly broker = declared.broker as never
    static readonly parallel = declared.parallel as never
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

test("a consumer's contract, broker, parallel and publication reach discovery", () => {
  const spec = specOf(consumer({ contract: orders, broker: "legacy", parallel: true, producesTo: { topic: "enriched", contract: enriched, broker: "legacy" } }))
  assert.equal(spec.protocolVersion, "1.15")
  const d = spec.components[0].detail
  assert.equal(d.case, "consumer")
  if (d.case !== "consumer") return
  assert.deepEqual(d.value.source?.contract && { name: d.value.source.contract.name, fingerprint: d.value.source.contract.fingerprint }, { name: "order.v1", fingerprint: orders.fingerprint })
  assert.equal(d.value.source?.broker, "legacy")
  assert.equal(d.value.source?.parallel, true)
  assert.equal(d.value.producesTo, "enriched")
  assert.equal(d.value.produces?.topic, "enriched")
  assert.equal(d.value.produces?.contract?.name, "enriched.v1")
  assert.equal(d.value.produces?.contract?.fingerprint, enriched.fingerprint)
  assert.equal(d.value.produces?.broker, "legacy")
})

test("a plain consumer and a plain view declare none of them", () => {
  const c = specOf(consumer({ producesTo: "enriched" })).components[0].detail
  assert.equal(c.case, "consumer")
  if (c.case !== "consumer") return
  assert.equal(c.value.source?.contract, undefined)
  assert.equal(c.value.source?.broker, undefined)
  assert.equal(c.value.source?.parallel, undefined)
  assert.equal(c.value.producesTo, "enriched")
  assert.deepEqual(c.value.produces && { topic: c.value.produces.topic, contract: c.value.produces.contract, broker: c.value.produces.broker }, { topic: "enriched", contract: undefined, broker: undefined })
  const v = specOf(view({})).components[0].detail
  assert.equal(v.case, "view")
  if (v.case !== "view") return
  assert.equal(v.value.source?.contract, undefined)
  assert.equal(v.value.source?.parallel, undefined)
})

test("a view's contract, broker and parallel reach discovery", () => {
  const v = specOf(view({ contract: orders, broker: "legacy", parallel: true })).components[0].detail
  assert.equal(v.case, "view")
  if (v.case !== "view") return
  assert.equal(v.value.source?.contract?.name, "order.v1")
  assert.equal(v.value.source?.broker, "legacy")
  assert.equal(v.value.source?.parallel, true)
})

test("what is not a contract, a broker or a flag is refused, and so are they on a component source", () => {
  assert.match(problemsOf(consumer({ contract: "order.v1" }))[0], /contract must be a Contract/)
  assert.match(problemsOf(consumer({ broker: "" }))[0], /broker must be the name of a broker/)
  assert.match(problemsOf(consumer({ parallel: "yes" }))[0], /parallel must be true or false/)
  assert.match(problemsOf(consumer({ producesTo: { topic: "" } }))[0], /producesTo must be a topic name/)
  assert.match(problemsOf(consumer({ producesTo: { topic: "enriched", contract: "enriched.v1" } }))[0], /producesTo.contract must be a Contract/)
  assert.match(problemsOf(consumer({ topic: undefined, source: Counter, startFrom: undefined, contract: orders }))[0], /which apply to a topic; it reads a component/)
})

test("a sidecar too old for contracts is refused, naming what declares one", () => {
  const spec = specOf(consumer({ contract: orders, producesTo: "enriched" }))
  assert.match(refusal(spec, "1.13") ?? "", /relay declare a topic's contract, broker or parallel reading.*1\.14 or later/)
  assert.equal(refusal(spec, "1.14"), undefined)
  const published = specOf(consumer({ producesTo: { topic: "enriched", broker: "legacy" } }))
  assert.match(refusal(published, "1.13") ?? "", /relay declare/)
  assert.equal(refusal(specOf(consumer({ producesTo: "enriched" })), "1.13"), undefined)
})

test("another project's topic is named by its project, read and published to, and needs 1.15", async () => {
  const { grantsRefusal } = await import("../src/server/discovery.ts")
  const Relay = class extends consumer({ producesTo: { topic: "payments.deposits", project: "spinvibe" } }) {
    static readonly project = "spinvibe"
  }
  const spec = specOf(Relay)
  const d = spec.components[0].detail
  assert.equal(d.case, "consumer")
  if (d.case !== "consumer") return
  assert.equal(d.value.source?.project, "spinvibe")
  assert.equal(d.value.produces?.project, "spinvibe")
  assert.match(grantsRefusal(spec, "1.14") ?? "", /another project's topics need 1\.15 \(relay\)/)
  assert.equal(grantsRefusal(spec, "1.15"), undefined)
  const plain = specOf(consumer({ producesTo: "enriched" })).components[0].detail
  if (plain.case === "consumer") assert.equal(plain.value.source?.project, undefined)
})

test("a project and a broker together are refused", () => {
  const Both = class extends consumer({ broker: "legacy" }) {
    static readonly project = "spinvibe"
  }
  assert.ok(problemsOf(Both).some((p) => p.includes("names a project and a broker")), problemsOf(Both).join("; "))
})
