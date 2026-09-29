// Autonomous agents declared in TypeScript: what discovery says, what registration refuses, and what
// the process answers when the sidecar asks it to check a task rule or run a tool.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { Ankka, RegistrationError } from "../src/service.ts"
import { AutonomousAgent, accepted, rejected, taskAcceptance, taskRule, taskType } from "../src/autonomous.ts"
import { handleTaskResult, handleTool } from "../src/server/agent.ts"
import { noClient } from "../src/client.ts"
import { tool } from "../src/handlers.ts"
import { s, type Infer } from "../src/schema.ts"

const Answer = s.record("Answer", { answer: s.string, sources: s.list(s.string) })
type Answer = Infer<typeof Answer>
const ANSWER = taskType("answer", "Answer a question", {
  result: Answer,
  rules: [taskRule<Answer>("cites-sources", (a) => (a.sources.length > 0 ? accepted() : rejected("sources must not be empty")))],
})
const SUMMARY = taskType("summary", "Summarise something")
const Topic = s.record("Topic", { topic: s.string })

class Answerer extends AutonomousAgent {
  static readonly componentId = "answerer"
  static readonly description = "Answers questions"
  static readonly instructions = "Be brief."
  static readonly tools = { lookup: tool("lookup", "Looks a topic up", Topic, (a: Answerer, t) => `${t.topic} for task ${a.taskId}`) }
  static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 5 }), taskAcceptance(SUMMARY)]
  static readonly settings = { approachingBudgetAt: 0.5, dependencyStuckAfterMillis: 1000 }
}

const registry = () => Ankka.service().register(Answerer).validate()
const ctx = () => ({ registry: registry(), client: noClient(), log: () => {} }) as any

describe("autonomous agents", () => {
  test("discovery carries the whole definition", () => {
    const spec = Ankka.service().register(Answerer).spec()
    const c = spec.components.find((x) => x.id === "answerer")!
    assert.equal(c.detail.case, "autonomousAgent")
    const d = c.detail.value as any
    assert.equal(d.description, "Answers questions")
    assert.equal(d.instructions, "Be brief.")
    assert.deepEqual(d.tools.map((t: any) => t.name), ["lookup"])
    assert.deepEqual(d.accepts.map((a: any) => [a.taskType, a.maxIterations]), [["answer", 5], ["summary", 10]])
    const answer = d.taskTypes.find((t: any) => t.name === "answer")
    assert.deepEqual(answer.rules, ["cites-sources"])
    assert.deepEqual(JSON.parse(answer.resultSchemaJson).required, ["answer", "sources"])
    assert.equal(d.taskTypes.find((t: any) => t.name === "summary").resultSchemaJson, undefined)
    assert.equal(d.settings.approachingBudgetAt, 0.5)
    assert.equal(d.settings.dependencyStuckAfterMillis, 1000n)
  })

  test("registration refuses every problem at once", () => {
    class Bad extends AutonomousAgent {
      static readonly componentId = "bad"
      static readonly description = ""
      static readonly tools = { done: tool("complete_task", "x", Topic, () => "x") }
      static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 0 }), taskAcceptance(ANSWER)]
    }
    assert.throws(
      () => Ankka.service().register(Bad).validate(),
      (e: unknown) => {
        const message = String(e)
        return e instanceof RegistrationError && ["description is required", "reserved", "accepted twice", "at least one iteration"].every((m) => message.includes(m))
      },
    )
  })

  test("a result is decoded, then checked by the rules in order", async () => {
    const check = (resultJson: string, taskType = "answer") => handleTaskResult({ componentId: "answerer", taskId: "t-1", taskType, resultJson } as any, ctx())
    const reject = await check('{"answer":"3","sources":[]}')
    assert.deepEqual(reject.verdict.case, "reject")
    assert.deepEqual({ ...(reject.verdict.value as any), $typeName: undefined }, { $typeName: undefined, rule: "cites-sources", reason: "sources must not be empty" })
    assert.equal((await check('{"answer":"3","sources":["x"]}')).verdict.case, "accept")
    assert.equal((await check('{"answer":3}')).verdict.case, "malformed")
    await assert.rejects(check("{}", "nope"))
  })

  test("a tool knows which task it runs for", async () => {
    const result = await handleTool({ componentId: "answerer", sessionId: "task:t-9", tool: "lookup", argumentsJson: '{"topic":"red"}' } as any, ctx())
    assert.deepEqual(result.result, { case: "ok", value: "red for task t-9" })
  })

  test("a task type needs a name and a description, and declares a rule once", () => {
    assert.throws(() => taskType("", "x"))
    assert.throws(() => taskType("x", ""))
    assert.throws(() => taskType("x", "y", { rules: [taskRule("r", () => accepted()), taskRule("r", () => accepted())] }))
  })
})
