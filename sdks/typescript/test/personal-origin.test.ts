// FR-027 (feature 042): what a service keeps of a personal field it read from another project's
// envelope stays sealed under the producing project's key, so an erasure there reaches the copy here
// with no notice of its own. A field a handler makes, even with the same value, is the service's own.

import { test } from "node:test"
import assert from "node:assert/strict"
import { s } from "../src/schema.ts"
import { readJson, writeJson } from "../src/json.ts"
import { currentKeyring, installKeyring, Keyring, type KeyReply } from "../src/keyring.ts"
import { personal, subject, withinScope, Erased, Present, type Personal } from "../src/personal.ts"

const KEY = new Uint8Array(32).map((_, i) => i)
const Registered = s.record("Registered", { playerId: s.string, email: s.personal(s.string) })
const Copy = s.record("Copy", { email: s.personal(s.string) })

/** A keyring of `own` answering from `keys` ("project:subject"); a write of the own project makes a key. */
function keyringOf(own: string, keys: Map<string, Uint8Array>, clock?: { now: number }) {
  return new Keyring({
    ...(clock ? { clock: () => clock.now } : {}),
    fetch: async (p, about, create): Promise<KeyReply> => {
      const project = p || own
      let key = keys.get(`${project}:${about}`)
      if (key === undefined && create && project === own) keys.set(`${project}:${about}`, (key = new Uint8Array(32).fill(9)))
      return { project, answer: key === undefined ? { kind: "erased" } : { kind: "key", key } }
    },
  })
}

async function under<T>(k: Keyring, body: () => Promise<T>): Promise<T> {
  const before = currentKeyring()
  installKeyring(k)
  try {
    return await body()
  } finally {
    installKeyring(before)
  }
}

async function write(k: Keyring, shape: typeof Copy | typeof Registered, value: unknown, manifest: string): Promise<string> {
  await k.prefetchValue(value)
  return withinScope(manifest, () => writeJson(shape as never, value as never))
}

async function read<T>(k: Keyring, shape: typeof Copy | typeof Registered, json: string, manifest: string): Promise<T> {
  await k.prefetchJson(json)
  return withinScope(manifest, () => readJson(shape as never, json)) as T
}

/** What 'casino' published: an envelope under its key. And a consumer in 'brand' holding a grant. */
async function setup(clock = { now: 1_000_000 }) {
  const keys = new Map([["casino:player/8c1f", KEY]])
  const producer = keyringOf("casino", keys)
  const published = await under(producer, () => write(producer, Registered, { playerId: "8c1f", email: personal(subject("player/8c1f"), "ada@example.com") }, "registered"))
  const consumer = keyringOf("brand", keys, clock)
  await consumer.prefetchValue(personal(subject("player/0"), "x")) // learns its own project
  return { keys, consumer, published, clock }
}

test("a field read from another project is kept under that project's key", async () => {
  const { keys, consumer, published } = await setup()
  await under(consumer, async () => {
    const event = await read<{ email: Personal<string> }>(consumer, Registered, published, "registered")
    const kept = await write(consumer, Copy, { email: event.email }, "copy")
    const envelope = JSON.parse(kept).email
    assert.equal(envelope.project, "casino")
    assert.ok(typeof envelope.c === "string" && !kept.includes("ada@example.com"))
    assert.ok(!keys.has("brand:player/8c1f"), "this project made a key of its own for the subject")
    const again = await read<{ email: Personal<string> }>(consumer, Copy, kept, "copy")
    assert.ok(again.email instanceof Present && again.email.value === "ada@example.com")
  })
})

test("an erasure in the producing project reaches the kept copy", async () => {
  const { keys, consumer, published, clock } = await setup()
  await under(consumer, async () => {
    const event = await read<{ email: Personal<string> }>(consumer, Registered, published, "registered")
    const kept = await write(consumer, Copy, { email: event.email }, "copy")
    keys.delete("casino:player/8c1f") // erased there; no notice reaches 'brand'
    clock.now += 31_000
    const again = await read<{ email: Personal<string> }>(consumer, Copy, kept, "copy")
    assert.ok(again.email instanceof Erased)
  })
})

test("a kept field whose key is gone is written erased", async () => {
  const { keys, consumer, published, clock } = await setup()
  await under(consumer, async () => {
    const event = await read<{ email: Personal<string> }>(consumer, Registered, published, "registered")
    keys.delete("casino:player/8c1f")
    clock.now += 31_000
    const kept = await write(consumer, Copy, { email: event.email }, "copy")
    assert.deepEqual(JSON.parse(kept).email, { $personal: 1, project: "casino", subject: "player/8c1f" })
  })
})

test("a field a handler makes with the same value is the service's own", async () => {
  const { consumer, published } = await setup()
  await under(consumer, async () => {
    const event = await read<{ email: Personal<string> }>(consumer, Registered, published, "registered")
    assert.ok(event.email instanceof Present)
    const made = personal(event.email.subject, event.email.value)
    const kept = await write(consumer, Copy, { email: made }, "copy")
    assert.equal(JSON.parse(kept).email.project, "brand")
  })
})
