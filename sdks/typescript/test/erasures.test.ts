// Asking for an erasure request from code (feature 042, protocol 1.23), against a stub client.

import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { Code, ConnectError } from "@connectrpc/connect"
import { ErasureCallSchema } from "../src/_proto/ankka/protocol/v1/erasure_pb.ts"
import { ErrorCode as ProtoErrorCode } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { CommandError, ComponentClient, Consumer, Endpoint, Erasures, EventSourcedEntity, LocalDate } from "../src/index.ts"
import { GraphConsumer } from "../src/graph.ts"
import { invokeErasure } from "../src/server/erasure.ts"
import type { ServerContext } from "../src/server/server.ts"

interface Asked {
  project: string
  subject: string
  notBefore?: string
  reason?: string
  correlation?: string
}

function stubbed(reply: unknown): { client: ComponentClient; asked: Asked[] } {
  const asked: Asked[] = []
  const stub = {
    askErasure: async (req: Asked) => {
      asked.push(req)
      return reply
    },
  }
  return { client: new ComponentClient("e", { address: "e", transport: undefined, stub: stub as never }), asked }
}

const held = { reply: { case: "ok", value: { id: "er-1", state: "held" } } }

describe("asking for an erasure", () => {
  test("its own project is sent empty, and nothing optional is sent unasked", async () => {
    const { client, asked } = stubbed(held)
    await new Erasures(client).ask({ subject: "customer-42" })
    assert.equal(asked[0]!.project, "")
    assert.equal(asked[0]!.subject, "customer-42")
    assert.equal(asked[0]!.notBefore, undefined)
    assert.equal(asked[0]!.reason, undefined)
  })

  test("the date is sent as ISO-8601, with the project and the words", async () => {
    const { client, asked } = stubbed(held)
    await new Erasures(client).ask({ subject: "customer-42", project: "ledger", notBefore: LocalDate.of(2027, 1, 31), reason: "aml-retention", correlation: "c-7" })
    assert.deepEqual(
      { project: asked[0]!.project, notBefore: asked[0]!.notBefore, reason: asked[0]!.reason, correlation: asked[0]!.correlation },
      { project: "ledger", notBefore: "2027-01-31", reason: "aml-retention", correlation: "c-7" },
    )
  })

  test("the answer is the request's id and its state", async () => {
    const { client } = stubbed({ reply: { case: "ok", value: { id: "er-9", state: "applying" } } })
    assert.deepEqual(await new Erasures(client).ask({ subject: "customer-42" }), { id: "er-9", state: "applying" })
  })

  test("a refusal is a CommandError with its code", async () => {
    const { client } = stubbed({ reply: { case: "error", value: { message: "no erasure grant", code: ProtoErrorCode.FORBIDDEN, details: {} } } })
    await assert.rejects(new Erasures(client).ask({ subject: "customer-42" }), (e: unknown) => e instanceof CommandError && e.code === "FORBIDDEN" && e.message === "no erasure grant")
  })

  test("a runtime without it is reported as too old, naming 1.23", async () => {
    const stub = { askErasure: () => Promise.reject(new ConnectError("Method not found", Code.Unimplemented)) }
    const client = new ComponentClient("old", { address: "old", transport: undefined, stub: stub as never })
    await assert.rejects(new Erasures(client).ask({ subject: "customer-42" }), (e: unknown) => e instanceof CommandError && e.message.includes("1.23"))
  })

  test("endpoints and consumers are given them; entities are not", () => {
    for (const cls of [Endpoint, Consumer, GraphConsumer]) assert.equal("erasures" in cls.prototype, true, cls.name)
    assert.equal("erasures" in EventSourcedEntity.prototype, false)
  })

  test("the erasure handler is given them, asking through its client", async () => {
    const { client, asked } = stubbed(held)
    const ctx = { client, registry: {}, log: () => {} } as unknown as ServerContext
    const call = create(ErasureCallSchema, { subject: "player/8c1f", erasureSeq: 1n, rerun: false })
    await invokeErasure(async (c, subject) => {
      await c.erasures.ask({ subject })
    }, call, ctx)
    assert.equal(asked[0]?.subject, "player/8c1f")
  })
})
