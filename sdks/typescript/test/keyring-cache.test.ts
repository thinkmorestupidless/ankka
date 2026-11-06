// The process's key cache (feature 042, FR-020, FR-022): the runtime's rules, on an injected clock. A
// key of the own project is fresh for `ttl` and served in an outage until `outageBound`; a key of another
// project, and a read's erased answer, for `foreignTtl`; at most `maxKeys` are held; and an answer whose
// fetch was overtaken by a notice is never cached.

import { test } from "node:test"
import assert from "node:assert/strict"
import { Keyring, keyringSettings, type KeyReply, type KeyringSettings } from "../src/keyring.ts"
import { personal, subject, PersonalError } from "../src/personal.ts"

const KEY = new Uint8Array(32).map((_, i) => i)
const OWN = "brand"

/** A sidecar answering from `keys` ("project:subject"), as the service of `brand` would; counts what it is asked. */
function sidecar(settings: Partial<KeyringSettings> = {}) {
  const clock = { now: 1_000_000 }
  const keys = new Map<string, Uint8Array>()
  const asked: string[] = []
  let down = false
  let hold: { release: () => void; started: Promise<void> } | undefined
  let started: () => void = () => {}
  const keyring = new Keyring({
    settings,
    clock: () => clock.now,
    fetch: async (p, about, create): Promise<KeyReply> => {
      const project = p || OWN
      asked.push(`${project}:${about}:${create}`)
      if (down) throw new Error("the keyring did not answer")
      const before = keys.get(`${project}:${about}`)
      if (hold) {
        started()
        await new Promise<void>((resolve) => (hold!.release = resolve))
        hold = undefined
      }
      if (before === undefined && create) keys.set(`${project}:${about}`, KEY)
      const key = before ?? (create ? KEY : undefined)
      return { project, answer: key === undefined ? { kind: "erased" } : { kind: "key", key } }
    },
  })
  return {
    keyring,
    clock,
    keys,
    asked,
    outage: (on: boolean) => (down = on),
    holdNext: () => {
      hold = { release: () => {}, started: new Promise<void>((resolve) => (started = resolve)) }
      return hold
    },
  }
}

const envelope = (project: string, about: string) => JSON.stringify({ $personal: 1, project, subject: about, c: "AAAA" })

async function read(keyring: Keyring, project: string, about: string) {
  await keyring.prefetchJson(envelope(project, about))
  return keyring.keyForRead(project, about)
}

/** Teaches the keyring its own project, as the first own answer does. */
async function learn(k: Keyring) {
  await k.prefetchValue(personal(subject("player/0"), "x"))
}

test("an own project's key is asked for again after the ttl", async () => {
  const { keyring, clock, keys, asked } = sidecar()
  await learn(keyring)
  keys.set("brand:player/1", KEY)
  assert.equal((await read(keyring, OWN, "player/1")).kind, "key")
  clock.now += 299_000
  await read(keyring, OWN, "player/1")
  assert.equal(asked.filter((a) => a.includes("player/1")).length, 1)
  clock.now += 2_000
  await read(keyring, OWN, "player/1")
  assert.equal(asked.filter((a) => a.includes("player/1")).length, 2)
})

test("another project's key is held for the foreign ttl only", async () => {
  const { keyring, clock, keys, asked } = sidecar()
  await learn(keyring)
  keys.set("casino:player/1", KEY)
  assert.equal((await read(keyring, "casino", "player/1")).kind, "key")
  clock.now += 29_000
  await read(keyring, "casino", "player/1")
  assert.equal(asked.filter((a) => a.startsWith("casino")).length, 1)
  keys.delete("casino:player/1") // erased there: no notice of it reaches this process
  clock.now += 2_000
  assert.equal((await read(keyring, "casino", "player/1")).kind, "erased")
})

test("a read's erased answer is held for the foreign ttl, so a grant made later is honoured", async () => {
  const { keyring, clock, keys } = sidecar()
  await learn(keyring)
  assert.equal((await read(keyring, "casino", "player/1")).kind, "erased")
  keys.set("casino:player/1", KEY)
  clock.now += 29_000
  assert.equal((await read(keyring, "casino", "player/1")).kind, "erased")
  clock.now += 2_000
  assert.equal((await read(keyring, "casino", "player/1")).kind, "key")
})

test("the cache holds at most maxKeys and lets the least recently used go", async () => {
  const { keyring, keys } = sidecar({ maxKeys: 2 })
  for (const s of ["player/1", "player/2", "player/3"]) keys.set(`brand:${s}`, KEY)
  await read(keyring, OWN, "player/1")
  await read(keyring, OWN, "player/2")
  await read(keyring, OWN, "player/1")
  await read(keyring, OWN, "player/3")
  assert.equal(keyring.size, 2)
  assert.ok(keyring.holds(OWN, "player/1") && keyring.holds(OWN, "player/3"))
  assert.ok(!keyring.holds(OWN, "player/2"))
})

test("in an outage an own key serves until the outage bound, then is unavailable", async () => {
  const { keyring, clock, keys, outage } = sidecar({ ttlMillis: 20_000, outageBoundMillis: 50_000 })
  await learn(keyring)
  keys.set("brand:player/1", KEY)
  await read(keyring, OWN, "player/1")
  outage(true)
  clock.now += 30_000
  assert.equal((await read(keyring, OWN, "player/1")).kind, "key")
  clock.now += 25_000
  await assert.rejects(read(keyring, OWN, "player/1"), (e: unknown) => e instanceof PersonalError && e.reason === "unavailable")
})

test("in an outage another project's key does not outlive the foreign ttl", async () => {
  const { keyring, clock, keys, outage } = sidecar()
  await learn(keyring)
  keys.set("casino:player/1", KEY)
  await read(keyring, "casino", "player/1")
  outage(true)
  clock.now += 31_000
  await assert.rejects(read(keyring, "casino", "player/1"), (e: unknown) => e instanceof PersonalError && e.reason === "unavailable")
})

test("the outage bound may not exceed the notice bound", () => {
  assert.throws(() => keyringSettings({ outageBoundMillis: 61_000 }), /outage-bound \(61000ms\) must not exceed ankka.keyring.notice-bound \(60000ms\)/)
  assert.throws(() => new Keyring({ fetch: async () => ({ project: "", answer: { kind: "erased" } }), settings: { foreignTtlMillis: 0 } }), /cache.foreign-ttl must be positive/)
  assert.throws(() => keyringSettings({ maxKeys: 0 }), /max-keys must be at least 1/)
  assert.equal(keyringSettings().outageBoundMillis, 60_000)
})

for (const how of ["subject", "all"] as const) {
  test(`a fetch in flight when its subject is dropped (${how}) is not cached`, async () => {
    const { keyring, keys, holdNext } = sidecar()
    await learn(keyring)
    keys.set("brand:player/1", KEY)
    const held = holdNext()
    const reading = read(keyring, OWN, "player/1")
    await held.started
    keys.delete("brand:player/1")
    if (how === "subject") keyring.drop(OWN, "player/1")
    else keyring.dropAll()
    held.release()
    assert.equal((await reading).kind, "erased")
  })
}
