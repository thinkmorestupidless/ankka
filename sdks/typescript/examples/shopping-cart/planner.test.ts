// The planner's coordination, with no sidecar: the selector's structured reply, the specialists through
// the agent kit, the summariser, and the workflow against a client double that plays the agents.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { type ComponentClient } from "ankka"
import { AgentTestKit, AnkkaTestKit, ScriptedModel, WorkflowTestKit } from "ankka/testkit"
import { ActivityAgent, ForecastAgent, type PlanState, PlannerWorkflow, SelectorAgent, Specialist, SummaryAgent, plannerService } from "./planner.ts"
import { TicketAgent } from "./tickets.ts"

describe("the agents of the plan", () => {
  test("the selector answers structured JSON and keeps its routing out of the session", async () => {
    const model = new ScriptedModel().expectText('{"specialists":["weather","budget"],"reason":"a trip"}')
    const kit = AgentTestKit.of(SelectorAgent, "p-1", model)
    const selection = JSON.parse(await kit.ask(SelectorAgent.handlers.select, "Plan a trip to Lisbon"))
    assert.deepEqual(selection.specialists, ["weather", "budget"])
    assert.equal(kit.history.length, 0, "memory off: nothing of the selector's reaches the session")
    await assert.rejects(kit.ask(SelectorAgent.handlers.select, "   "), /nothing to plan/)
  })

  test("the weather specialist takes two model turns, one for its tool", async () => {
    const model = new ScriptedModel().expectToolCall("get_forecast", { destination: "Lisbon" }).expectText("Lisbon is mild with occasional rain.")
    assert.equal(await AgentTestKit.of(ForecastAgent, "p-1", model).ask(ForecastAgent.handlers.consult, "Lisbon"), "Lisbon is mild with occasional rain.")
    assert.equal(model.calls.length, 2)
  })

  test("the activity specialist reads the traveller's preferences as context, not as the user's words", async () => {
    const preferences = { userId: "u-1", likes: ["museums"], dislikes: [], maxBudget: 90 }
    const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: async () => preferences }) }) } as unknown as ComponentClient
    const model = new ScriptedModel().expectText("Try the tram and a pastel de nata.")
    const kit = AgentTestKit.of(ActivityAgent, "p-1", model, client)
    await kit.ask(ActivityAgent.handlers.consult, { userId: "u-1", destination: "Lisbon" })
    assert.match(model.calls[0]!.messages.map((m) => m.text).join("\n"), /likes museums/)
    assert.deepEqual(kit.history.map((m) => m.text), ["What should I do in Lisbon?", "Try the tram and a pastel de nata."])
  })

  test("the summariser reads the shared session", async () => {
    const model = new ScriptedModel().expectText("A mild, affordable trip.")
    assert.equal(await AgentTestKit.of(SummaryAgent, "p-1", model).ask(SummaryAgent.handlers.summarise, "Lisbon"), "A mild, affordable trip.")
    assert.match(model.calls[0]!.messages.at(-1)!.text, /brief for a trip to Lisbon/)
  })
})

describe("the ticket agent's MCP server", () => {
  // docs:start scripted-mcp-server
  test("the model calls the server's tool, and the kit answers it from the script", async () => {
    const sent: unknown[] = []
    const model = new ScriptedModel().expectToolCall("mcp__tickets__create", { title: "broken" }).expectText("opened it")
    const kit = AgentTestKit.of(TicketAgent, "t-1", model, undefined, {
      mcp: {
        tickets: {
          create: (args) => (sent.push(args), `opened '${args.title}'`),
          search: () => "2 tickets found",
        },
      },
    })
    assert.equal(await kit.ask(TicketAgent.handlers.ask, "open a ticket: broken"), "opened it")
    assert.deepEqual(sent, [{ title: "broken" }])
    // The server's tools reach the model under the server's name.
    assert.deepEqual(model.calls[0]!.tools, ["mcp__tickets__create", "mcp__tickets__search"])
    assert.equal(model.calls[1]!.messages.at(-1)!.text, "opened 'broken'")
  })
  // docs:end scripted-mcp-server
})

describe("the planner workflow, against agents played by a client double", () => {
  // docs:start script
  /** Plays the agents: the selector's structured reply plus one answer per specialist, recording who was asked. */
  function scriptPlan(specialists: string[], summary: string) {
    const asked: { agent: string; session: string }[] = []
    const answers: Record<string, unknown> = {
      "selector-agent": { specialists, reason: "chosen for the test" },
      "weather-agent": "Lisbon is mild with occasional rain.",
      "activity-agent": "Try the tram and a pastel de nata.",
      "budget-agent": "Budget about 90 euros a day.",
      "summary-agent": summary,
    }
    const client = {
      withMetadata: () => client,
      of: (cls: { componentId: string }, session: string) => ({
        call: () => ({
          invoke: async () => {
            asked.push({ agent: cls.componentId, session })
            return answers[cls.componentId]
          },
        }),
      }),
    }
    return { client: client as unknown as ComponentClient, asked }
  }
  // docs:end script

  // docs:start assert
  test("the selector decides which specialists run, and only those run", async () => {
    const { client, asked } = scriptPlan([Specialist.Weather, Specialist.Budget], "A mild, affordable trip.")
    const kit = WorkflowTestKit.of(PlannerWorkflow, "p-select", client)
    const started = await kit.call(PlannerWorkflow.handlers.start, { userId: "u-1", destination: "Lisbon" })
    assert.equal(started.transition?.step, "select")
    const progress = await kit.runUntilEnd()
    assert.equal(progress.ended, true)

    const plan = kit.state
    assert.deepEqual(plan.selection?.specialists, [Specialist.Weather, Specialist.Budget])
    assert.deepEqual(plan.contributions.map((c) => c.specialist), [Specialist.Weather, Specialist.Budget])
    // The activity specialist was never consulted, so it contributed nothing.
    assert.equal(asked.some((a) => a.agent === "activity-agent"), false)
    assert.equal(plan.summary, "A mild, affordable trip.")
    // Every agent was addressed on the workflow's own session.
    assert.deepEqual([...new Set(asked.map((a) => a.session))], ["p-select"])
  })
  // docs:end assert


  test("a selector that names nothing usable falls back to the activity specialist", async () => {
    const { client, asked } = scriptPlan(["astrology"], "A trip.")
    const kit = WorkflowTestKit.of(PlannerWorkflow, "p-fallback", client)
    await kit.call(PlannerWorkflow.handlers.start, { userId: "u-1", destination: "Lisbon" })
    await kit.runUntilEnd()
    assert.deepEqual(kit.state.selection?.specialists, [Specialist.Activity])
    assert.deepEqual(asked.map((a) => a.agent), ["selector-agent", "activity-agent", "summary-agent"])
  })
})

const slow = process.env.ANKKA_SLOW ? false : "set ANKKA_SLOW=1 to run the tests that need Docker"

describe("the planner through the real sidecar, its model scripted", { skip: slow }, () => {
  test("a plan's own state and the engine's standing are read apart", async () => {
    const script = [
      { text: JSON.stringify({ specialists: [Specialist.Budget], reason: "cost" }) },
      { when: "What does a day", text: "About 90 euros a day." },
      { when: "Write a brief", text: "Cheap." },
    ]
    const kit = await AnkkaTestKit.start(plannerService(), { env: { ANKKA_MODEL_SCRIPT: JSON.stringify(script) } })
    try {
      const planner = kit.client.of(PlannerWorkflow, "p-observe")
      await planner.call(PlannerWorkflow.handlers.start).invoke({ userId: "u-1", destination: "Lisbon" })
      await planner.awaitEnd<PlanState>(60_000)
      // docs:start observe
      const state = await planner.call(PlannerWorkflow.handlers.plan).invoke() // the domain state
      const lifecycle = await planner.lifecycle() // the engine's state
      assert.equal(state.status, "completed")
      assert.equal(lifecycle.status, "completed")
      // docs:end observe
    } finally {
      await kit.stop()
    }
  })
})
