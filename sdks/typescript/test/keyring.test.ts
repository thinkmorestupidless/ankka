// The sidecar's keys as the process fetches them: one blocking call through a worker on a miss, the
// answer held until its expiry, and a subject the runtime says is erased dropped at once.

import { after, before, test } from "node:test"
import assert from "node:assert/strict"
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { createInterface } from "node:readline"
import { SidecarKeys } from "../src/keyring.ts"

const key = new Uint8Array(32).fill(7)
let child: ChildProcessWithoutNullStreams
let address = ""
const lines: string[] = []
let wakeLine: (() => void) | undefined

async function line(prefix: string): Promise<string> {
  for (;;) {
    const i = lines.findIndex((l) => l.startsWith(prefix))
    if (i >= 0) return lines.splice(i, 1)[0]!
    await new Promise<void>((resolve) => (wakeLine = resolve))
  }
}

async function fetches(): Promise<number> {
  child.stdin.write("count\n")
  return Number((await line("count ")).split(" ")[1])
}

before(async () => {
  const script = resolve(dirname(fileURLToPath(import.meta.url)), "fixtures", "key-sidecar.ts")
  child = spawn(process.execPath, ["--conditions=ankka-source", script])
  createInterface({ input: child.stdout }).on("line", (l) => {
    lines.push(l)
    wakeLine?.()
  })
  address = `127.0.0.1:${(await line("port ")).split(" ")[1]}`
})

after(() => {
  child.stdin.end()
  child.kill()
})

test("a miss is fetched with one blocking call, learns the project, and is then held", async () => {
  const keys = new SidecarKeys(() => address)
  try {
    const answer = keys.key(undefined, "player/8c1f", true)
    assert.equal(answer.kind, "key")
    assert.equal(keys.ownProject, "brand")
    assert.deepEqual(answer.kind === "key" ? [...answer.key] : [], [...key])
    const before = await fetches()
    keys.key("brand", "player/8c1f", false)
    assert.equal(await fetches(), before, "a held key is not fetched again")
    assert.equal(keys.key("brand", "player/gone", false).kind, "destroyed")
    assert.ok(keys.isDestroyed("brand", "player/gone"))
  } finally {
    await keys.close()
  }
})

test("a subject the runtime says is erased is dropped at once", async () => {
  const keys = new SidecarKeys(() => address)
  try {
    // Only this source's stream: whatever an earlier one announced is not it.
    for (let i = lines.length - 1; i >= 0; i--) if (lines[i] === "listening") lines.splice(i, 1)
    assert.equal(keys.key("brand", "player/77a0", true).kind, "key")
    await line("listening")
    child.stdin.write("erase player/77a0\n")
    for (let i = 0; i < 50 && !keys.isDestroyed("brand", "player/77a0"); i++) await new Promise((r) => setTimeout(r, 20))
    assert.ok(keys.isDestroyed("brand", "player/77a0"))
    assert.equal(keys.key("brand", "player/77a0", false).kind, "destroyed")
  } finally {
    await keys.close()
  }
})

test("an unreachable runtime is an outage, not an erasure", async () => {
  const keys = new SidecarKeys(() => "127.0.0.1:1")
  try {
    assert.equal(keys.key("brand", "player/8c1f", false).kind, "unavailable")
  } finally {
    await keys.close()
  }
})
