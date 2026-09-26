import { test } from "node:test"
import assert from "node:assert/strict"
import { Done, done, s, SchemaError, defaultManifest, toJsonSchema, type Infer, type Schema } from "../src/schema.ts"
import { codecForManifest, defaultCodecFor, jsonCodec } from "../src/codec.ts"
import { Instant } from "../src/time.ts"

const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
const Cart = s.record("ShoppingCart", { cartId: s.string, items: s.list(LineItem), checkedOut: s.boolean, checkedOutAt: s.option(s.instant) })
const Event = s.sumType("ShoppingCartEvent", { ItemAdded: { item: LineItem }, ItemRemoved: { productId: s.string }, CheckedOut: {} })
const Status = s.enumeration("Status", "Ready", "Failed")

test("Infer gives the TypeScript type of a shape", () => {
  const cart: Infer<typeof Cart> = { cartId: "c1", items: [{ productId: "p1", name: "Pen", quantity: 2 }], checkedOut: false, checkedOutAt: null }
  const added: Infer<typeof Event> = { type: "ItemAdded", item: cart.items[0]! }
  const checkedOut: Infer<typeof Event> = { type: "CheckedOut" }
  const status: Infer<typeof Status> = "Ready"
  const big: Infer<typeof s.long> = 9007199254740993n
  const when: Infer<typeof s.instant> = Instant.now()
  // @ts-expect-error a case's fields are required
  const bad1: Infer<typeof Event> = { type: "ItemRemoved" }
  // @ts-expect-error not a case
  const bad2: Infer<typeof Event> = { type: "Exploded" }
  // @ts-expect-error a long is a bigint
  const bad3: Infer<typeof s.long> = 1
  // @ts-expect-error not a value of the enumeration
  const bad4: Infer<typeof Status> = "Unknown"
  void [cart, added, checkedOut, status, big, when, bad1, bad2, bad3, bad4]
  assert.ok(true)
})

test("done is the one value of Done", () => {
  const d: Infer<typeof Done> = done
  assert.equal(d, done)
  assert.equal(defaultCodecFor(Done).manifest, "done")
  assert.deepEqual(defaultCodecFor(Done).encode(done), new Uint8Array())
})

test("declaration refuses what cannot be encoded", () => {
  assert.throws(() => s.record("", { a: s.string }), SchemaError)
  assert.throws(() => s.sumType("E", {}), SchemaError)
  assert.throws(() => s.sumType("E", { type: {} }), /cannot be named "type"/)
  assert.throws(() => s.sumType("E", { A: { type: s.string } }), /cannot be named "type"/)
  assert.throws(() => s.enumeration("S", "A", "A"), /repeats a value/)
})

test("default manifests and codecs follow the encoding's rules", () => {
  assert.equal(defaultManifest(Cart), "ShoppingCart")
  assert.equal(defaultManifest(Event), "ShoppingCartEvent")
  assert.equal(defaultManifest(s.option(s.int)), "option[int]")
  assert.equal(defaultCodecFor(s.string).contentType, "text/plain")
  assert.equal(defaultCodecFor(s.int).manifest, "int")
  assert.equal(defaultCodecFor(s.long).manifest, "long")
  assert.equal(defaultCodecFor(s.double).manifest, "double")
  assert.equal(defaultCodecFor(s.boolean).manifest, "boolean")
  assert.equal(defaultCodecFor(s.bytes).contentType, "application/octet-stream")
  assert.equal(defaultCodecFor(s.option(s.int)).manifest, "option[int]")
  assert.equal(defaultCodecFor(Cart).contentType, "application/json")
  assert.equal(jsonCodec(Cart, "shopping-cart").manifest, "shopping-cart")
  assert.equal(codecForManifest("option[option[string]]")?.manifest, "option[option[string]]")
  assert.equal(codecForManifest("shopping-cart"), undefined)
})

test("text codecs render and parse primitives as Scala does", () => {
  const dec = new TextDecoder()
  assert.equal(dec.decode(defaultCodecFor(s.double).encode(1.5)), "1.5")
  assert.equal(dec.decode(defaultCodecFor(s.double).encode(1e10)), "1.0E10")
  assert.equal(defaultCodecFor(s.double).decode(new TextEncoder().encode("1.0E10")), 1e10)
  assert.equal(defaultCodecFor(s.long).decode(new TextEncoder().encode("9007199254740993")), 9007199254740993n)
  assert.equal(defaultCodecFor(s.boolean).decode(new TextEncoder().encode("true")), true)
  assert.throws(() => defaultCodecFor(s.int).decode(new TextEncoder().encode("1.5")))
  assert.throws(() => defaultCodecFor(s.int).decode(new TextEncoder().encode("2147483648")), /does not fit int/)
})

test("a top-level option is a marker byte followed by the inner bytes", () => {
  const codec = defaultCodecFor(s.option(s.int))
  assert.deepEqual(codec.encode(null), new Uint8Array())
  assert.deepEqual(codec.encode(42), new Uint8Array([1, 0x34, 0x32]))
  assert.equal(codec.decode(new Uint8Array([1, 0x34, 0x32])), 42)
  assert.equal(codec.decode(new Uint8Array()), null)
})

test("toJsonSchema describes a tool's input for the model", () => {
  const Lookup = s.record("Lookup", { id: s.string, limit: s.option(s.int), since: s.instant, tags: s.list(s.string) })
  assert.deepEqual(toJsonSchema(Lookup), {
    type: "object",
    properties: {
      id: { type: "string" },
      limit: { anyOf: [{ type: "integer" }, { type: "null" }] },
      since: { type: "string", format: "date-time" },
      tags: { type: "array", items: { type: "string" } },
    },
    required: ["id", "since", "tags"],
    additionalProperties: false,
  })
  const sum = toJsonSchema(Event) as { oneOf: { properties: { type: { const: string } }; required: string[] }[] }
  assert.deepEqual(sum.oneOf.map((c) => c.properties.type.const), ["ItemAdded", "ItemRemoved", "CheckedOut"])
  assert.deepEqual(sum.oneOf[0]!.required, ["type", "item"])
  assert.deepEqual(toJsonSchema(Status), { type: "object", properties: { type: { enum: ["Ready", "Failed"] } }, required: ["type"], additionalProperties: false })
})

test("a lazy schema resolves once it is declared", () => {
  type Tree = { label: string; children: Tree[] }
  const Tree: Schema<Tree> = s.record("Tree", { label: s.string, children: s.list(s.lazy(() => Tree)) })
  assert.equal(defaultManifest(s.lazy(() => Tree)), "Tree")
  const codec = jsonCodec(Tree)
  assert.equal(codec.manifest, "Tree")
  const tree: Tree = { label: "root", children: [{ label: "leaf", children: [] }] }
  assert.deepEqual(codec.decode(codec.encode(tree)), tree)
})
