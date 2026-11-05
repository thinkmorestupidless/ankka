// Personal fields (feature 042): the shared fixtures in proto/fixtures/personal (the envelope byte for
// byte, the subjects, the edge form), the keyring's prefetch, cache and notices, the errors of
// contracts/sdk.md, an entity whose events hold a personal field, the refusal of a runtime below 1.23,
// and the erasure handler.

import { test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, readFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { inspect } from "node:util"
import { create } from "@bufbuild/protobuf"
import { createServer, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import { Client, SubjectKeyReplySchema, type SubjectKeyRequest } from "../src/_proto/ankka/protocol/v1/client_pb.ts"
import { s, toJsonSchema, type Schema } from "../src/schema.ts"
import { codecFor, jsonCodec } from "../src/codec.ts"
import { readJson, writeJson } from "../src/json.ts"
import { personal, subject, Erased, Present, PersonalError, withinScope, DATA_SUBJECT_RULE, type Personal } from "../src/personal.ts"
import { currentKeyring, followNotices, installKeyring, Keyring, type KeyReply, type SubjectNotice } from "../src/keyring.ts"
import { LocalDate } from "../src/time.ts"
import { decodePayload, encodePayload, prefetchPayload, prefetchValue } from "../src/server/payloads.ts"
import { Ankka } from "../src/service.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { command, query } from "../src/handlers.ts"
import { ErasureCallSchema } from "../src/_proto/ankka/protocol/v1/erasure_pb.ts"
import { invokeErasure } from "../src/server/erasure.ts"
import { ComponentClient } from "../src/client.ts"
import { InMemoryBuckets } from "../src/storage.ts"
import { Duration } from "../src/time.ts"
import { Conversation, failureOf, payload, replyOf, startServer } from "./helpers.ts"

const here = dirname(fileURLToPath(import.meta.url))
const copied = resolve(here, "..", "proto", "fixtures", "personal")
const original = resolve(here, "..", "..", "..", "protocol", "fixtures", "personal")
const FIXTURES = existsSync(copied) ? copied : original
const fixture = (name: string) => JSON.parse(readFileSync(join(FIXTURES, name), "utf8"))

const b64 = (text: string) => new Uint8Array(Buffer.from(text, "base64"))
const text = (bytes: Uint8Array) => new TextDecoder().decode(bytes)

/** A keyring answering from a table as the sidecar of a service in `project` would, counting what it was asked. */
function keyringOf(project: string, keys: Record<string, Uint8Array | "erased">): { keyring: Keyring; asked: string[] } {
  const asked: string[] = []
  const keyring = new Keyring({
    fetch: async (p, about, create): Promise<KeyReply> => {
      asked.push(`${p || "(own)"}:${about}:${create}`)
      const k = keys[about]
      if (k === undefined) throw new Error(`no key for ${about}`)
      return { project: p || project, answer: k === "erased" ? { kind: "erased" } : { kind: "key", key: k } }
    },
  })
  return { keyring, asked }
}

/** Runs `body` with `keyring` installed, restoring what was installed before. */
async function withKeyring<T>(keyring: Keyring | undefined, body: () => Promise<T> | T): Promise<T> {
  const before = currentKeyring()
  installKeyring(keyring)
  try {
    return await body()
  } finally {
    installKeyring(before)
  }
}

// The value shapes the envelope cases seal, by their plaintext.
const Address = s.record("Address", { line1: s.string, city: s.string })
function shapeOf(plaintext: string): { inner: Schema; value: unknown } {
  const raw = JSON.parse(plaintext)
  if (typeof raw === "object") return { inner: Address, value: raw }
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return { inner: s.localDate, value: LocalDate.parse(raw) }
  return { inner: s.string, value: raw }
}

/** A field written under a serializer's scope, with the nonce fixed. */
const Holder = (inner: Schema) => s.record("Holder", { field: s.personal(inner) })
function sealHolder(inner: Schema, value: Personal<unknown>, manifest: string, nonce?: Uint8Array): string {
  return withinScope(manifest, () => writeJson(Holder(inner), { field: value }), nonce)
}
function openHolder(inner: Schema, json: string, manifest: string): Personal<unknown> {
  return withinScope(manifest, () => readJson(Holder(inner), json) as { field: Personal<unknown> }).field
}

// ── subjects ──────────────────────────────────────────────────────────────────

test("subjects: every valid subject is accepted and every invalid one refused with the shared message", () => {
  const f = fixture("subjects.json")
  assert.equal(f.message, DATA_SUBJECT_RULE)
  for (const v of f.valid) assert.equal(subject(v), v)
  for (const v of f.invalid) assert.throws(() => subject(v), { name: "TypeError", message: `the value given is not a data subject: ${f.message}` })
})

test("a refused subject is not repeated in the refusal", () => {
  // A caller who passes an email as the subject must not find the email in an error message.
  assert.throws(() => subject("ada@example.com"), (e: unknown) => e instanceof TypeError && !e.message.includes("ada@example.com"))
})

// ── the envelope, byte for byte ───────────────────────────────────────────────

for (const c of fixture("envelopes.json").cases) {
  test(`envelope: ${c.name} is written byte for byte and read back`, async () => {
    const { inner, value } = shapeOf(c.plaintext)
    const { keyring } = keyringOf(c.project, { [c.subject]: b64(c.key) })
    await withKeyring(keyring, async () => {
      const field = personal(subject(c.subject), value)
      await prefetchValue({ field })
      const written = sealHolder(inner, field, c.manifest, b64(c.nonce))
      assert.equal(written, `{"field":${JSON.stringify(c.envelope)}}`)
      // And read back, from the fixture's own bytes.
      await keyring.prefetchJson(written)
      const read = openHolder(inner, written, c.manifest)
      assert.equal(read.kind, "present")
      assert.deepEqual((read as Present<unknown>).value, value)
    })
  })
}

test("envelope: a random nonce is fresh on every write, and both open", async () => {
  const c = fixture("envelopes.json").cases[0]
  const { keyring } = keyringOf(c.project, { [c.subject]: b64(c.key) })
  await withKeyring(keyring, async () => {
    const field = personal(subject(c.subject), "ada@example.com")
    await prefetchValue(field)
    const one = sealHolder(s.string, field, c.manifest)
    const two = sealHolder(s.string, field, c.manifest)
    assert.notEqual(one, two)
    assert.equal(openHolder(s.string, one, c.manifest).orElse("?"), "ada@example.com")
    assert.equal(openHolder(s.string, two, c.manifest).orElse("?"), "ada@example.com")
    assert.ok(!one.includes("ada@example.com"))
  })
})

test("envelope: the erased form reads as erased with no key asked, and an erased field writes it", async () => {
  const e = fixture("envelopes.json").erased[0]
  const { keyring, asked } = keyringOf(e.project, { [e.subject]: "erased" })
  await withKeyring(keyring, async () => {
    const json = `{"field":${JSON.stringify(e.envelope)}}`
    await keyring.prefetchJson(json)
    const read = openHolder(s.string, json, "player-registered")
    assert.ok(read instanceof Erased)
    assert.equal(read.subject, e.subject)
    assert.deepEqual(asked, [])
    // The field read is written again under the project its envelope named (FR-027), asking nothing.
    assert.equal(sealHolder(s.string, read, "player-registered"), json)
    assert.deepEqual(asked, [])
    // One made here, before any answer has named the own project: Unavailable. A prefetch asks, without create.
    const made = new Erased(subject(e.subject))
    assert.throws(() => sealHolder(s.string, made, "player-registered"), (x: unknown) => x instanceof PersonalError && x.reason === "unavailable")
    await prefetchValue(made)
    assert.deepEqual(asked, [`(own):${e.subject}:false`])
    assert.equal(sealHolder(s.string, made, "player-registered"), json)
  })
})

for (const c of fixture("envelopes.json").corrupt) {
  test(`envelope: ${c.name} is corrupt, never erased`, async () => {
    const { keyring } = keyringOf(c.envelope.project, { [c.envelope.subject]: b64(c.key) })
    await withKeyring(keyring, async () => {
      const json = `{"field":${JSON.stringify(c.envelope)}}`
      await keyring.prefetchJson(json)
      assert.throws(() => openHolder(s.string, json, c.manifest), (e: unknown) => {
        assert.ok(e instanceof PersonalError)
        assert.equal(e.reason, "corrupt")
        assert.equal(e.code, "INTERNAL")
        assert.equal(e.message, `the personal envelope of '${c.envelope.subject}' is corrupt: it failed authentication`)
        return true
      })
    })
  })
}

test("envelope: an unknown version is corrupt", async () => {
  const env = fixture("envelopes.json")["unknown-version"]
  const { keyring } = keyringOf("brand", {})
  await withKeyring(keyring, () => {
    assert.throws(() => openHolder(s.string, `{"field":${JSON.stringify(env)}}`, "m"), (e: unknown) => e instanceof PersonalError && e.reason === "corrupt")
  })
})

// ── erased subjects and the errors ────────────────────────────────────────────

test("an erased subject decodes as erased and never throws; a write for it throws Erased", async () => {
  const c = fixture("envelopes.json").cases[0]
  const { keyring } = keyringOf(c.project, { [c.subject]: "erased" })
  await withKeyring(keyring, async () => {
    const json = `{"field":${JSON.stringify(c.envelope)}}`
    await keyring.prefetchJson(json)
    const read = openHolder(s.string, json, c.manifest)
    assert.equal(read.kind, "erased")
    assert.equal(read.orElse("gone"), "gone")

    const field = personal(subject(c.subject), "ada@example.com")
    await prefetchValue(field)
    assert.throws(() => sealHolder(s.string, field, c.manifest), (e: unknown) => {
      assert.ok(e instanceof PersonalError)
      assert.equal(e.reason, "erased")
      assert.equal(e.code, "CONFLICT")
      assert.equal(e.message, "the data subject 'player/8c1f' is erased; nothing was written")
      return true
    })
  })
})

test("a read's erased answer does not refuse a later write: the write asks again with create", async () => {
  let made = false
  const key = b64(fixture("envelopes.json").cases[0].key)
  const keyring = new Keyring({
    fetch: async (p, _s, create): Promise<KeyReply> => ({
      project: p || "brand",
      answer: create ? ((made = true), { kind: "key", key }) : made ? { kind: "key", key } : { kind: "erased" },
    }),
  })
  await withKeyring(keyring, async () => {
    await keyring.prefetchJson(`{"$personal":1,"project":"brand","subject":"player/new","c":"AAAA"}`)
    const field = personal(subject("player/new"), "x")
    await prefetchValue(field)
    assert.ok(sealHolder(s.string, field, "m").includes('"c":'))
  })
})

test("no keyring is Unavailable on a write; a key not fetched is Unavailable too", async () => {
  await withKeyring(undefined, () => {
    assert.throws(() => sealHolder(s.string, personal(subject("player/8c1f"), "x"), "m"), (e: unknown) => {
      assert.ok(e instanceof PersonalError)
      assert.equal(e.reason, "unavailable")
      assert.equal(e.code, "UNAVAILABLE")
      assert.match(e.message, /^the keyring is unavailable/)
      return true
    })
  })
  const { keyring } = keyringOf("brand", {})
  await withKeyring(keyring, async () => {
    const field = personal(subject("player/8c1f"), "x")
    await prefetchValue(field) // the fetch fails; the codec says why
    assert.throws(() => sealHolder(s.string, field, "m"), (e: unknown) => e instanceof PersonalError && e.reason === "unavailable" && /no key for player\/8c1f/.test(e.message))
  })
})

// ── the keyring: prefetch, cache, notices ─────────────────────────────────────

test("keyring: subjects are prefetched from the JSON and the value, once each, then cached", async () => {
  const c = fixture("envelopes.json").cases[0]
  const r = fixture("envelopes.json").cases[2]
  const { keyring, asked } = keyringOf("brand", { [c.subject]: b64(c.key), [r.subject]: b64(r.key), "player/41d2": b64(c.key) })
  // The first answer for the own project names it.
  assert.equal(keyring.project, undefined)
  await keyring.prefetchValue({ other: personal(subject("player/41d2"), 1) })
  assert.equal(keyring.project, "brand")
  assert.deepEqual(asked, ["(own):player/41d2:true"])
  const json = JSON.stringify({ a: c.envelope, nested: [{ b: r.envelope }], again: c.envelope })
  await keyring.prefetchJson(json)
  await keyring.prefetchJson(json)
  assert.deepEqual(asked.slice(1).sort(), ["(own):player/8c1f:false", "payments:cardholder/77a0:false"])
  await keyring.prefetchValue({ deep: [new Map([["k", personal(subject("player/8c1f"), 1)]])] })
  // A key already held for the own project serves a write without asking again.
  assert.equal(asked.length, 3)
})

test("keyring: a notice drops the cached key, then acks; the next read asks again", async () => {
  const c = fixture("envelopes.json").cases[0]
  const { keyring, asked } = keyringOf("brand", { [c.subject]: b64(c.key) })
  const json = JSON.stringify(c.envelope)
  await keyring.prefetchJson(json)
  assert.ok(keyring.holds("brand", c.subject))
  const acked: bigint[] = []
  const stop = new AbortController()
  const notices = async function* (): AsyncIterable<SubjectNotice> {
    yield { seq: 7n, project: "brand", subject: c.subject, rerun: false }
  }
  await followNotices(
    keyring,
    () => notices(),
    async (seq) => {
      // The key is gone before the ack, which is what the runtime waits for.
      assert.equal(keyring.holds("brand", c.subject), false)
      acked.push(seq)
      stop.abort()
    },
    stop.signal,
  )
  assert.deepEqual(acked, [7n])
  await keyring.prefetchJson(json)
  assert.equal(asked.length, 2)
})

// ── the textual form ──────────────────────────────────────────────────────────

test("the textual form names the subject and never the value", () => {
  const p = personal(subject("player/8c1f"), "ada@example.com")
  assert.equal(String(p), "Personal(player/8c1f)")
  assert.equal(`${p}`, "Personal(player/8c1f)")
  assert.ok(!inspect({ email: p }).includes("ada@example.com"))
  assert.ok(inspect({ email: p }).includes("Personal(player/8c1f)"))
})

test("JSON.stringify of a value holding a personal field prints the subject and never the value", () => {
  const event = { type: "Registered", email: personal(subject("player/8c1f"), "ada@example.com") }
  const text = JSON.stringify(event)
  assert.ok(!text.includes("ada@example.com"), text)
  assert.equal(text, `{"type":"Registered","email":{"subject":"player/8c1f"}}`)
  assert.equal(JSON.stringify(new Erased(subject("player/8c1f"))), `{"subject":"player/8c1f"}`)
})

test("a tool's result holding a personal field reaches the model without the value", async () => {
  const { runTool } = await import("../src/server/agent.ts")
  const { tool } = await import("../src/handlers.ts")
  const lookup = tool("lookup", "Looks up a player.", s.string, () => ({ email: personal(subject("player/8c1f"), "ada@example.com") }))
  const registered = { tools: new Map([["lookup", lookup]]) } as unknown as Parameters<typeof runTool>[0]
  const text = await runTool(registered, {} as Parameters<typeof runTool>[1], "lookup", `"x"`)
  assert.ok(!text.includes("ada@example.com"), text)
  assert.ok(text.includes("player/8c1f"), text)
})

// ── the edge ──────────────────────────────────────────────────────────────────

for (const c of fixture("edge.json").cases) {
  test(`edge: ${c.name}, both ways`, () => {
    const inner = typeof c.edge.value === "object" ? Address : s.string
    const shape = s.personal(inner as Schema<unknown>)
    const value = "value" in c ? personal(subject(c.subject), c.value) : new Erased(subject(c.subject))
    // An endpoint's reply: the codec outside any serializer's scope.
    const written = text(codecFor(Holder(inner)).encode({ field: value }))
    assert.equal(written, JSON.stringify({ field: c.edge }))
    const read = readJson(shape, JSON.stringify(c.edge))
    assert.equal(read.kind, "value" in c ? "present" : "erased")
    assert.equal(read.subject, c.subject)
    if (read.kind === "present") assert.deepEqual(read.value, c.value)
  })
}

test("edge: a body naming no subject, or a bad one, is refused", () => {
  assert.throws(() => readJson(s.personal(s.string), `{"value":"x"}`), /names its data subject/)
  assert.throws(() => readJson(s.personal(s.string), `{"subject":"a b","value":"x"}`), /is not a data subject/)
})

test("toJsonSchema describes the edge form", () => {
  assert.deepEqual(toJsonSchema(s.personal(s.string)), {
    type: "object",
    properties: { subject: { type: "string", pattern: "^[A-Za-z0-9._/:-]{1,253}$" }, value: { type: "string" } },
    required: ["subject"],
    additionalProperties: false,
  })
})

// ── an entity whose events hold a personal field ──────────────────────────────

const PlayerEvent = s.sumType("PlayerEvent", { Registered: { playerId: s.string, email: s.personal(s.string) } })
type PlayerEvent = typeof PlayerEvent extends { _type?: infer T } ? T : never
const PlayerState = s.record("PlayerState", { email: s.option(s.personal(s.string)) })
type PlayerState = typeof PlayerState extends { _type?: infer T } ? T : never

function replyPayload(reply: ReturnType<typeof replyOf>) {
  const o = reply.outcome?.outcome
  if (o?.case !== "reply") throw new Error(`expected a reply, got ${o?.case}`)
  return o.value.payload
}

class Player extends EventSourcedEntity<PlayerState, PlayerEvent> {
  static readonly componentId = "player"
  static readonly state = jsonCodec(PlayerState, "player-state")
  static readonly events = jsonCodec(PlayerEvent, "player-event")
  static readonly handlers = {
    register: command("register", s.string, s.string, (p: Player, email) =>
      p.effects.persist({ type: "Registered", playerId: p.entityId, email: personal(subject(`player/${p.entityId}`), email) }).thenReply(() => "ok"),
    ),
    email: query("email", s.string, (p: Player) => p.effects.reply(p.state.email?.orElse("(erased)") ?? "(none)")),
  }
  emptyState(): PlayerState {
    return { email: null }
  }
  applyEvent(_s: PlayerState, e: PlayerEvent): PlayerState {
    return { email: e.email }
  }
}

test("an entity: the journal holds the envelope, a replay reads the value, an erased subject replays as erased", async () => {
  const key = b64(fixture("envelopes.json").cases[0].key)
  const answers: Record<string, Uint8Array | "erased"> = { "player/8c1f": key }
  const { keyring } = keyringOf("brand", answers)
  const started = await startServer(Ankka.service({ client: new ComponentClient("127.0.0.1:1") }).register(Player))
  installKeyring(keyring) // in place of the one the server dials its sidecar for
  try {
    const one = new Conversation(started)
    one.init("player", "8c1f")
    const reply = replyOf(await one.call("register", payload(s.string, "ada@example.com")))
    const event = reply.events[0]!
    assert.equal(event.manifest, "player-event")
    const journaled = text(event.data)
    assert.ok(journaled.includes('"$personal":1') && journaled.includes('"project":"brand"'), journaled)
    assert.ok(!journaled.includes("ada@example.com"))
    one.close()

    const two = new Conversation(started)
    two.init("player", "8c1f")
    two.event(1n, event)
    const replayed = replyOf(await two.call("email"))
    assert.equal(decodePayload(codecFor(s.string), replyPayload(replayed)), "ada@example.com")
    two.close()

    // The subject is erased: the sidecar's notice drops the key, the keyring answers erased.
    keyring.drop("brand", "player/8c1f")
    answers["player/8c1f"] = "erased"
    const three = new Conversation(started)
    three.init("player", "8c1f")
    three.event(1n, event)
    const after = replyOf(await three.call("email"))
    assert.equal(after.outcome?.outcome.case, "reply")
    assert.equal(decodePayload(codecFor(s.string), replyPayload(after)), "(erased)")
    // A write for the erased subject is refused, CONFLICT, and nothing is persisted.
    const refused = failureOf(await three.call("register", payload(s.string, "again@example.com")))
    assert.match(refused.error!.message, /the data subject 'player\/8c1f' is erased; nothing was written/)
    three.close()
  } finally {
    await started.stop()
  }
})

test("the project is learned from the sidecar: a fake sidecar answering 'brand' gets envelopes naming 'brand'", async () => {
  const key = b64(fixture("envelopes.json").cases[0].key)
  const asked: SubjectKeyRequest[] = []
  const sessions = new Set<ServerHttp2Session>()
  const sidecar = createServer(
    connectNodeAdapter({
      routes: (router) =>
        router.service(Client, {
          async getSubjectKey(req) {
            asked.push(req)
            return create(SubjectKeyReplySchema, { reply: { case: "key", value: key }, project: req.project || "brand" })
          },
        }),
    }),
  )
  sidecar.on("session", (session) => {
    sessions.add(session)
    session.once("close", () => sessions.delete(session))
  })
  await new Promise<void>((r) => sidecar.listen(0, "127.0.0.1", () => r()))
  const client = new ComponentClient(`127.0.0.1:${(sidecar.address() as AddressInfo).port}`)
  const started = await startServer(Ankka.service({ client }).register(Player))
  try {
    assert.equal(currentKeyring()?.project, undefined)
    const one = new Conversation(started)
    one.init("player", "8c1f")
    const event = replyOf(await one.call("register", payload(s.string, "ada@example.com"))).events[0]!
    one.close()
    // The write asked for the own project with `project` empty, and the answer named it.
    assert.deepEqual(asked.map((r) => [r.project, r.subject, r.create]), [["", "player/8c1f", true]])
    assert.equal(currentKeyring()?.project, "brand")
    const envelope = JSON.parse(text(event.data)).email
    assert.equal(envelope.project, "brand")
    // The associated data binds 'brand' too: sealed under any other project, this does not open.
    const opened = openHolder(s.string, JSON.stringify({ field: envelope }), "player-event")
    assert.equal((opened as Present<string>).value, "ada@example.com")
    const moved = { ...envelope, project: "other" }
    await currentKeyring()!.prefetchJson(JSON.stringify(moved))
    assert.throws(() => openHolder(s.string, JSON.stringify({ field: moved }), "player-event"), (e: unknown) => e instanceof PersonalError && e.reason === "corrupt")
  } finally {
    await started.stop()
    for (const session of sessions) session.destroy()
    await new Promise<void>((r) => sidecar.close(() => r()))
  }
})

test("a payload round trip binds the manifest: read under another manifest it is corrupt", async () => {
  const c = fixture("envelopes.json").cases[0]
  const { keyring } = keyringOf("brand", { [c.subject]: b64(c.key) })
  await withKeyring(keyring, async () => {
    const codec = jsonCodec(Holder(s.string), "player-registered")
    const value = { field: personal(subject(c.subject), "ada@example.com") }
    await prefetchValue(value)
    const p = encodePayload(codec, value)
    await prefetchPayload(p)
    assert.equal((decodePayload(codec, p).field as Present<string>).value, "ada@example.com")
    assert.throws(() => decodePayload(jsonCodec(Holder(s.string), "other"), p), (e: unknown) => e instanceof PersonalError && e.reason === "corrupt")
  })
})

// ── discovery and the erasure handler ────────────────────────────────────────

test("a discovery forgets every key the process holds: the sidecar may be new, and its notice lost", async () => {
  const key = b64(fixture("envelopes.json").cases[0].key)
  const sessions = new Set<ServerHttp2Session>()
  let following: () => void = () => {}
  const followed = new Promise<void>((r) => (following = r))
  const sidecar = createServer(
    connectNodeAdapter({
      routes: (router) =>
        router.service(Client, {
          async getSubjectKey(req) {
            return create(SubjectKeyReplySchema, { reply: { case: "key", value: key }, project: req.project || "brand" })
          },
          // Held open and silent: the notice the sidecar that stopped meant to send never comes.
          async *subjectNotices(_req, ctx) {
            following()
            await new Promise<void>((r) => ctx.signal.addEventListener("abort", () => r()))
          },
        }),
    }),
  )
  sidecar.on("session", (session) => {
    sessions.add(session)
    session.once("close", () => sessions.delete(session))
  })
  await new Promise<void>((r) => sidecar.listen(0, "127.0.0.1", () => r()))
  const client = new ComponentClient(`127.0.0.1:${(sidecar.address() as AddressInfo).port}`)
  const started = await startServer(Ankka.service({ client }).register(Player))
  try {
    // The first discovery starts following notices, whose opening forgets everything anyway.
    await started.discovery.discover({ protocolVersion: "1.23", runtimeVersion: "t" })
    await followed
    const one = new Conversation(started)
    one.init("player", "8c1f")
    replyOf(await one.call("register", payload(s.string, "ada@example.com")))
    one.close()
    assert.ok(currentKeyring()!.holds("brand", "player/8c1f"))
    // A sidecar discovering the process again, its notice stream still open as far as it knows.
    await started.discovery.discover({ protocolVersion: "1.23", runtimeVersion: "t" })
    assert.ok(!currentKeyring()!.holds("brand", "player/8c1f"), "a key held before the discovery is still held")
  } finally {
    await started.stop()
    for (const session of sessions) session.destroy()
    await new Promise<void>((r) => sidecar.close(() => r()))
  }
})

test("discovery: a runtime below 1.23 is refused when the service uses personal fields or a handler", async () => {
  const withFields = await startServer(Ankka.service({ client: new ComponentClient("127.0.0.1:1") }).register(Player))
  try {
    await assert.rejects(withFields.discovery.discover({ protocolVersion: "1.22", runtimeVersion: "t" }), /personal fields need protocol 1\.23.*\(player\)/)
    const spec = await withFields.discovery.discover({ protocolVersion: "1.23", runtimeVersion: "t" })
    assert.equal(spec.erasureHandler, false)
  } finally {
    await withFields.stop()
  }
  const withHandler = await startServer(Ankka.service({ client: new ComponentClient("127.0.0.1:1") }).erasureHandler(async () => ({})))
  try {
    await assert.rejects(withHandler.discovery.discover({ protocolVersion: "1.22", runtimeVersion: "t" }), /personal fields need protocol 1\.23.*the erasure handler/)
    assert.equal((await withHandler.discovery.discover({ protocolVersion: "1.23", runtimeVersion: "t" })).erasureHandler, true)
  } finally {
    await withHandler.stop()
  }
})

test("a second erasure handler is refused at registration", () => {
  assert.throws(() => Ankka.service({ client: new ComponentClient("127.0.0.1:1") }).erasureHandler(async () => {}).erasureHandler(async () => {}).validate(), /one erasure handler/)
})

test("the erasure handler runs with the subject and reports; a throw is an error in the reply", async () => {
  const seen: string[] = []
  const ctx = { registry: Ankka.service().validate(), client: new ComponentClient("127.0.0.1:1"), log: () => {}, storage: new InMemoryBuckets() }
  const call = create(ErasureCallSchema, { subject: "player/8c1f", erasureSeq: 12n, rerun: true })
  const ok = await invokeErasure(
    async (c, about) => {
      seen.push(`${about}:${c.erasureSeq}:${c.rerun}`)
      return { objectsErased: 3, finality: { kind: "after", after: Duration.ofHours(168) } }
    },
    call,
    ctx,
  )
  assert.deepEqual(seen, ["player/8c1f:12:true"])
  assert.equal(ok.objectsErased, 3n)
  assert.equal(ok.finality, "after:PT168H")
  assert.equal(ok.error, undefined)
  const bad = await invokeErasure(async () => {
    throw new Error("bucket unreachable")
  }, call, ctx)
  assert.equal(bad.error?.message, "bucket unreachable")
})

