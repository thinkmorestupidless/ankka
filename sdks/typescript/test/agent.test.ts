// Agents through the unit testkit: the plan, the scripted model, tools called back into the agent,
// guardrails, memory, and what is refused.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { AgentTestKit, ScriptedModel } from "../src/testkit/kinds.ts"
import { CommandError } from "../src/effects/common.ts"
import { Ankka, RegistrationError } from "../src/service.ts"
import { noClient } from "../src/client.ts"
import { Agent } from "../src/agent.ts"
import { command, tool } from "../src/handlers.ts"
import { s } from "../src/schema.ts"
import { Calculator } from "./fixtures/kinds.ts"

describe("agents", () => {
  test("the model calls a tool, the tool runs in the agent, and the reply comes back", async () => {
    const model = new ScriptedModel().expectToolCall("add", { a: 2, b: 3 }).expectText("2 + 3 = 5")
    const kit = AgentTestKit.of(Calculator, "s1", model)
    assert.equal(await kit.ask(Calculator.handlers.ask, "what is 2 + 3?"), "2 + 3 = 5")
    assert.equal(model.calls.length, 2)
    assert.equal(model.calls[0]!.system, "You add numbers with the add tool.")
    assert.deepEqual(model.calls[0]!.tools, ["add"])
    assert.deepEqual(model.calls[1]!.messages.at(-1), { role: "tool", text: "5" })
    assert.equal(model.remaining, 0)
  })

  test("a tool error is fed back to the model, which corrects itself", async () => {
    const model = new ScriptedModel().expectToolCall("add", { a: 5000, b: 1 }).expectToolCall("add", { a: 1, b: 1 }).expectText("2")
    const kit = AgentTestKit.of(Calculator, "s2", model)
    assert.equal(await kit.ask(Calculator.handlers.ask, "add"), "2")
    assert.match(model.calls[1]!.messages.at(-1)!.text, /error: too big/)
  })

  test("a bad argument shape is a tool error too", async () => {
    const model = new ScriptedModel().expectToolCall("add", { a: "two", b: 3 }).expectText("sorry")
    const kit = AgentTestKit.of(Calculator, "s3", model)
    assert.equal(await kit.ask(Calculator.handlers.ask, "add"), "sorry")
    assert.match(model.calls[1]!.messages.at(-1)!.text, /error: a: expected a whole number/)
  })

  test("guardrails block input and output", async () => {
    const kit = AgentTestKit.of(Calculator, "s4", new ScriptedModel().expectText("you are stupid"))
    await assert.rejects(kit.ask(Calculator.handlers.ask, "you stupid machine"), (e: unknown) => e instanceof CommandError && e.code === "FORBIDDEN" && /polite: input was rude/.test(e.message))
    await assert.rejects(kit.ask(Calculator.handlers.ask, "hello"), (e: unknown) => e instanceof CommandError && /polite: output was rude/.test(e.message))
  })

  test("session memory carries earlier turns; memory(false) does not", async () => {
    const model = new ScriptedModel().expectText("hi Ada").expectText("you said your name is Ada").expectText("no memory here")
    const kit = AgentTestKit.of(Calculator, "s5", model)
    await kit.ask(Calculator.handlers.ask, "my name is Ada")
    await kit.ask(Calculator.handlers.ask, "what is my name?")
    assert.deepEqual(
      model.calls[1]!.messages.map((m) => m.role),
      ["user", "assistant", "user"],
    )
    await kit.ask(Calculator.handlers.forget, "anything")
    assert.deepEqual(model.calls[2]!.messages.map((m) => m.role), ["user"])
    assert.equal(kit.history.length, 4)
  })

  test("the scripted model fails loudly when the script runs out", async () => {
    const kit = AgentTestKit.of(Calculator, "s6", new ScriptedModel())
    await assert.rejects(kit.ask(Calculator.handlers.ask, "hi"), /ran out of responses/)
  })

  test("effects.error refuses without a model call; a plan naming an undeclared tool is refused", async () => {
    const model = new ScriptedModel().expectText("unused")
    const kit = AgentTestKit.of(Calculator, "s7", model)
    await assert.rejects(kit.ask(Calculator.handlers.refuse, "x"), (e: unknown) => e instanceof CommandError && e.code === "FORBIDDEN" && e.message === "not today")
    await assert.rejects(kit.ask(Calculator.handlers.rogue, "x"), /undeclared tool "subtract"/)
    assert.equal(model.calls.length, 0)
  })

  test("too many tool calls end the turn", async () => {
    const model = new ScriptedModel()
    for (let i = 0; i < 5; i++) model.expectToolCall("add", { a: 1, b: 1 })
    const kit = AgentTestKit.of(Calculator, "s8", model)
    await assert.rejects(kit.ask(Calculator.handlers.ask, "loop"), /more than 3 tool calls/)
  })

  test("a streaming handler yields the reply as tokens", async () => {
    const kit = AgentTestKit.of(Calculator, "s9", new ScriptedModel().expectText("one two three"))
    const tokens: string[] = []
    for await (const t of kit.stream(Calculator.handlers.chat, "count")) tokens.push(t)
    assert.deepEqual(tokens, ["one ", "two ", "three"])
  })

  test("a JSON reply is the model's text, for the caller to decode", async () => {
    const kit = AgentTestKit.of(Calculator, "s10", new ScriptedModel().expectText('{"sum":5}'))
    assert.equal(await kit.ask(Calculator.handlers.askJson, "2+3"), '{"sum":5}')
  })

  test("a tool needs a description; a duplicate tool name is a problem", () => {
    assert.throws(() => tool("x", "", s.string, () => ""), /needs a description/)
    class Twice extends Agent {
      static readonly componentId = "twice"
      static readonly tools = { a: tool("same", "one", s.string, () => ""), b: tool("same", "two", s.string, () => "") }
      static readonly handlers = { ask: command("ask", s.string, s.string, (t: Twice, q) => t.effects.userMessage(q).thenReply()) }
    }
    assert.throws(() => Ankka.service({ client: noClient() }).register(Twice).validate(), (e: unknown) => e instanceof RegistrationError && /two tools declare the name "same"/.test(e.message))
  })
})
