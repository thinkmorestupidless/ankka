import { test } from "node:test"
import assert from "node:assert/strict"
import { s, type Schema } from "../src/schema.ts"
import { DecodingError, EncodingError, readJson, renderDouble, writeJson } from "../src/json.ts"
import { Duration, Instant } from "../src/time.ts"

const Item = s.record("Item", { productId: s.string, quantity: s.int })
const Cart = s.record("Cart", { items: s.list(Item), checkedOut: s.boolean, note: s.option(s.string), at: s.option(s.instant) })

test("doubles render as Scala's Double.toString", () => {
  assert.equal(renderDouble(1), "1.0")
  assert.equal(renderDouble(1.5), "1.5")
  assert.equal(renderDouble(0.1), "0.1")
  assert.equal(renderDouble(-7), "-7.0")
  assert.equal(renderDouble(0), "0.0")
  assert.equal(renderDouble(-0), "-0.0")
  assert.equal(renderDouble(1e10), "1.0E10")
  assert.equal(renderDouble(1e7), "1.0E7")
  assert.equal(renderDouble(9999999), "9999999.0")
  assert.equal(renderDouble(0.001), "0.001")
  assert.equal(renderDouble(0.0001), "1.0E-4")
  assert.equal(renderDouble(1.2345e-5), "1.2345E-5")
  assert.equal(renderDouble(123456789012), "1.23456789012E11")
  assert.throws(() => renderDouble(NaN), EncodingError)
})

test("a long past 2^53 round-trips through the reviver as a bigint", () => {
  const Big = s.record("Big", { n: s.long, d: s.double })
  const text = '{"n":9007199254740993,"d":1.0E10}'
  const value = readJson(Big, text)
  assert.equal(value.n, 9007199254740993n)
  assert.equal(value.d, 1e10)
  assert.equal(writeJson(Big, value), text)
  assert.equal(writeJson(Big, { n: 5, d: 2 }), '{"n":5,"d":2.0}')
})

test("an int must be a whole safe number", () => {
  assert.throws(() => writeJson(Item, { productId: "p", quantity: 1.5 }), /quantity: expected a whole number/)
  assert.throws(() => readJson(Item, '{"productId":"p","quantity":1.5}'), /quantity: expected a whole number/)
  assert.throws(() => readJson(Item, '{"productId":"p","quantity":9007199254740993}'), /does not fit an int/)
})

test("every field is written, in declaration order, with null for an absent option", () => {
  const cart = { checkedOut: false, items: [], note: null, at: null }
  assert.equal(writeJson(Cart, cart), '{"items":[],"checkedOut":false,"note":null,"at":null}')
  assert.equal(writeJson(Cart, { items: [], checkedOut: true }), '{"items":[],"checkedOut":true,"note":null,"at":null}')
})

test("reading ignores unknown fields, accepts any order, treats absent options as null", () => {
  const cart = readJson(Cart, '{"extra":1,"checkedOut":true,"items":[{"quantity":2,"productId":"p1","x":0}]}')
  assert.deepEqual(cart, { items: [{ productId: "p1", quantity: 2 }], checkedOut: true, note: null, at: null })
})

test("reading refuses a missing required field, naming its path", () => {
  assert.throws(() => readJson(Cart, '{"items":[{"productId":"p1"}],"checkedOut":true}'), (e: unknown) => {
    assert.ok(e instanceof DecodingError)
    assert.equal(e.path, "items[0].quantity")
    assert.match(e.message, /missing required field/)
    return true
  })
})

test("sum types carry the case name as type, first", () => {
  const Event = s.sumType("Event", { Added: { item: Item }, Removed: { productId: s.string }, Done: {} })
  assert.equal(writeJson(Event, { type: "Added", item: { productId: "p1", quantity: 2 } }), '{"type":"Added","item":{"productId":"p1","quantity":2}}')
  assert.equal(writeJson(Event, { type: "Done" }), '{"type":"Done"}')
  assert.deepEqual(readJson(Event, '{"productId":"p1","type":"Removed"}'), { type: "Removed", productId: "p1" })
  assert.throws(() => readJson(Event, '{"type":"Exploded"}'), /has no case "Exploded"/)
  assert.throws(() => readJson(Event, '{"item":{}}'), /needs a "type"/)
  assert.throws(() => writeJson(Event, { type: "Exploded" }), /has no case "Exploded"/)
})

test("an enumeration is {\"type\":...} on the wire and a string in TypeScript", () => {
  const Status = s.enumeration("Status", "Ready", "Failed")
  assert.equal(writeJson(Status, "Ready"), '{"type":"Ready"}')
  assert.equal(readJson(Status, '{"type":"Failed"}'), "Failed")
  assert.equal(readJson(Status, '"Failed"'), "Failed")
  assert.throws(() => readJson(Status, '{"type":"Unknown"}'), /expected one of "Ready", "Failed"/)
  assert.throws(() => writeJson(Status, "Unknown"), /expected one of/)
})

test("instants keep their digits; durations and dates their text", () => {
  const T = s.record("T", { at: s.instant, took: s.duration })
  const text = '{"at":"2026-09-23T10:00:00.123456789Z","took":"PT1.5S"}'
  const v = readJson(T, text)
  assert.ok(v.at instanceof Instant)
  assert.ok(v.took instanceof Duration)
  assert.equal(writeJson(T, v), text)
  assert.equal(writeJson(T, { at: new Date("2026-09-23T10:00:00.500Z"), took: Duration.ofMillis(10) }), '{"at":"2026-09-23T10:00:00.500Z","took":"PT0.01S"}')
})

test("recursive shapes go as deep as they go", () => {
  type Tree = { label: string; children: Tree[] }
  const Tree: Schema<Tree> = s.record("Tree", { label: s.string, children: s.list(s.lazy(() => Tree)) })
  const text = '{"label":"root","children":[{"label":"leaf","children":[]}]}'
  assert.equal(writeJson(Tree, readJson(Tree, text)), text)
})

test("bytes are base64 inside JSON", () => {
  const B = s.record("B", { data: s.bytes })
  assert.equal(writeJson(B, { data: new Uint8Array([1, 2, 3]) }), '{"data":"AQID"}')
  assert.deepEqual(readJson(B, '{"data":"AQID"}').data, new Uint8Array([1, 2, 3]))
})

test("strings escape as JSON does", () => {
  assert.equal(writeJson(s.string, 'a "quoted"\nline'), '"a \\"quoted\\"\\nline"')
  assert.equal(readJson(s.string, '"\\u00e9"'), "é")
})

test("text that is not JSON is refused with a clear message", () => {
  assert.throws(() => readJson(Item, "not json"), /not JSON/)
  assert.throws(() => readJson(Item, "[1]"), /expected an object for record Item/)
})
