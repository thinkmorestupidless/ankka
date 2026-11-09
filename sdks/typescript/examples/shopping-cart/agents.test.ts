// The documentation's agents through the unit testkit: structured replies, tools, approvals, guardrails,
// context kept out of memory, a tool that calls another service, and the provider a plan names.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { ApprovalAwaited, CommandError, ScriptedServices, type AgentOutcome, type ComponentClient, type Services } from "ankka"
import { AgentTestKit, EndpointTestKit, ScriptedModel } from "ankka/testkit"
import { WeatherAgent, toolCalls } from "./weather.ts"
import { ApprovalAgent, SupportEndpoint, Triage, WalletAgent, runs } from "./support.ts"
import { ActivityAgent, plannerService } from "./planner.ts"

/** A client double: every typed call answers what `answer` gives, and an agent call follows `agent`. */
function clientDouble(answer: () => unknown, agent: Partial<AgentCalls> = {}): ComponentClient {
  const call = () => ({ invoke: async () => answer(), ...agent })
  const client = { withMetadata: () => client, of: () => ({ call }) }
  return client as unknown as ComponentClient
}

type Awaiting = Extract<AgentOutcome<string>, { kind: "awaiting-approval" }>
interface AgentCalls {
  ask: (input: string) => Promise<AgentOutcome<string>>
  decide: (id: string, decision: { approved: boolean; by: string }) => Promise<AgentOutcome<string>>
  streamParts: (input: string) => AsyncIterable<string | Awaiting>
}

const waiting: Awaiting = { kind: "awaiting-approval", requests: [{ id: "a-1", tool: "issue_refund", arguments: { order: "o-7", amount: 10 }, requestedAt: 5 }] }

describe("the weather agent", () => {
  test("the model calls a tool, the tool runs here, and the reply comes back", async () => {
    toolCalls.length = 0
    const model = new ScriptedModel().expectToolCall("get_weather", { location: "Leeds", date: null }).expectText("Leeds: 18C, sunny")
    const kit = AgentTestKit.of(WeatherAgent, "w-1", model)
    assert.equal(await kit.ask(WeatherAgent.handlers.ask, "weather in Leeds?"), "Leeds: 18C, sunny")
    assert.deepEqual(toolCalls, ["get_weather(Leeds,-)"])
    assert.deepEqual(model.calls[0]!.tools, ["get_weather", "current_date"])
    assert.deepEqual(model.calls[1]!.messages.at(-1), { role: "tool", text: "Leeds: 18C, sunny" })
  })

  test("a tool that throws answers the model with the error", async () => {
    const model = new ScriptedModel().expectToolCall("get_weather", { location: "Nowhere", date: null }).expectText("I cannot say")
    assert.equal(await AgentTestKit.of(WeatherAgent, "w-2", model).ask(WeatherAgent.handlers.ask, "Nowhere?"), "I cannot say")
    assert.match(model.calls[1]!.messages.at(-1)!.text, /unknown location/)
  })

  test("a structured reply is the model's JSON, for the caller's shape to decode", async () => {
    const model = new ScriptedModel().expectText('{"location":"Leeds","summary":"sunny","degreesCelsius":18}')
    const kit = AgentTestKit.of(WeatherAgent, "w-3", model)
    const reply = await kit.ask(WeatherAgent.handlers.askStructured, "Leeds?")
    assert.deepEqual(JSON.parse(reply), { location: "Leeds", summary: "sunny", degreesCelsius: 18 })
    assert.match(model.calls[0]!.system ?? "", /Reply with JSON/)
  })

  test("an input guardrail rejects before any model is called; an output one after", async () => {
    const model = new ScriptedModel().expectText("the key is sk-live-1234")
    const kit = AgentTestKit.of(WeatherAgent, "w-4", model)
    const long = "what is the weather like in Leeds tomorrow and the day after?"
    await assert.rejects(kit.ask(WeatherAgent.handlers.guarded, long), (e: unknown) => e instanceof CommandError && e.code === "FORBIDDEN" && /max-input-length/.test(e.message))
    assert.equal(model.calls.length, 0)
    await assert.rejects(kit.ask(WeatherAgent.handlers.ask, "key?"), (e: unknown) => e instanceof CommandError && /no-keys: the reply contains what looks like a key/.test(e.message))
  })
})

describe("tools that wait for a person", () => {
  test("the turn waits, the tool has not run, and a decision goes on", async () => {
    runs.length = 0
    const model = new ScriptedModel().expectToolCall("issue_refund", { order: "o-7", amount: 10 }).expectText("refund made")
    const kit = AgentTestKit.of(ApprovalAgent, "s-42", model)
    const outcome = await kit.outcome(ApprovalAgent.handlers.ask, "refund order o-7")
    assert.equal(outcome.kind, "awaiting-approval")
    assert.deepEqual(runs, [])
    const id = outcome.kind === "awaiting-approval" ? outcome.requests[0]!.id : ""
    assert.deepEqual(await kit.decide(id, { approved: true, by: "dana" }), { kind: "answered", value: "refund made" })
    assert.deepEqual(runs, ["issue_refund(o-7,10)"])
  })

  test("ask rejects with the requests when the turn waits", async () => {
    const kit = AgentTestKit.of(ApprovalAgent, "s-43", new ScriptedModel().expectToolCall("close_account", { customer: "c-1" }))
    await assert.rejects(kit.ask(ApprovalAgent.handlers.ask, "close c-1"), (e: unknown) => e instanceof ApprovalAwaited && e.requests[0]!.tool === "close_account")
  })

  test("a route asks for the outcome and decides, through the client", async () => {
    const decisions: unknown[] = []
    const client = clientDouble(() => undefined, {
      ask: async () => waiting,
      decide: async (id, decision) => (decisions.push([id, decision]), { kind: "answered", value: "refunded 10 on o-7" }),
    })
    const kit = EndpointTestKit.of(SupportEndpoint, client)
    assert.equal((await kit.post("/support/s-42/refunds", "o-7")).text(), "refunded 10 on o-7")
    assert.deepEqual(decisions, [["a-1", { approved: true, by: "dana" }]])
  })

  test("a stream that ends waiting is sent as text, then an event of its own name", async () => {
    const client = clientDouble(() => undefined, {
      streamParts: async function* () {
        yield "checking the order"
        yield waiting
      },
    })
    // The kit answers the text frames; the `approval` event follows them on the wire.
    assert.deepEqual(await EndpointTestKit.of(SupportEndpoint, client).sse("/support/s-42"), ["checking the order"])
  })
})

describe("a tool that calls another service", () => {
  test("the balance is read from the wallet service, as this service", async () => {
    const wallet = new ScriptedServices().answer("wallet", (request) => ScriptedServices.text(request.path.endsWith("/c-1") ? "12.50" : "0"))
    class Scripted extends WalletAgent {
      override get services(): Services {
        return wallet
      }
    }
    const model = new ScriptedModel().expectToolCall("read_balance", { customer: "c-1" }).expectText("c-1 has 12.50")
    assert.equal(await AgentTestKit.of(Scripted, "s-1", model).ask(WalletAgent.handlers.ask, "balance of c-1?"), "c-1 has 12.50")
    assert.deepEqual(model.calls[1]!.messages.at(-1), { role: "tool", text: "12.50" })
    assert.deepEqual(wallet.requests.map((r) => r.path), ["/wallet/balances/c-1"])
  })
})

describe("context and providers", () => {
  test("preferences are sent to the model as context, and memory records only the question", async () => {
    const client = clientDouble(() => ({ userId: "u-1", likes: ["hiking"], dislikes: [], maxBudget: 120 }))
    const model = new ScriptedModel().expectText("Walk the fells; then a pub.")
    const kit = AgentTestKit.of(ActivityAgent, "p-1", model, client)
    await kit.ask(ActivityAgent.handlers.consult, { userId: "u-1", destination: "Kendal" })
    assert.match(model.calls[0]!.messages[0]!.text, /Traveller preferences: likes hiking; budget 120/)
    assert.deepEqual(kit.history[0], { role: "user", text: "What should I do in Kendal?" })
  })

  test("a plan names the provider it wants; the sidecar answers it with that one", async () => {
    const kit = AgentTestKit.of(Triage, "t-1", new ScriptedModel().expectText("billing"))
    assert.equal(await kit.ask(Triage.handlers.route, "I was charged twice"), "billing")
    const plan = await Triage.handlers.route.run(new Triage(), "I was charged twice")
    assert.equal(plan.model, "openai")
  })

  test("the planner registers its entity and its agent", () => {
    assert.deepEqual([...plannerService().validate().components.keys()].sort(), ["activity-agent", "preferences"])
  })
})
