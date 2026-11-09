// The answerer and the operator: their declarations, the questions endpoint against a client double,
// and — with ANKKA_SLOW=1 and Docker — a task worked to its end through the sidecar's scripted model.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { Ankka, type ComponentClient } from "ankka"
import { AnkkaTestKit, EndpointTestKit } from "ankka/testkit"
import { ANSWER, CartAnswerer } from "./answerer.ts"
import { QuestionsEndpoint } from "./questions.ts"
import { Operator, OperatorEndpoint } from "./operator.ts"
import { service } from "./main.ts"

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

describe("the answerer and the operator, declared", () => {
  test("the answerer accepts the answer task type, with its rule and both tools", () => {
    const spec = Ankka.service().register(CartAnswerer).spec()
    const detail = spec.components.find((c) => c.id === "cart-answerer")!.detail.value as any
    assert.deepEqual(detail.tools.map((t: any) => t.name), ["cart_contents", "cart_total"])
    assert.deepEqual(detail.accepts.map((a: any) => [a.taskType, a.maxIterations]), [["answer", 5]])
    assert.deepEqual(detail.taskTypes[0].rules, ["cites-sources"])
  })

  test("the rule refuses an answer that cites nothing", async () => {
    const rule = ANSWER.rules[0]!
    assert.deepEqual(await rule.check({ answer: "3 items", sources: [] }), { verdict: "rejected", reason: "say which tools you used in sources" })
    assert.deepEqual(await rule.check({ answer: "3 items", sources: ["cart_total"] }), { verdict: "accepted" })
  })

  test("two of the operator's tools wait for a person, one with a time limit", () => {
    const detail = Ankka.service().register(Operator).register(OperatorEndpoint).spec().components.find((c) => c.id === "operator")!.detail.value as any
    const tools = new Map(detail.tools.map((t: any) => [t.name, t.approval]))
    assert.ok(tools.get("restart_service"))
    assert.equal((tools.get("drain_node") as any).withinMillis, 2_000n)
    assert.equal(tools.get("read_metrics"), undefined)
  })
})

describe("the questions endpoint, against a client double", () => {
  const calls: unknown[] = []
  const snapshot = { id: "t-1", typeName: "answer", status: "completed", result: { answer: "3 items", sources: ["cart_total"] }, reason: undefined, iterations: 2, assignee: { componentId: "cart-answerer", instanceId: "i-1" } }
  const client = {
    withMetadata: () => client,
    tasks: { create: async (_type: unknown, instructions: string, options: unknown) => (calls.push(["create", instructions, options]), `task-${calls.length}`) },
    forTask: () => ({ get: async () => snapshot }),
    forAutonomousAgent: (_cls: unknown, instance?: string) => ({
      runSingleTask: async (_type: unknown, instructions: string) => (calls.push(["run", instructions]), "t-1"),
      assign: async (...ids: string[]) => (calls.push(["assign", instance, ids]), { accepted: ids, refused: {} }),
      notifications: async function* () {
        yield { type: "TaskStarted", componentId: "cart-answerer", instanceId: instance, taskId: "t-1", at: 1 }
      },
    }),
  } as unknown as ComponentClient

  test("asking starts a task and answers where to look; reading decodes the answer", async () => {
    const kit = EndpointTestKit.of(QuestionsEndpoint, client)
    assert.deepEqual((await kit.post("/questions/ask", "How many items are in c-1?")).json(), { taskId: "t-1", instanceId: "i-1" })
    assert.deepEqual(calls.at(-1), ["run", "How many items are in c-1?"])
    const read = (await kit.get("/questions/t-1")).json() as { status: string; answer: { sources: string[] } }
    assert.deepEqual([read.status, read.answer.sources], ["completed", ["cart_total"]])
  })

  test("a review creates two tasks, the second depending on the first, and assigns both", async () => {
    calls.length = 0
    assert.equal((await EndpointTestKit.of(QuestionsEndpoint, client).post("/questions/review")).status, 204)
    assert.deepEqual(calls, [
      ["create", "What is in cart c-1?", undefined],
      ["create", "And in c-2?", { dependsOn: ["task-1"] }],
      ["assign", "reviewer-1", ["task-1", "task-2"]],
    ])
  })

  test("notifications are forwarded as one JSON string each", async () => {
    const frames = await EndpointTestKit.of(QuestionsEndpoint, client).sse("/questions/answerer/i-1/notifications")
    assert.deepEqual(frames.map((f) => JSON.parse(f).type), ["TaskStarted"])
  })
})

describe("an autonomous task through the real sidecar", { skip: slow }, () => {
  // docs:start scripted-autonomous
  const ANSWER_SCRIPT = JSON.stringify([
    { tool: "cart_total", arguments: { cartId: "q1" } },
    // The first answer cites nothing, so the rule in this process sends it back.
    { tool: "complete_task", arguments: { answer: "3 items", sources: [] } },
    { tool: "complete_task", arguments: { answer: "Cart q1 holds 3 items.", sources: ["cart_total"] } },
  ])

  test("the loop runs in the sidecar, the tool and the rule here, and the typed result is read back", async () => {
    const kit = await AnkkaTestKit.start(service(), { env: { ANKKA_MODEL_SCRIPT: ANSWER_SCRIPT } })
    try {
      assert.equal((await kit.http.post("/carts/q1/items", { productId: "p1", name: "Pen", quantity: 2 })).status, 204)
      assert.equal((await kit.http.post("/carts/q1/items", { productId: "p2", name: "Ink", quantity: 1 })).status, 204)
      const asked = await kit.http.post("/questions/ask", "How many items are in q1?")
      assert.equal(asked.status, 200, asked.text())
      const { taskId } = asked.json() as { taskId: string }

      const done = await kit.client.forTask(taskId).wait(ANSWER, { timeoutMs: 60_000 })
      assert.equal(done.status, "completed")
      assert.deepEqual(done.result, { answer: "Cart q1 holds 3 items.", sources: ["cart_total"] })
      assert.equal(done.iterations, 3)
    } finally {
      await kit.stop()
    }
  })
  // docs:end scripted-autonomous
})
