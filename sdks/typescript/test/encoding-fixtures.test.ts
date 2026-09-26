// Every fixture in proto/fixtures, decoded and re-encoded through the default codec (protocol/ENCODING.md).
// The JSON shapes are the Scala `EncodingShapes` from modules/core's EncodingFixturesSuite, redeclared
// with `s`. A fixture with no codec is a failure, never a skip, and a final test asserts every file was named.

import { test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, readdirSync, readFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { s, type Infer, type Schema } from "../src/schema.ts"
import { codecForManifest, jsonCodec, JSON_CONTENT, type Codec } from "../src/codec.ts"
import { decodeJsonValue, reviver, writeJson } from "../src/json.ts"
import { Duration } from "../src/time.ts"

const here = dirname(fileURLToPath(import.meta.url))
const copied = resolve(here, "..", "proto", "fixtures")
const original = resolve(here, "..", "..", "..", "protocol", "fixtures")
const FIXTURES = existsSync(copied) ? copied : original

// The shapes, as EncodingShapes declares them.
const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
const ShoppingCart = s.record("ShoppingCart", { items: s.list(LineItem), checkedOut: s.boolean })
const ShoppingCartEvent = s.sumType("ShoppingCartEvent", {
  ItemAdded: { item: LineItem },
  ItemRemoved: { productId: s.string },
  CheckedOut: {},
})
const Step = s.record("Step", { name: s.string, order: s.int })
const Plan = s.record("Plan", { title: s.string, steps: s.list(Step), labels: s.stringMap(s.string) })
const Status = s.enumeration("Status", "Ready", "Failed")
const Service = s.record("Service", {
  name: s.string,
  createdAt: s.instant,
  owner: s.option(s.string),
  note: s.option(s.string),
  status: Status,
})
const ServiceEvent = s.sumType("ServiceEvent", { Applied: { generation: s.long, at: s.option(s.instant) } })
const Numbers = s.record("Numbers", { big: s.long, half: s.double, tenBillion: s.double, tenth: s.double, negative: s.int })
type Tree = { label: string; children: Tree[] }
const Tree: Schema<Tree> = s.record("Tree", { label: s.string, children: s.list(s.lazy(() => Tree)) })

const JSON_SHAPES: Record<string, Schema> = {
  "shopping-cart": ShoppingCart,
  "shopping-cart-event": ShoppingCartEvent,
  plan: Plan,
  service: Service,
  "service-event": ServiceEvent,
  status: Status,
  numbers: Numbers,
  tree: Tree,
}

interface Fixture {
  manifest: string
  content_type: string
  bytes_base64: string
  value: unknown
}

function codecFor(fixture: Fixture, name: string): Codec<unknown> {
  if (fixture.content_type === JSON_CONTENT) {
    const shape = JSON_SHAPES[fixture.manifest]
    assert.ok(shape, `${name}: no shape declared for JSON manifest ${JSON.stringify(fixture.manifest)}; add it to JSON_SHAPES`)
    return jsonCodec(shape, fixture.manifest)
  }
  const codec = codecForManifest(fixture.manifest)
  assert.ok(codec, `${name}: no codec for manifest ${JSON.stringify(fixture.manifest)} (${fixture.content_type})`)
  return codec
}

/** A decoded text or binary value in the fixture's language-neutral JSON form. */
function neutral(value: unknown): unknown {
  if (value === undefined) return null
  if (typeof value === "object" && value !== null && "done" in value) return null
  if (value instanceof Uint8Array) return Buffer.from(value).toString("base64")
  if (value instanceof Duration) return value.toMillis()
  if (typeof value === "bigint") return value
  return value
}

const files = readdirSync(FIXTURES).filter((f) => f.endsWith(".json")).sort()
const seen = new Set<string>()

for (const file of files) {
  test(`fixture ${file.replace(/\.json$/, "")} round-trips`, () => {
    seen.add(file)
    const raw = readFileSync(join(FIXTURES, file), "utf8")
    // The fixture itself is read with the reviver, so a long in `value` stays exact.
    const fixture = JSON.parse(raw, reviver) as Fixture
    const bytes = new Uint8Array(Buffer.from(fixture.bytes_base64, "base64"))
    const codec = codecFor(fixture, file)
    assert.equal(codec.manifest, fixture.manifest)
    assert.equal(codec.contentType, fixture.content_type)

    const decoded = codec.decode(bytes)

    if (fixture.content_type === JSON_CONTENT) {
      // The expected value read through the same reviver and schema, so 9007199254740993 stays exact.
      const shape = JSON_SHAPES[fixture.manifest]!
      assert.deepEqual(decoded, decodeJsonValue(shape, fixture.value))
      assert.equal(writeJson(shape, decoded), Buffer.from(bytes).toString("utf8"))
    } else {
      assert.deepEqual(neutral(decoded), fixture.value)
    }
    assert.deepEqual(codec.encode(decoded), bytes, `re-encoding ${file} changed the bytes`)
  })
}

test("no fixture is missing", () => {
  assert.ok(files.length > 0, `no fixtures found in ${FIXTURES}`)
  if (existsSync(original) && FIXTURES !== original) {
    assert.deepEqual(files, readdirSync(original).filter((f) => f.endsWith(".json")).sort(), "the copied fixtures differ from protocol/fixtures; run npm run proto")
  }
  assert.deepEqual([...seen].sort(), files)
})

// Compile-time check that the shapes infer the types the Scala types have.
const _cart: Infer<typeof ShoppingCart> = { items: [{ productId: "p1", name: "Pen", quantity: 2 }], checkedOut: false }
const _event: Infer<typeof ShoppingCartEvent> = { type: "ItemRemoved", productId: "p1" }
const _numbers: Infer<typeof Numbers> = { big: 9007199254740993n, half: 1.5, tenBillion: 1e10, tenth: 0.1, negative: -7 }
void _cart
void _event
void _numbers
