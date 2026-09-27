// The whole service through the real sidecar: Postgres and the sidecar image in Docker, this process
// serving the components to it, and an HTTP client for the routes. Seconds, not milliseconds.
//
// The sidecar image is `$ANKKA_SIDECAR_IMAGE`, or `ankka-sidecar:latest`. These tests are skipped, and
// say so, when that image is not on this machine; see README.md, "The sidecar image".
import { test } from "node:test"
import assert from "node:assert/strict"
import { spawnSync } from "node:child_process"
import { AnkkaTestKit } from "ankka/testkit"
import { service } from "../src/main.ts"

const image = process.env.ANKKA_SIDECAR_IMAGE ?? "ankka-sidecar:latest"
const haveImage = spawnSync("docker", ["image", "inspect", image], { stdio: "ignore" }).status === 0
const skip = haveImage ? false : `the sidecar image ${image} is not on this machine; see README.md`

/** A view is eventually consistent: retry until the answer is the one expected, then return it. */
async function jsonWhen(kit: AnkkaTestKit, path: string, ready: (body: unknown) => boolean, timeoutMs = 20_000): Promise<unknown> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const body = (await kit.http.get(path)).json()
    if (ready(body) || Date.now() > deadline) return body
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
}

test("an item survives a restart and is listed", { skip }, async () => {
  const kit = await AnkkaTestKit.start(service())
  try {
    assert.ok((await kit.http.post("/items/i1", { name: "Widget", count: 2 })).status < 300)
    await kit.restart() // a new sidecar, the same database: the state is durable, not cached
    assert.deepEqual((await kit.http.get("/items/i1")).json(), { id: "i1", name: "Widget", count: 2 })
    const rows = await jsonWhen(kit, "/items/", (body) => Array.isArray(body) && body.length === 1)
    assert.deepEqual(rows, [{ id: "i1", name: "Widget", count: 2 }])
  } finally {
    await kit.stop()
  }
})
