// The personal data guide's samples, run: the field is sealed and read back, the session and the
// instance carry their data subject, the handler erases exactly the subject's objects, and the
// endpoint's ask is sent with its reason and correlation.

import { test } from "node:test"
import assert from "node:assert/strict"
import { ComponentClient, Erasures, InMemoryBuckets, subject, subjectPrefix } from "ankka"
import { AccountsEndpoint, askAboutAPlayer, playersService, registered } from "./personalData.ts"

const utf8 = new TextEncoder()

test("a marked field is about its data subject, and never prints its value", () => {
  const event = registered("8c1f", "ada@example.com")
  assert.equal(event.email.kind, "present")
  assert.equal(event.email.subject, "player/8c1f")
  assert.equal(event.email.orElse("erased"), "ada@example.com")
  assert.equal(String(event.email), "Personal(player/8c1f)")
  assert.equal(JSON.stringify(event).includes("ada@example.com"), false)
  assert.throws(() => subject("player 8c1f"), /is not a data subject/)
})

test("a session and an instance started with a data subject name it on every call", async () => {
  const sent: { componentId: string; entityId: string; name: string; subject: string[]; body: string }[] = []
  const stub = {
    invoke: async (req: { componentId: string; entityId: string; name: string; metadata?: { entries: { key: string; value: string }[] }; payload?: { data: Uint8Array } }) => {
      sent.push({
        componentId: req.componentId,
        entityId: req.entityId,
        name: req.name,
        subject: (req.metadata?.entries ?? []).filter((e) => e.key === "ankka-data-subject").map((e) => e.value),
        body: new TextDecoder().decode(req.payload?.data ?? new Uint8Array()),
      })
      const data = req.name === "assign" ? "{}" : "we will look into it"
      return { result: { case: "reply", value: { payload: { contentType: "text/plain", manifest: "string", data: utf8.encode(data) } } } }
    },
  }
  await askAboutAPlayer(new ComponentClient("p", { address: "p", transport: undefined, stub: stub as never }))
  const [asked, assigned] = sent
  assert.deepEqual([asked!.componentId, asked!.entityId, asked!.name, asked!.subject], ["helper", "support-91", "ask", ["player/8c1f"]])
  assert.deepEqual([assigned!.componentId, assigned!.entityId], ["resolver", "case-91"])
  assert.deepEqual(JSON.parse(assigned!.body), { taskIds: ["t-1"], subject: "player/8c1f" })
})

test("the erasure handler erases the subject's objects and no other's, and again none", async () => {
  const handler = playersService(new ComponentClient("127.0.0.1:1")).validate().erasureHandler!
  const storage = new InMemoryBuckets({ buckets: { kyc: "private" } })
  const kyc = storage.bucket("kyc")
  await kyc.put(`${subjectPrefix("player/8c1f")}passport.jpg`, utf8.encode("x"), { contentType: "image/jpeg" })
  await kyc.put(`${subjectPrefix("player/9d2e")}passport.jpg`, utf8.encode("x"), { contentType: "image/jpeg" })
  const ctx = { storage } as never
  assert.deepEqual(await handler(ctx, subject("player/8c1f")), { objectsErased: 1, finality: { kind: "immediate" } })
  assert.equal((await handler(ctx, subject("player/8c1f")))?.objectsErased, 0)
})

test("an endpoint asks for an erasure with its reason and correlation", async () => {
  const asked: { subject: string; reason?: string; correlation?: string }[] = []
  const stub = {
    askErasure: async (req: { subject: string; reason?: string; correlation?: string }) => {
      asked.push(req)
      return { reply: { case: "ok", value: { id: "er-1", state: "held" } } }
    },
  }
  const endpoint = new AccountsEndpoint()
  endpoint.erasures = new Erasures(new ComponentClient("e", { address: "e", transport: undefined, stub: stub as never }))
  assert.equal(await endpoint.close(), "held")
  assert.deepEqual([asked[0]!.subject, asked[0]!.reason, asked[0]!.correlation], ["player/8c1f", "aml-retention", "closure-4411"])
})
