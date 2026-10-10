// Waiting for a workflow's end from a TypeScript handler, through the sidecar: the TypeScript rows of
// `features/awaiting-workflows/languages.feature` and the stream form of `serving.feature`. The unit
// cases need nothing; the rest need Docker and the sidecar image from this branch (`ANKKA_SIDECAR_IMAGE`).
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { createServer, type Http2Server, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { AWAIT_SINCE, CommandError, ComponentClient, ErrorCode, awaitPartJson } from "ankka"
import { AnkkaTestKit } from "ankka/testkit"
import { Checkout, CheckoutWorkflow } from "./checkoutWorkflow.ts"
import { service } from "./main.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

/** A runtime from before waiting: every gRPC call is answered UNIMPLEMENTED (status 12). */
async function oldRuntime(): Promise<{ address: string; server: Http2Server; stop: () => Promise<void> }> {
  const sessions = new Set<ServerHttp2Session>()
  const server = createServer((_request, response) => {
    response.writeHead(200, { "content-type": "application/grpc", "grpc-status": "12", "grpc-message": "Method not found" })
    response.end()
  })
  server.on("session", (session) => sessions.add(session))
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()))
  // A client keeps an idle session open, and close() waits for every session: destroy them first.
  const stop = async () => {
    for (const session of sessions) session.destroy()
    await new Promise<void>((resolve) => server.close(() => resolve()))
  }
  return { address: `127.0.0.1:${(server.address() as AddressInfo).port}`, server, stop }
}

describe("waiting for a workflow's end", () => {
  test("a runtime from before waiting refuses a handler's wait, naming the protocol version", async () => {
    const { address, stop } = await oldRuntime()
    try {
      const client = new ComponentClient(address)
      await assert.rejects(client.of(CheckoutWorkflow, "w1").awaitEnd<Checkout>(1_000), (e: unknown) => {
        assert.ok(e instanceof CommandError)
        assert.equal(e.code, ErrorCode.Unavailable)
        assert.match(e.message, new RegExp(`protocol ${AWAIT_SINCE.replace(".", "\\.")}`))
        return true
      })
    } finally {
      await stop()
    }
  })

  test("there is no default: a timeout of zero is refused before anything is sent", async () => {
    const client = new ComponentClient("127.0.0.1:1")
    await assert.rejects(client.of(CheckoutWorkflow, "w1").awaitEnd(0), (e: unknown) => e instanceof CommandError && e.code === ErrorCode.BadRequest)
  })

  test("each part of a wait is one line of JSON", () => {
    assert.equal(awaitPartJson({ kind: "heartbeat" }), '{"heartbeat":true}')
    assert.equal(awaitPartJson({ kind: "ended", state: { status: "charged" } }), '{"ended":{"status":"charged"}}')
    const failed = new CommandError({ message: "it failed", code: ErrorCode.WorkflowFailed, details: { step: "compensate", reason: "no" } })
    assert.deepEqual(JSON.parse(awaitPartJson({ kind: "failed", error: failed })), {
      failed: { code: "WORKFLOW_FAILED", message: "it failed", step: "compensate", reason: "no" },
    })
  })
})

describe("waiting for a workflow's end through the real sidecar", { skip: slow }, () => {
  test("a handler sends a command and waits for the end, and a failure names its step", async () => {
    const kit = await AnkkaTestKit.start(service())
    try {
      assert.equal((await kit.http.post("/carts/aw1/items", { productId: "p1", name: "Pen", quantity: 2 })).status, 204)
      const answer = await kit.http.post("/carts/aw1/checkouts/wait", "ok")
      assert.equal(answer.status, 200, answer.text())
      assert.deepEqual(answer.json(), { cartId: "aw1", status: "charged", reserved: 2, mode: "ok" })

      const ended = await kit.client.of(CheckoutWorkflow, "aw1").awaitEnd<Checkout>(10_000)
      assert.equal(ended.status, "charged")

      const failed = await kit.http.post("/carts/aw2/checkouts/wait", "doomed")
      assert.equal(failed.status, 424, failed.text())
      await assert.rejects(kit.client.of(CheckoutWorkflow, "aw2").awaitEnd<Checkout>(5_000), (e: unknown) => {
        assert.ok(e instanceof CommandError)
        assert.equal(e.code, ErrorCode.WorkflowFailed)
        assert.equal(e.details.step, "compensate")
        assert.match(e.details.reason ?? "", /compensation failed too/)
        return true
      })
    } finally {
      await kit.stop()
    }
  })

  test("a wait served as server-sent events ends with the state", async () => {
    const kit = await AnkkaTestKit.start(service())
    try {
      await kit.http.post("/carts/aw4/checkouts", "pause")
      const events = (await kit.http.get("/carts/aw4/checkouts/events")).text()
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => JSON.parse(JSON.parse(line.slice("data:".length).trim())))
      assert.equal(events.at(-1).ended.status, "charged", JSON.stringify(events))
      assert.ok(events.slice(0, -1).every((e) => e.heartbeat === true), JSON.stringify(events))
    } finally {
      await kit.stop()
    }
  })
})
