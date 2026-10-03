// Graph deltas: the builder against the fixtures the ankka-flow merge sink reads (`keys.json`,
// `deltas.json`) and the ones every SDK's builder must refuse (`refused.json`); the reader; versions;
// and a graph consumer through the unit testkit and through the servicer.
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { existsSync, readFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { create } from "@bufbuild/protobuf"
import { createClient } from "@connectrpc/connect"
import { Ankka, RegistrationError } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { jsonCodec } from "../src/codec.ts"
import { reviver } from "../src/json.ts"
import { s, type Infer } from "../src/schema.ts"
import { PROTOCOL_VERSION } from "../src/spec.ts"
import {
  GRAPH_DELTA_SCHEMA, Graph, GraphConsumer, GraphEffects, GraphError, deltaRecords, edgeKey, elementKey, graphDeltaCodec, nodeKey, readDelta,
  type Delta, type Element, type PropertyValue,
} from "../src/graph.ts"
import { GraphConsumerTestKit } from "../src/testkit/kinds.ts"
import { Kind } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { Consumer as ConsumerService, ConsumerRequestSchema } from "../src/_proto/ankka/protocol/v1/consumer_pb.ts"
import { payload, startServer, type Started } from "./helpers.ts"

const here = dirname(fileURLToPath(import.meta.url))
const copied = resolve(here, "..", "proto", "fixtures", "graph-deltas")
const original = resolve(here, "..", "..", "..", "protocol", "fixtures", "graph-deltas")
const FIXTURES = existsSync(copied) ? copied : original

// Read as the SDK reads JSON: an integer past 2⁵³ arrives as a bigint, not as a rounded number.
function fixture<T>(name: string): T {
  return JSON.parse(readFileSync(join(FIXTURES, name), "utf8"), reviver) as T
}

/** JSON text for a parsed fixture value, bigints as digits. */
function text(value: unknown): string {
  if (typeof value === "bigint") return value.toString()
  if (Array.isArray(value)) return `[${value.map(text).join(",")}]`
  if (typeof value === "object" && value !== null) return `{${Object.entries(value).map(([k, v]) => `${JSON.stringify(k)}:${text(v)}`).join(",")}}`
  return JSON.stringify(value)
}

const utf8 = (t: string) => new TextEncoder().encode(t)
const string = (bytes: Uint8Array) => new TextDecoder().decode(bytes)

type Raw = Record<string, unknown>
interface Row {
  readonly delta: Raw
  readonly key: string
  readonly reads?: Record<string, string>
}
interface Refused {
  readonly name: string
  readonly element?: Raw
  readonly elements?: Raw[]
  readonly why: string
  readonly sequence?: number
}

const graph = new Graph()
const effects = new GraphEffects()

/** The element a fixture row describes, built the way an author builds it. Whatever the row holds is passed on, valid or not. */
function build(d: Raw): Element {
  const version = d.version === undefined ? {} : { version: d.version as number }
  const properties = d.properties === undefined ? {} : { properties: d.properties as Record<string, PropertyValue> }
  const endpoints = { type: d.type as string, from: d.from as string, to: d.to as string }
  if (d.kind === "node") return graph.node(d.id as string, { ...(d.labels === undefined ? {} : { labels: d.labels as string[] }), ...properties, ...version })
  if (d.kind === "edge") return graph.edge(d.id as string, { ...endpoints, ...properties, ...version })
  if (d.element === "node") return graph.tombstoneNode(d.id as string, version)
  return graph.tombstoneEdge(d.id as string, { ...endpoints, ...version })
}

/** What publishing these elements for a change at `sequence` comes to. */
function published(elements: Element[], sequence: number | bigint = 1) {
  return deltaRecords(effects.publish(elements), { "ce-subject": "s", "ankka.sequence": String(sequence) })
}

function kindOf(value: PropertyValue): string {
  if (Array.isArray(value)) return `list:${kindOf(value[0] as PropertyValue)}`
  if (typeof value === "string") return "string"
  if (typeof value === "boolean") return "boolean"
  if (typeof value === "bigint") return "integer"
  return Number.isInteger(value) ? "integer" : "float"
}

describe("the shared fixtures: what the builder writes is what the sink reads", () => {
  const keys = fixture<Row[]>("keys.json")
  const deltas = fixture<Row[]>("deltas.json")
  const refused = fixture<Refused[]>("refused.json")

  test("the fixtures are the ones this suite expects", () => {
    assert.ok(keys.length >= 8, `${keys.length} rows of keys`)
    assert.ok(deltas.length >= 12, `${deltas.length} rows of deltas`)
    assert.ok(refused.length >= 42, `${refused.length} refused rows`)
    assert.deepEqual([...new Set(refused.map((r) => r.why))].sort(), ["duplicate", "endpoints", "id", "identifier", "integer-range", "no-sequence", "property-value", "reserved", "version"])
    const kinds = new Set(deltas.flatMap((r) => Object.values(r.reads ?? {})))
    assert.deepEqual([...kinds].sort(), ["boolean", "float", "integer", "list:boolean", "list:float", "list:integer", "list:string", "string"])
    // Integers past 2^53 survived the parse.
    assert.equal(typeof deltas.find((r) => r.key === "node:past-two-to-the-53")!.delta.version, "bigint")
  })

  for (const [file, rows] of [["keys.json", keys], ["deltas.json", deltas]] as const) {
    rows.forEach((row, i) => {
      test(`${file} row ${i} (${row.key}): built, it has the row's key and reads back as the row's delta`, () => {
        const records = published([build(row.delta)])
        assert.equal(records.length, 1)
        const record = records[0]!
        assert.equal(record.key, row.key)
        // Equality is the reader's: 2.0 is 2, and labels and properties that are absent are empty.
        const written = readDelta(record.value, row.key)
        const given = readDelta(utf8(text(row.delta)), row.key)
        assert.deepEqual(written, given)
        assert.equal(elementKey(written), row.key)
        if (row.reads) {
          const properties = "properties" in written ? written.properties : {}
          assert.deepEqual(Object.fromEntries(Object.entries(properties).map(([k, v]) => [k, kindOf(v)])), row.reads)
        }
      })
    })
  }

  refused.forEach((row) => {
    test(`refused (${row.why}): ${row.name}`, () => {
      const attempt = () => published((row.elements ?? [row.element!]).map(build), row.sequence ?? 1)
      assert.throws(attempt, (e: unknown) => {
        assert.ok(e instanceof GraphError, `not a GraphError: ${String(e)}`)
        assert.equal(e.why, row.why, e.message)
        return true
      })
    })
  })
})

describe("the builder", () => {
  test("what a node, an edge and the two tombstones are written as", () => {
    const records = published(
      [
        graph.node("cart:c1", { labels: ["Cart"], properties: { cartId: "c1", checkedOut: false, lines: 2, ratio: 0.25, tags: ["a", "b"] } }),
        graph.edge("checked-out:c1", { type: "CHECKED_OUT", from: "cart:c1", to: "checkout:c1" }),
        graph.tombstoneNode("cart:c2"),
        graph.tombstoneEdge("checked-out:c2", { type: "CHECKED_OUT", from: "cart:c2", to: "checkout:c2" }),
        graph.node("bare"),
      ],
      7,
    )
    assert.deepEqual(records.map((r) => [r.key, string(r.value)]), [
      ["node:cart:c1", '{"kind":"node","id":"cart:c1","version":7,"labels":["Cart"],"properties":{"cartId":"c1","checkedOut":false,"lines":2,"ratio":0.25,"tags":["a","b"]}}'],
      ["edge:checked-out:c1", '{"kind":"edge","id":"checked-out:c1","version":7,"type":"CHECKED_OUT","from":"cart:c1","to":"checkout:c1","properties":{}}'],
      ["node:cart:c2", '{"kind":"tombstone","element":"node","id":"cart:c2","version":7}'],
      ["edge:checked-out:c2", '{"kind":"tombstone","element":"edge","id":"checked-out:c2","version":7,"type":"CHECKED_OUT","from":"cart:c2","to":"checkout:c2"}'],
      // Labels and properties are always written, empty when there are none.
      ["node:bare", '{"kind":"node","id":"bare","version":7,"labels":[],"properties":{}}'],
    ])
  })

  test("the keys: a node and an edge with one id are different elements", () => {
    assert.equal(nodeKey("same"), "node:same")
    assert.equal(edgeKey("same"), "edge:same")
    assert.equal(nodeKey("cart:c1"), "node:cart:c1")
    const records = published([graph.node("same"), graph.edge("same", { type: "T", from: "a", to: "b" })])
    assert.deepEqual(records.map((r) => r.key), ["node:same", "edge:same"])
    // A tombstone has the key of the element it marks.
    assert.equal(elementKey(graph.tombstoneEdge("same", { type: "T", from: "a", to: "b" })), "edge:same")
  })

  test("a number that is not finite is refused", () => {
    for (const bad of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
      assert.throws(() => graph.node("n", { properties: { p: bad } }), (e: unknown) => e instanceof GraphError && e.why === "property-value")
      assert.throws(() => graph.node("n", { properties: { p: [1.5, bad] } }), (e: unknown) => e instanceof GraphError && e.why === "property-value")
    }
  })

  test("an integral number must be a safe integer; a bigint carries the rest, and reads back as one", () => {
    assert.throws(() => graph.node("n", { properties: { p: 2 ** 53 } }), (e: unknown) => e instanceof GraphError && e.why === "integer-range" && /bigint/.test(e.message))
    const [record] = published([graph.node("n", { properties: { big: 9007199254740993n, small: 5n, max: 9223372036854775807n, min: -9223372036854775808n }, version: 9007199254740993n })])
    assert.equal(string(record!.value), '{"kind":"node","id":"n","version":9007199254740993,"labels":[],"properties":{"big":9007199254740993,"small":5,"max":9223372036854775807,"min":-9223372036854775808}}')
    const back = readDelta(record!.value)
    assert.equal(back.version, 9007199254740993n)
    assert.deepEqual("properties" in back && back.properties, { big: 9007199254740993n, small: 5, max: 9223372036854775807n, min: -9223372036854775808n })
    assert.throws(() => graph.node("n", { properties: { p: 2n ** 63n } }), (e: unknown) => e instanceof GraphError && e.why === "integer-range")
  })

  test("a whole-number float is an integer: written as one, and the same kind as one in a list", () => {
    const [record] = published([graph.node("n", { properties: { whole: 2.0, mixedLooking: [1, 2.0, 3], half: 0.5, tiny: 1e-7 } })])
    assert.equal(string(record!.value), '{"kind":"node","id":"n","version":1,"labels":[],"properties":{"whole":2,"mixedLooking":[1,2,3],"half":0.5,"tiny":1.0E-7}}')
    assert.equal(JSON.parse(string(record!.value)).properties.tiny, 1e-7)
  })

  test("a property name may be any string but the three the sink keeps", () => {
    const [record] = published([graph.node("n", { properties: { "with space": 1, "": "empty name", _private: true } })])
    assert.deepEqual("properties" in record!.delta && Object.keys(record!.delta.properties), ["with space", "", "_private"])
    for (const name of ["id", "_version", "_deleted"]) {
      assert.throws(() => graph.node("n", { properties: { [name]: 1 } }), (e: unknown) => e instanceof GraphError && e.why === "reserved")
    }
  })

  test("a fault is named where the element is built, with the element it is in", () => {
    assert.throws(() => graph.node("", {}), /id must be a non-empty string/)
    assert.throws(() => graph.node("cart:c1", { labels: ["Has Space"] }), /node "cart:c1": label "Has Space" is not an identifier/)
    assert.throws(() => graph.edge("e", { type: "KNOWS", from: "a", to: "" }), /edge "e": an edge needs a type, a from and a to; to is missing or empty/)
    assert.throws(() => graph.node("n", { labels: "Cart" as unknown as string[] }), /labels must be a list of identifiers/)
    assert.throws(() => graph.node("n", { properties: { p: null as unknown as string } }), /property "p" is null/)
    assert.throws(() => graph.tombstoneNode("n", { version: 0 }), /version must be a whole number of at least 1/)
  })

  test("an element built by hand is checked when it is published, as one built by the builder is", () => {
    assert.throws(() => effects.publish([{ kind: "node", id: "n", labels: ["bad label"], properties: {} }]), (e: unknown) => e instanceof GraphError && e.why === "identifier")
    assert.throws(() => effects.publish([{ kind: "node", id: "n", labels: [], properties: { id: 1 } }]), (e: unknown) => e instanceof GraphError && e.why === "reserved")
    assert.throws(() => effects.publish([{ kind: "nope" } as unknown as Element]), /not a graph element/)
  })

  test("an effect is a frozen value; building one publishes nothing", () => {
    const effect = effects.publish([graph.node("n")])
    assert.ok(Object.isFrozen(effect))
    assert.equal(effect.kind, "publish")
    assert.equal(effects.done().kind, "done")
    assert.equal(effects.ignore().kind, "ignore")
    assert.deepEqual(deltaRecords(effects.done(), {}), [])
    assert.deepEqual(deltaRecords(effects.publish([]), {}), [])
  })
})

describe("versions", () => {
  test("an element takes the change's sequence number; one that states a version keeps it", () => {
    const records = published([graph.node("a"), graph.node("b", { version: 42 }), graph.tombstoneNode("c"), graph.edge("d", { type: "T", from: "a", to: "b", version: 5n })], 7)
    assert.deepEqual(records.map((r) => r.delta.version), [7, 42, 7, 5])
  })

  test("a change with no sequence number needs a stated version", () => {
    const noSequence = (e: unknown) => e instanceof GraphError && e.why === "no-sequence" && /node "a": this source has no sequence number; state a version/.test(e.message)
    assert.throws(() => published([graph.node("a")], 0), noSequence)
    assert.throws(() => deltaRecords(effects.publish([graph.node("a")]), { "ce-subject": "s" }), noSequence)
    assert.deepEqual(published([graph.node("a", { version: 3 })], 0).map((r) => r.delta.version), [3])
  })

  test("a stated version below 1, or not whole, is refused", () => {
    for (const bad of [0, -1, 1.5, 0n, 2n ** 63n, Number.NaN, 2 ** 53]) {
      assert.throws(() => graph.node("a", { version: bad }), (e: unknown) => e instanceof GraphError && e.why === "version", String(bad))
    }
  })

  test("the same element twice in one result is refused; a node and an edge sharing an id are not", () => {
    const duplicate = (e: unknown) => e instanceof GraphError && e.why === "duplicate"
    assert.throws(() => published([graph.node("a"), graph.node("a", { labels: ["Other"] })]), duplicate)
    assert.throws(() => published([graph.node("a"), graph.tombstoneNode("a")]), duplicate)
    assert.equal(published([graph.node("a"), graph.edge("a", { type: "T", from: "x", to: "y" })]).length, 2)
  })

  test("the same change handled twice publishes equal records", () => {
    const once = () => published([graph.node("cart:c1", { labels: ["Cart"], properties: { cartId: "c1" } }), graph.edge("e", { type: "T", from: "a", to: "b" })], 4)
    const first = once().map((r) => [r.key, string(r.value)])
    assert.deepEqual(once().map((r) => [r.key, string(r.value)]), first)
  })
})

describe("the reader", () => {
  const read = (t: string, key?: string) => readDelta(utf8(t), key)

  test("it reads what the contract writes, labels and properties absent or empty alike", () => {
    assert.deepEqual(read('{"kind":"node","id":"n","version":1}'), { kind: "node", id: "n", version: 1, labels: [], properties: {} })
    assert.deepEqual(read('{"kind":"edge","id":"e","version":2,"type":"T","from":"a","to":"b"}'), { kind: "edge", id: "e", version: 2, type: "T", from: "a", to: "b", properties: {} })
    assert.deepEqual(read('{"kind":"tombstone","element":"node","id":"n","version":3}'), { kind: "tombstone", element: "node", id: "n", version: 3 })
    // Fields the contract does not name are ignored, and 2.0 is 2.
    assert.deepEqual(read('{"kind":"node","id":"n","version":1,"extra":true,"properties":{"p":2.0}}'), { kind: "node", id: "n", version: 1, labels: [], properties: { p: 2 } })
  })

  test("it accepts a version of 0, which the contract allows and the builder does not write", () => {
    assert.equal(read('{"kind":"node","id":"n","version":0}').version, 0)
    assert.equal(read('{"kind":"node","id":"n","version":1e3}').version, 1000)
  })

  test("given a key, the key must be the delta's element key", () => {
    const node = '{"kind":"node","id":"cart:c1","version":1}'
    assert.equal(read(node, "node:cart:c1").id, "cart:c1")
    assert.throws(() => read(node, "cart:c1"), /key 'cart:c1' is not this delta's element key 'node:cart:c1'/)
    assert.throws(() => read(node, "edge:cart:c1"), /is not this delta's element key 'node:cart:c1'/)
    assert.equal(read('{"kind":"tombstone","element":"edge","id":"e","version":1,"type":"T","from":"a","to":"b"}', "edge:e").kind, "tombstone")
  })

  test("it refuses what the contract refuses, in the contract's words", () => {
    const cases: [string, RegExp][] = [
      ["[]", /not a JSON object/],
      ["nonsense", /not a JSON object/],
      ['{"id":"n","version":1}', /kind missing/],
      ['{"kind":"vertex","id":"n","version":1}', /unknown kind 'vertex'/],
      ['{"kind":"node","id":"","version":1}', /id missing or empty/],
      ['{"kind":"node","id":"n"}', /version is not a non-negative integer/],
      ['{"kind":"node","id":"n","version":-1}', /version is not a non-negative integer/],
      ['{"kind":"node","id":"n","version":1.5}', /version is not a non-negative integer/],
      ['{"kind":"node","id":"n","version":9223372036854775808}', /version is not a non-negative integer/],
      ['{"kind":"node","id":"n","version":1,"labels":["a b"]}', /labels must be an array of identifiers/],
      ['{"kind":"edge","id":"e","version":1,"type":"T","from":"a"}', /edge needs type, from and to/],
      ['{"kind":"tombstone","id":"n","version":1}', /tombstone needs element 'node' or 'edge'/],
      ['{"kind":"tombstone","element":"edge","id":"e","version":1}', /tombstone of an edge needs type, from and to/],
      ['{"kind":"node","id":"n","version":1,"properties":[]}', /properties must be an object/],
      ['{"kind":"node","id":"n","version":1,"properties":{"p":null}}', /property 'p' is not a scalar or array of scalars/],
      ['{"kind":"node","id":"n","version":1,"properties":{"p":[1,"a"]}}', /property 'p' is not a scalar or array of scalars/],
      ['{"kind":"node","id":"n","version":1,"properties":{"p":[]}}', /property 'p' is not a scalar or array of scalars/],
      ['{"kind":"node","id":"n","version":1,"properties":{"p":1.0E30}}', /property 'p' is not a scalar or array of scalars/],
      ['{"kind":"node","id":"n","version":1,"properties":{"_deleted":true}}', /property '_deleted' is reserved/],
    ]
    for (const [value, message] of cases) assert.throws(() => read(value), message, value)
  })

  test("the codec is the builder's writer and this reader, under the contract's name", () => {
    assert.equal(graphDeltaCodec.manifest, "ankka.graph-delta.v1")
    assert.equal(graphDeltaCodec.manifest, GRAPH_DELTA_SCHEMA)
    assert.equal(graphDeltaCodec.contentType, "application/json")
    const delta: Delta = { kind: "node", id: "n", version: 3, labels: ["A"], properties: { p: 1 } }
    assert.deepEqual(graphDeltaCodec.decode(graphDeltaCodec.encode(delta)), delta)
    assert.throws(() => graphDeltaCodec.encode({ ...delta, properties: { id: 1 } }), (e: unknown) => e instanceof GraphError && e.why === "reserved")
  })
})

// ── a graph consumer ──

const Change = s.record("Change", { what: s.string })
type Change = Infer<typeof Change>

class Things extends GraphConsumer<Change> {
  static readonly componentId = "things"
  static readonly topic = "changes"
  static readonly message = jsonCodec(Change, "change")
  static readonly producesTo = "thing-graph"

  onMessage(change: Change) {
    const id = this.subject
    switch (change.what) {
      case "node":
        return this.effects.publish([this.graph.node(`thing:${id}`, { labels: ["Thing"], properties: { name: id } })])
      case "stated":
        return this.effects.publish([this.graph.node(`thing:${id}`, { version: 42 })])
      case "pair":
        return this.effects.publish([this.graph.node(`thing:${id}`), this.graph.edge(`owns:${id}`, { type: "OWNS", from: "owner:1", to: `thing:${id}` })])
      case "twice":
        return this.effects.publish([this.graph.node(`thing:${id}`), this.graph.node(`thing:${id}`)])
      case "none":
        return this.effects.publish([])
      case "done":
        return this.effects.done()
      default:
        return this.effects.ignore()
    }
  }

  override onDelete() {
    return this.effects.publish([this.graph.tombstoneNode(`thing:${this.subject}`)])
  }
}

describe("a graph consumer, through the unit testkit", () => {
  test("it answers with the elements published, read back from what would be on the topic", async () => {
    const kit = GraphConsumerTestKit.of(Things)
    assert.deepEqual(await kit.onMessage({ what: "node" }, { subject: "t1", sequence: 3 }), [{ kind: "node", id: "thing:t1", version: 3, labels: ["Thing"], properties: { name: "t1" } }])
    assert.deepEqual(await kit.onMessage({ what: "pair" }, { subject: "t1", sequence: 4 }), [
      { kind: "node", id: "thing:t1", version: 4, labels: [], properties: {} },
      { kind: "edge", id: "owns:t1", version: 4, type: "OWNS", from: "owner:1", to: "thing:t1", properties: {} },
    ])
    assert.deepEqual(await kit.onDelete({ subject: "t1", sequence: 5 }), [{ kind: "tombstone", element: "node", id: "thing:t1", version: 5 }])
    assert.deepEqual(await kit.onMessage({ what: "none" }), [])
    assert.deepEqual(await kit.onMessage({ what: "done" }), [])
    assert.deepEqual(await kit.onMessage({ what: "anything else" }), [])
  })

  test("the sequence defaults to 1; a stated version replaces it; a source with none needs one stated", async () => {
    const kit = GraphConsumerTestKit.of(Things)
    assert.equal((await kit.onMessage({ what: "node" }))[0]!.version, 1)
    assert.equal((await kit.onMessage({ what: "stated" }, { sequence: 9 }))[0]!.version, 42)
    // A topic's message arrives at sequence 0: the default has nothing to be.
    await assert.rejects(kit.onMessage({ what: "node" }, { sequence: 0 }), (e: unknown) => e instanceof GraphError && e.why === "no-sequence")
    assert.equal((await kit.onMessage({ what: "stated" }, { sequence: 0 }))[0]!.version, 42)
    await assert.rejects(kit.onMessage({ what: "twice" }), (e: unknown) => e instanceof GraphError && e.why === "duplicate")
  })

  test("handling the same change twice gives equal elements", async () => {
    const kit = GraphConsumerTestKit.of(Things)
    const first = await kit.onMessage({ what: "pair" }, { subject: "t9", sequence: 12 })
    assert.deepEqual(await kit.onMessage({ what: "pair" }, { subject: "t9", sequence: 12 }), first)
  })

  test("it holds the server's rule: deltas go only to a runtime that accepts several messages", async () => {
    const kit = GraphConsumerTestKit.of(Things)
    await assert.rejects(kit.onMessage({ what: "node" }, { metadata: { "ankka.protocol": "1.2" } }), /this runtime speaks protocol 1\.2; several messages or a record key need 1\.3/)
    assert.deepEqual(await kit.onMessage({ what: "none" }, { metadata: { "ankka.protocol": "1.2" } }), [])
  })

  test("a graph consumer has no produce and no key to set", () => {
    const things = new Things()
    // @ts-expect-error a graph consumer's effects publish elements and nothing else
    assert.equal(things.effects.produce, undefined)
    // @ts-expect-error there is no produceAll either
    assert.equal(things.effects.produceAll, undefined)
    // @ts-expect-error an element has no key field to set
    const withKey: Element = { kind: "node", id: "n", labels: [], properties: {}, key: "mine" }
    // What is published is keyed by the element whatever else the object carries.
    assert.deepEqual(deltaRecords(things.effects.publish([withKey]), { "ankka.sequence": "1" }).map((r) => r.key), ["node:n"])
  })
})

describe("a graph consumer, registered and served", () => {
  let started: Started
  let consumer: ReturnType<typeof createClient<typeof ConsumerService>>
  before(async () => {
    started = await startServer(Ankka.service({ client: noClient(), log: () => {} }).register(Things))
    consumer = createClient(ConsumerService, started.transport)
  })
  after(() => started.stop())

  const request = (what: string, metadata: Record<string, string>, deleted = false) =>
    create(ConsumerRequestSchema, {
      componentId: "things",
      ...(deleted ? {} : { message: payload(jsonCodec(Change, "change"), { what }) }),
      metadata: { entries: Object.entries(metadata).map(([key, value]) => ({ key, value })) },
      deleted,
    })
  const change = { "ce-subject": "t1", "ankka.sequence": "6", "ankka.protocol": PROTOCOL_VERSION }

  test("it is discovered as a consumer that produces to its topic", () => {
    const spec = Ankka.service({ client: noClient() }).register(Things).spec()
    assert.equal(spec.components.length, 1)
    const component = spec.components[0]!
    assert.equal(component.kind, Kind.CONSUMER)
    assert.equal(component.detail.case, "consumer")
    if (component.detail.case === "consumer") {
      assert.equal(component.detail.value.producesTo, "thing-graph")
      assert.equal(component.detail.value.source?.source.case, "topic")
    }
  })

  test("each element is one message: the delta as its payload, the element key as its key, the contract as its type", async () => {
    const reply = await consumer.handle(request("pair", change))
    assert.equal(reply.effect.case, "produceAll")
    if (reply.effect.case !== "produceAll") return
    const messages = reply.effect.value.messages
    assert.deepEqual(messages.map((m) => m.key), ["node:thing:t1", "edge:owns:t1"])
    assert.deepEqual(messages.map((m) => [m.payload?.manifest, m.payload?.contentType]), [["ankka.graph-delta.v1", "application/json"], ["ankka.graph-delta.v1", "application/json"]])
    assert.deepEqual(messages.map((m) => m.metadata?.entries.map((e) => [e.key, e.value])), [[["ce-type", "ankka.graph-delta.v1"]], [["ce-type", "ankka.graph-delta.v1"]]])
    assert.deepEqual(messages.map((m) => string(m.payload!.data)), [
      '{"kind":"node","id":"thing:t1","version":6,"labels":[],"properties":{}}',
      '{"kind":"edge","id":"owns:t1","version":6,"type":"OWNS","from":"owner:1","to":"thing:t1","properties":{}}',
    ])
  })

  test("the deletion handler publishes its tombstone at the deletion's sequence number", async () => {
    const reply = await consumer.handle(request("", { ...change, "ankka.sequence": "8" }, true))
    assert.equal(reply.effect.case, "produceAll")
    if (reply.effect.case !== "produceAll") return
    assert.deepEqual(reply.effect.value.messages.map((m) => [m.key, string(m.payload!.data)]), [["node:thing:t1", '{"kind":"tombstone","element":"node","id":"thing:t1","version":8}']])
  })

  test("nothing to publish is done; done and ignore are themselves", async () => {
    assert.equal((await consumer.handle(request("none", change))).effect.case, "done")
    assert.equal((await consumer.handle(request("done", change))).effect.case, "done")
    assert.equal((await consumer.handle(request("other", change))).effect.case, "ignore")
  })

  test("a refusal fails the request, and nothing is answered in part", async () => {
    await assert.rejects(consumer.handle(request("twice", change)), /is in this result twice/)
    // A topic's message has sequence 0.
    await assert.rejects(consumer.handle(request("node", { ...change, "ankka.sequence": "0" })), /this source has no sequence number; state a version/)
    assert.equal((await consumer.handle(request("stated", { ...change, "ankka.sequence": "0" }))).effect.case, "produceAll")
  })

  test("a runtime that did not say it accepts several messages is not sent deltas", async () => {
    await assert.rejects(consumer.handle(request("node", { "ce-subject": "t1", "ankka.sequence": "6" })), (e: Error) => e.message.endsWith("this runtime speaks protocol 1.2 or earlier; several messages or a record key need 1.3"))
    await assert.rejects(consumer.handle(request("node", { ...change, "ankka.protocol": "1.2" })), /speaks protocol 1\.2;/)
  })

  test("a graph consumer needs a topic for its deltas, and declares no out shape", () => {
    class Nowhere extends GraphConsumer<Change> {
      static readonly componentId = "nowhere"
      static readonly topic = "changes"
      static readonly message = jsonCodec(Change, "change")
      onMessage() {
        return this.effects.ignore()
      }
    }
    assert.throws(
      () => Ankka.service({ client: noClient() }).register(Nowhere as never).validate(),
      (e: unknown) => e instanceof RegistrationError && /Nowhere: needs a static producesTo/.test(e.message),
    )
    class WithOut extends GraphConsumer<Change> {
      static readonly componentId = "with-out"
      static readonly topic = "changes"
      static readonly message = jsonCodec(Change, "change")
      static readonly producesTo = "g"
      static readonly out = jsonCodec(Change, "change")
      onMessage() {
        return this.effects.ignore()
      }
    }
    assert.throws(
      () => Ankka.service({ client: noClient() }).register(WithOut).validate(),
      (e: unknown) => e instanceof RegistrationError && /declares a static out/.test(e.message),
    )
    class NoSource extends GraphConsumer<Change> {
      static readonly componentId = "no-source"
      static readonly message = jsonCodec(Change, "change")
      static readonly producesTo = "g"
      onMessage() {
        return this.effects.ignore()
      }
    }
    assert.throws(
      () => Ankka.service({ client: noClient() }).register(NoSource).validate(),
      (e: unknown) => e instanceof RegistrationError && /needs a static source/.test(e.message),
    )
  })
})
