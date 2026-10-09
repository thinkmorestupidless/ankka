// Personal fields against protocol/fixtures/personal: the envelopes the Scala codec wrote open here,
// an envelope written here opens there (the same key, cipher, associated data and lookup token), and
// the rules on a subject, an erased one and a corrupt envelope hold.

import { afterEach, beforeEach, test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, readFileSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { s } from "../src/schema.ts"
import { jsonCodec } from "../src/codec.ts"
import { decodeJsonValue, writeJson, DecodingError } from "../src/json.ts"
import { allowingLookup, DataSubjectError, erased, FixedKeys, installKeys, PersonalFieldError, present, valueOf, type Personal } from "../src/personal.ts"
import { renderSpec } from "../src/spec.ts"
import { refusal } from "../src/server/discovery.ts"
import { Ankka } from "../src/service.ts"
import { ErasureOutcomes } from "../src/erasure.ts"

const here = dirname(fileURLToPath(import.meta.url))
const copied = resolve(here, "..", "proto", "fixtures", "personal")
const dir = existsSync(copied) ? copied : resolve(here, "..", "..", "..", "protocol", "fixtures", "personal")
const KEYS = JSON.parse(readFileSync(resolve(dir, "keys.json"), "utf8"))
const ROWS: Array<Record<string, any>> = JSON.parse(readFileSync(resolve(dir, "envelopes.json"), "utf8"))

let keys: FixedKeys
beforeEach(() => {
  keys = new FixedKeys(KEYS.ownProject, Buffer.from(KEYS.subjectKey, "base64"), Buffer.from(KEYS.lookupKey, "base64"), [KEYS.destroyedSubject])
  keys.otherProjects.set("payments", Buffer.from(KEYS.subjectKey, "base64"))
  installKeys(keys)
})
afterEach(() => installKeys(undefined))

const anyJson = s.personal(s.lazy(() => s.string))
const read = (envelope: unknown, inner: any = s.string): Personal<any> => decodeJsonValue(s.personal(inner), envelope) as Personal<any>

for (const row of ROWS.filter((r) => r.expect === "value")) {
  test(`a Scala envelope opens to its value: ${row.name}`, () => {
    const plaintext = JSON.parse(row.plaintext)
    const inner =
      typeof plaintext === "string"
        ? s.string
        : typeof plaintext === "number"
          ? s.int
          : row.name === "record"
            ? s.record("Address", { street: s.string, city: s.string })
            : s.record("Nested", { name: s.string, address: s.record("A", { city: s.string, lines: s.list(s.string) }) })
    const value = read(row.envelope, inner)
    assert.equal(value.kind, "present")
    assert.deepEqual(valueOf(value), plaintext)
    assert.equal(value.subject, row.subject)
  })
}

for (const row of ROWS.filter((r) => r.expect === "erased")) {
  test(`an erased or destroyed envelope reads as erased: ${row.name}`, () => {
    const value = read(row.envelope)
    assert.equal(valueOf(value), undefined)
    assert.equal(value.subject, row.subject)
  })
}

for (const row of ROWS.filter((r) => r.expect === "corrupt")) {
  test(`a corrupt envelope is refused: ${row.name}`, () => {
    assert.throws(() => read(row.envelope), (e: unknown) => e instanceof DecodingError && /corrupt/.test((e as Error).message))
  })
}

test("an envelope written here opens, and carries the Scala lookup token in a view row", () => {
  const row = ROWS.find((r) => r.name === "lookup")!
  const written = JSON.parse(allowingLookup(() => writeJson(anyJson, present("player/8c1f", "ada@example.com", { lookup: true }))))
  assert.equal(written.project, "brand")
  assert.equal(written.lookup, row.lookup)
  assert.equal(valueOf(read(written)), "ada@example.com")
})

test("no lookup token outside a view row", () => {
  assert.equal(JSON.parse(writeJson(anyJson, present("player/8c1f", "ada@example.com", { lookup: true }))).lookup, undefined)
})

test("a record round-trips and its plaintext never reaches the bytes", () => {
  const codec = jsonCodec(s.record("Profile", { email: s.personal(s.string), currency: s.string }))
  const bytes = codec.encode({ email: present("player/8c1f", "ada@example.com"), currency: "GBP" })
  const text = new TextDecoder().decode(bytes)
  assert.ok(!text.includes("ada@example.com") && text.includes('"currency":"GBP"'))
  assert.equal(valueOf(codec.decode(bytes).email), "ada@example.com")
})

test("a fresh value for an erased subject is refused", () => {
  keys.erase("player/8c1f")
  assert.throws(() => present("player/8c1f", "x"), PersonalFieldError)
})

test("a stored value of an erased subject is written erased", () => {
  const stored = read(JSON.parse(writeJson(anyJson, present("player/8c1f", "ada@example.com"))))
  keys.erase("player/8c1f")
  assert.equal(valueOf(stored), undefined)
  assert.deepEqual(JSON.parse(writeJson(anyJson, stored)), { subject: "player/8c1f", project: "brand" })
})

test("erased encodes without data", () => {
  assert.deepEqual(JSON.parse(writeJson(anyJson, erased("player/8c1f"))), { subject: "player/8c1f", project: "brand" })
})

test("a subject is checked", () => {
  for (const bad of ["", "a".repeat(254), "player 8c1f", "pläyer"]) assert.throws(() => present(bad, "x"), DataSubjectError)
})

test("no keyring refuses a present value", () => {
  installKeys(undefined)
  assert.throws(() => writeJson(anyJson, present("player/8c1f", "x")), (e: unknown) => e instanceof PersonalFieldError && /no keyring/.test((e as Error).message))
})

test("an erasure handler is declared, and refused by an older sidecar", () => {
  const builder = Ankka.service().onErasure(async () => ErasureOutcomes.done())
  const spec = renderSpec(builder.validate())
  assert.ok(spec.erasureHandler)
  assert.equal(spec.protocolVersion, "1.15")
  assert.match(refusal(spec, "1.14") ?? "", /Run a sidecar speaking 1.15 or later/)
  assert.equal(refusal(spec, "1.15"), undefined)
  assert.ok(!renderSpec(Ankka.service().validate()).erasureHandler)
  assert.throws(() => Ankka.service().onErasure(async () => ErasureOutcomes.done()).onErasure(async () => ErasureOutcomes.done()).validate(), /twice/)
})
