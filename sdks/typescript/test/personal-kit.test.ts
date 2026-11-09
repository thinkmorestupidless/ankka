// The integration kit's keyring and personal value assertion (feature 042, T071): a personal field
// written through the real sidecar is in no table as written, an erasure through `kit.keyring` reads it
// back as erased, and an outage refuses a key the sidecar does not hold. Needs Docker and the
// `ankka-sidecar` image (ANKKA_SIDECAR_IMAGE names this branch's).
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { AnkkaTestKit, personalValueFailure, personalValueQuery } from "../src/testkit/integration.ts"
import { s } from "../src/schema.ts"
import { jsonCodec } from "../src/codec.ts"
import { personal, subject } from "../src/personal.ts"
import { Ankka } from "../src/service.ts"
import { EventSourcedEntity } from "../src/eventSourcedEntity.ts"
import { command, query } from "../src/handlers.ts"
import { CommandError, ErrorCode } from "../src/effects/common.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

const MemberEvent = s.sumType("MemberEvent", { Joined: { email: s.personal(s.string) } })
type MemberEvent = typeof MemberEvent extends { _type?: infer T } ? T : never
const MemberState = s.record("MemberState", { email: s.option(s.personal(s.string)) })
type MemberState = typeof MemberState extends { _type?: infer T } ? T : never

class Member extends EventSourcedEntity<MemberState, MemberEvent> {
  static readonly componentId = "member"
  static readonly state = jsonCodec(MemberState, "member-state")
  static readonly events = jsonCodec(MemberEvent, "member-event")
  static readonly handlers = {
    join: command("join", s.string, s.string, (m: Member, email) =>
      m.effects.persist({ type: "Joined", email: personal(subject(`member/${m.entityId}`), email) }).thenReply(() => "joined"),
    ),
    email: query("email", s.string, (m: Member) => m.effects.reply(m.state.email?.orElse("erased") ?? "none")),
  }
  emptyState(): MemberState {
    return { email: null }
  }
  applyEvent(_s: MemberState, e: MemberEvent): MemberState {
    return { email: e.email }
  }
}

test("the query names each form of each value, and the failure each place", () => {
  const sql = personalValueQuery(["ada@example.com", "o'neil"])
  // The JVM kit's forms: unpadded base64 and base64url, lower-case hex; quotes doubled.
  assert.ok(sql.includes("'YWRhQGV4YW1wbGUuY29t'") && sql.includes("'616461406578616d706c652e636f6d'"), sql)
  assert.ok(sql.includes("'o''neil'"), sql)
  assert.equal(
    personalValueFailure(["ada@example.com"], "0 base64 public.event_journal.event_payload\n"),
    "a personal value can be read back from what the service kept:\n  'ada@example.com' as base64 in public.event_journal.event_payload",
  )
  assert.equal(personalValueFailure(["ada@example.com"], ""), undefined)
})

describe("the integration kit's keyring", { skip: slow }, () => {
  // docs:start keyring
  test("a personal field is sealed, erased and refused in an outage through the sidecar", async () => {
    const kit = await AnkkaTestKit.start(Ankka.service().register(Member))
    try {
      const member = (id: string) => kit.client.of(Member, id)
      assert.equal(await member("8c1f").call(Member.handlers.join).invoke("ada@example.com"), "joined")
      await kit.assertNoPersonalValue("ada@example.com")
      // The assertion can fail: the envelope names its data subject in the clear.
      await assert.rejects(kit.assertNoPersonalValue("member/8c1f"), /event_journal/)

      // An outage refuses a key the sidecar has not fetched, and an erasure, as unavailable.
      await kit.keyring.outage(600)
      await assert.rejects(member("9d2e").call(Member.handlers.join).invoke("grace@example.com"), (e: unknown) => {
        assert.ok(e instanceof CommandError, String(e))
        assert.equal(e.code, ErrorCode.Unavailable, e.message)
        return true
      })
      await assert.rejects(kit.keyring.erase("member/8c1f"), /503/)
      await kit.keyring.recover()

      assert.ok((await kit.keyring.erase("member/8c1f")) >= 1)
      await kit.restart()
      assert.equal(await member("8c1f").call(Member.handlers.email).invoke(), "erased")
      await kit.assertNoPersonalValue("ada@example.com", "grace@example.com")
    } finally {
      await kit.stop()
    }
  })
  // docs:end keyring
})
