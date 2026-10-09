// Which sidecar, and which keyring beside it, the integration testkit starts when it is not told.
import { test } from "node:test"
import assert from "node:assert/strict"
import { keyringImage, sidecarImage } from "../src/testkit/integration.ts"

function withEnv(value: string | undefined, run: () => void): void {
  const saved = process.env.ANKKA_SIDECAR_IMAGE
  if (value === undefined) delete process.env.ANKKA_SIDECAR_IMAGE
  else process.env.ANKKA_SIDECAR_IMAGE = value
  try {
    run()
  } finally {
    if (saved === undefined) delete process.env.ANKKA_SIDECAR_IMAGE
    else process.env.ANKKA_SIDECAR_IMAGE = saved
  }
}

test("a released SDK uses the sidecar published with it", () => {
  withEnv(undefined, () => assert.equal(sidecarImage("0.7.0"), "ghcr.io/thinkmorestupidless/ankka-sidecar:0.7.0"))
})

test("an unreleased SDK uses the sidecar built from the same checkout", () => {
  withEnv(undefined, () => {
    assert.equal(sidecarImage("0.0.0"), "ankka-sidecar:latest")
    assert.equal(sidecarImage(), "ankka-sidecar:latest") // this checkout's own version is 0.0.0
  })
})

test("the environment wins", () => {
  withEnv("registry.example/sidecar:dev", () => assert.equal(sidecarImage("0.7.0"), "registry.example/sidecar:dev"))
})

test("the keyring beside the sidecar is resolved the same way", () => {
  const saved = process.env.ANKKA_KEYRING_IMAGE
  delete process.env.ANKKA_KEYRING_IMAGE
  try {
    assert.equal(keyringImage("0.7.0"), "ghcr.io/thinkmorestupidless/ankka-keyring:0.7.0")
    assert.equal(keyringImage("0.0.0"), "ankka-keyring:latest")
    process.env.ANKKA_KEYRING_IMAGE = "registry.example/keyring:dev"
    assert.equal(keyringImage("0.7.0"), "registry.example/keyring:dev")
  } finally {
    if (saved === undefined) delete process.env.ANKKA_KEYRING_IMAGE
    else process.env.ANKKA_KEYRING_IMAGE = saved
  }
})
