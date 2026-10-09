// The whole service through the real sidecar: Postgres and the sidecar image in Docker, this process
// serving the components to it, and an HTTP client for the routes. Seconds, not milliseconds.
//
// The sidecar is the one published with the SDK's version, pulled on first use; `$ANKKA_SIDECAR_IMAGE`
// names another. Needs Docker.
import { test } from "node:test"
import assert from "node:assert/strict"
import { AnkkaTestKit } from "ankka/testkit"
import { service } from "../src/main.ts"


/** A view is eventually consistent: retry until the answer is the one expected, then return it. */
async function jsonWhen(kit: AnkkaTestKit, path: string, ready: (body: unknown) => boolean, timeoutMs = 20_000): Promise<unknown> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const body = (await kit.http.get(path)).json()
    if (ready(body) || Date.now() > deadline) return body
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
}

test("an item survives a restart and is listed", async () => {
  const kit = await AnkkaTestKit.start(service())
  try {
    assert.ok((await kit.http.post("/items/i1", { name: "Widget", count: 2 })).status < 300)
    await kit.restart() // a new sidecar, the same database: the state is durable, not cached
    assert.deepEqual((await kit.http.get("/items/i1")).json(), { id: "i1", name: "Widget", count: 2, owner: null })
    const rows = await jsonWhen(kit, "/items/", (body) => Array.isArray(body) && body.length === 1)
    assert.deepEqual(rows, [{ id: "i1", name: "Widget", count: 2 }])
  } finally {
    await kit.stop()
  }
})

test("the owner's email is kept encrypted and read back through the keyring", async () => {
  // The kit starts a keyring beside the sidecar, as the platform runs one beside every service.
  const kit = await AnkkaTestKit.start(service())
  try {
    assert.ok((await kit.http.post("/items/i2", { name: "Gadget", count: 1 })).status < 300)
    const set = await kit.http.put("/items/i2/owner", { user: "u2", email: "grace@example.com" })
    assert.ok(set.status < 300, set.text())
    await kit.restart() // a new sidecar fetches the subject's key from the keyring again
    assert.equal(((await kit.http.get("/items/i2")).json() as { owner: unknown }).owner, "grace@example.com")
  } finally {
    await kit.stop()
  }
})
