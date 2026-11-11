// A workflow's standing and how a copy stands, as this SDK asks the sidecar for them (protocol 1.24),
// against a stub client: each standing the platform records decoded, a refusal carried in the reply, and
// "a runtime from before the read refuses ..., naming the protocol version" for both reads.

import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { create } from "@bufbuild/protobuf"
import { Code, ConnectError } from "@connectrpc/connect"
import {
  LifecycleReplySchema,
  ReplicationReplySchema,
  WorkflowStatus,
  type LifecycleReply,
  type ReplicationReply,
} from "../src/_proto/ankka/protocol/v1/client_pb.ts"
import { ErrorCode as ProtoErrorCode } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { CommandError, ComponentClient, READS_SINCE } from "../src/index.ts"

function answering(lifecycle?: LifecycleReply, replication?: ReplicationReply) {
  const asked: unknown[] = []
  const stub = {
    lifecycle: (request: unknown) => (asked.push(request), Promise.resolve(lifecycle)),
    replication: (request: unknown) => (asked.push(request), Promise.resolve(replication)),
  }
  return { client: new ComponentClient("stub", { address: "stub", transport: undefined, stub: stub as never }), asked }
}

const standing = (fields: Record<string, unknown>) => create(LifecycleReplySchema, { result: { case: "lifecycle", value: fields as never } })

describe("a workflow's standing", () => {
  for (const [status, expected] of [
    [WorkflowStatus.NOT_STARTED, "not-started"],
    [WorkflowStatus.RUNNING, "running"],
    [WorkflowStatus.PAUSED, "paused"],
    [WorkflowStatus.COMPLETED, "completed"],
    [WorkflowStatus.FAILED, "failed"],
  ] as const) {
    test(`${expected} is read`, async () => {
      const { client, asked } = answering(standing({ status }))
      assert.equal((await client.forWorkflow("transfer", "t1").lifecycle()).status, expected)
      assert.deepEqual(asked, [{ componentId: "transfer", workflowId: "t1" }])
    })
  }

  test("a running workflow names its step; a failed one its reason and retries", async () => {
    const running = await answering(standing({ status: WorkflowStatus.RUNNING, pendingStep: "withdraw" })).client.forWorkflow("transfer", "t1").lifecycle()
    assert.deepEqual(running, { status: "running", pendingStep: "withdraw", retries: {} })
    const failed = await answering(standing({ status: WorkflowStatus.FAILED, retries: { deposit: 2 }, failure: "the bank was shut" }))
      .client.forWorkflow("transfer", "t1")
      .lifecycle()
    assert.deepEqual(failed, { status: "failed", retries: { deposit: 2 }, failure: "the bank was shut" })
  })

  test("a status this SDK does not know is refused", async () => {
    await assert.rejects(answering(standing({ status: 0 })).client.forWorkflow("transfer", "t1").lifecycle(), (e: unknown) => e instanceof CommandError && e.code === "INTERNAL")
  })
})

describe("how a copy stands", () => {
  test("its home, its set, its position, a handover waiting and a conflict", async () => {
    const reply = create(ReplicationReplySchema, {
      result: { case: "status", value: { home: "leipzig", replicationSet: ["leipzig", "vienna"], pendingHandover: "vienna", conflict: "vienna", position: 5n } as never },
    })
    const { client, asked } = answering(undefined, reply)
    assert.deepEqual(await client.forEventSourcedEntity("vehicle", "v1").replication(), {
      home: "leipzig",
      replicationSet: ["leipzig", "vienna"],
      pendingHandover: "vienna",
      conflict: "vienna",
      position: 5n,
    })
    assert.deepEqual(asked, [{ componentId: "vehicle", entityId: "v1" }])
  })
})

describe("refusals", () => {
  test("a refusal crosses with the runtime's code", async () => {
    const error = { message: "not replicated", code: ProtoErrorCode.NOT_FOUND } as never
    const { client } = answering(
      create(LifecycleReplySchema, { result: { case: "error", value: error } }),
      create(ReplicationReplySchema, { result: { case: "error", value: error } }),
    )
    await assert.rejects(client.forWorkflow("transfer", "t1").lifecycle(), (e: unknown) => e instanceof CommandError && e.code === "NOT_FOUND")
    await assert.rejects(client.forEventSourcedEntity("vehicle", "v1").replication(), (e: unknown) => e instanceof CommandError && e.code === "NOT_FOUND")
  })

  const old = () => {
    const refused = () => Promise.reject(new ConnectError("Method not found", Code.Unimplemented))
    return new ComponentClient("old", { address: "old", transport: undefined, stub: { lifecycle: refused, replication: refused } as never })
  }

  test("a runtime from before the read refuses a workflow's standing, naming the protocol version", async () => {
    assert.equal(READS_SINCE, "1.24")
    await assert.rejects(old().forWorkflow("transfer", "t1").lifecycle(), (e: unknown) => e instanceof CommandError && e.code === "UNAVAILABLE" && e.message.includes("protocol 1.24"))
  })

  test("a runtime from before the read refuses how a copy stands, naming the protocol version", async () => {
    await assert.rejects(old().forEventSourcedEntity("vehicle", "v1").replication(), (e: unknown) => e instanceof CommandError && e.code === "UNAVAILABLE" && e.message.includes("protocol 1.24"))
  })
})
