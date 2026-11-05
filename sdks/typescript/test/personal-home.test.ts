// FR-032 (feature 042): a replicated entity's keys are its home's. A copy at a follower tracks the
// home from Init.home, each event's installation and Command.home, asks for every key it decodes or
// seals with that home named, never asks for one to be made there, and keeps another home's keys apart
// from its own installation's. A home's keyring that cannot be reached fails the copy's read; it is
// never read as erased.

import { test } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { query } from "../src/handlers.ts"
import { codecFor, jsonCodec } from "../src/codec.ts"
import { s } from "../src/schema.ts"
import { Ankka } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { handleEventSourced } from "../src/server/eventSourced.ts"
import { decodePayload, encodePayload, prefetchValueAt } from "../src/server/payloads.ts"
import { currentKeyring, installKeyring, Keyring, type KeyReply } from "../src/keyring.ts"
import { personal, subject, Present } from "../src/personal.ts"
import { EventSourcedInSchema, type EventSourcedIn, type EventSourcedOut } from "../src/_proto/ankka/protocol/v1/event_sourced_pb.ts"

const Joined = s.record("Joined", { email: s.personal(s.string) })
type Joined = typeof Joined extends { _type?: infer T } ? T : never
const Member = s.record("Member", { email: s.option(s.personal(s.string)) })
type Member = typeof Member extends { _type?: infer T } ? T : never

class MemberEntity extends EventSourcedEntity<Member, Joined> {
  static readonly componentId = "member"
  static readonly replicated = true
  static readonly state = jsonCodec(Member, "member")
  static readonly events = jsonCodec(Joined, "joined")
  static readonly handlers = {
    email: query("email", s.string, (m: MemberEntity) => m.effects.reply(m.state.email?.orElse("erased") ?? "none")),
  }
  emptyState(): Member {
    return { email: null }
  }
  applyEvent(_s: Member, e: Joined): Member {
    return { email: e.email }
  }
}

const SUBJECT = "player/8c1f"

interface Asked {
  readonly project: string
  readonly subject: string
  readonly create: boolean
  readonly home: string
}

/**
 * The sidecar beside a follower at "b" of an entity whose home is "a": "a" keeps the subject's key, and
 * "b"'s own keyring a different key of the same subject. A home listed in `down` cannot be reached.
 */
function follower(clock = { now: 1_000_000 }) {
  const keys = new Map<string, Uint8Array>([
    [`a|club:${SUBJECT}`, new Uint8Array(32).fill(1)],
    [`|club:${SUBJECT}`, new Uint8Array(32).fill(2)],
  ])
  const down = new Set<string>()
  const asked: Asked[] = []
  const keyring = new Keyring({
    clock: () => clock.now,
    fetch: async (p, about, create, home = ""): Promise<KeyReply> => {
      asked.push({ project: p, subject: about, create, home })
      if (down.has(home)) throw new Error(`the keyring of installation '${home}' cannot be reached`)
      const project = p || "club"
      const at = `${home}|${project}:${about}`
      let key = keys.get(at)
      if (key === undefined && create) keys.set(at, (key = new Uint8Array(32).fill(9)))
      return { project, answer: key === undefined ? { kind: "erased" } : { kind: "key", key } }
    },
  })
  return { keys, down, asked, keyring, clock }
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

const events = codecFor(MemberEntity.events)
const states = codecFor(MemberEntity.state)

/** The event as the home wrote it, sealed under the home's key. */
async function joinedAtHome(k: Keyring) {
  const event: Joined = { email: personal(subject(SUBJECT), "ada@example.com") }
  await prefetchValueAt("a", event)
  const payload = encodePayload(events, event, "a")
  k.dropAll() // nothing the writing held is kept: the follower asks for what it reads
  return payload
}

async function stream(messages: EventSourcedIn[]): Promise<EventSourcedOut[]> {
  const registry = Ankka.service({ client: noClient(), log: () => {} }).register(MemberEntity).validate()
  async function* feed() {
    for (const m of messages) yield m
  }
  const out: EventSourcedOut[] = []
  for await (const m of handleEventSourced(feed(), { registry, client: noClient(), log: () => {} })) out.push(m)
  return out
}

const init = (home = "") => create(EventSourcedInSchema, { message: { case: "init", value: { componentId: "member", entityId: "m1", installation: "b", home } } })
const event = (payload: ReturnType<typeof encodePayload>) =>
  create(EventSourcedInSchema, { message: { case: "event", value: { sequence: 1n, payload, installation: "a" } } })
const ask = () => create(EventSourcedInSchema, { message: { case: "command", value: { id: 1n, name: "email", installation: "b", atHome: false, home: "a", snapshotRequested: true } } })

function replyOf(out: EventSourcedOut[]) {
  const m = out[0]?.message
  if (m?.case !== "reply") throw new Error(`expected a reply, got ${m?.case}: ${m?.case === "failure" ? m.value.error?.message : ""}`)
  return m.value
}

test("a follower reads and writes with its home's keys and makes none", async () => {
  const f = follower()
  await f.keyring.prefetchValue(personal(subject("player/0"), "x")) // learns its own project
  await under(f.keyring, async () => {
    const joined = await joinedAtHome(f.keyring)
    f.asked.length = 0
    const reply = replyOf(await stream([init(), event(joined), ask()]))
    const o = reply.outcome?.outcome
    assert.equal(o?.case === "reply" ? decodePayload(codecFor(s.string), o.value.payload) : o?.case, "ada@example.com")
    assert.ok(f.asked.length > 0, "no key was asked for")
    for (const a of f.asked) assert.deepEqual([a.home, a.create], ["a", false], `asked ${JSON.stringify(a)}`)
    assert.equal(f.keys.size, 3, `a key was made: ${[...f.keys.keys()]}`) // player/0, and the subject's at "a" and at "b"

    // Recovered again from the snapshot the follower wrote: Init.home names whose keys it is.
    f.keyring.dropAll()
    const snapshot = reply.snapshot!
    const fromSnapshot = replyOf(
      await stream([
        create(EventSourcedInSchema, { message: { case: "init", value: { componentId: "member", entityId: "m1", installation: "b", home: "a", snapshot: { sequence: 1n, payload: snapshot } } } }),
        ask(),
      ]),
    )
    const s2 = fromSnapshot.outcome?.outcome
    assert.equal(s2?.case === "reply" ? decodePayload(codecFor(s.string), s2.value.payload) : s2?.case, "ada@example.com")

    // A subject the home holds no key for, written with the home's keys: no key is made, there or here.
    const fresh: Member = { email: personal(subject("player/new"), "x") }
    await prefetchValueAt("a", fresh)
    assert.throws(() => encodePayload(states, fresh, "a"))
    assert.ok(!f.keys.has("a|club:player/new") && !f.keys.has("|club:player/new"), "a key was made for a follower's write")
  })
})

test("two homes' keys of one subject never mix", async () => {
  const f = follower()
  await f.keyring.prefetchValue(personal(subject("player/0"), "x"))
  await under(f.keyring, async () => {
    const joined = await joinedAtHome(f.keyring)
    await f.keyring.prefetchJson(`{"$personal":1,"project":"club","subject":"${SUBJECT}","c":"AAAA"}`) // the own key, held first
    assert.ok(f.keyring.holds("club", SUBJECT))
    const reply = replyOf(await stream([init(), event(joined), ask()]))
    const o = reply.outcome?.outcome
    assert.equal(o?.case === "reply" ? decodePayload(codecFor(s.string), o.value.payload) : o?.case, "ada@example.com")
    const read = decodePayload(events, joined, "a")
    assert.ok(read.email instanceof Present)
  })
})

test("a home that cannot be reached fails the read and is never erased", async () => {
  const f = follower()
  await f.keyring.prefetchValue(personal(subject("player/0"), "x"))
  await under(f.keyring, async () => {
    const joined = await joinedAtHome(f.keyring)
    f.down.add("a")
    const out = await stream([init(), event(joined), ask()])
    const m = out[0]?.message
    assert.equal(m?.case, "failure", "a recovery with the home unreachable answered")
    assert.match(m?.case === "failure" ? m.value.error!.message : "", /unavailable|cannot be reached/i)

    // Recovered while the home answers, then the home goes past the held key's life: the command
    // fails, unavailable, rather than reading the field erased.
    f.down.delete("a")
    async function* feed() {
      yield init()
      yield event(joined)
      f.down.add("a")
      f.clock.now += 31_000
      yield ask()
    }
    const registry = Ankka.service({ client: noClient(), log: () => {} }).register(MemberEntity).validate()
    const later: EventSourcedOut[] = []
    for await (const r of handleEventSourced(feed(), { registry, client: noClient(), log: () => {} })) later.push(r)
    const l = later[0]?.message
    assert.equal(l?.case, "failure", `a command with the home unreachable answered ${l?.case}`)
    assert.match(l?.case === "failure" ? l.value.error!.message : "", /unavailable/i)
  })
})
