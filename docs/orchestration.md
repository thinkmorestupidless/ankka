# Multi-agent orchestration

Three ways to coordinate agents, and when each is right. The
`samples/multi-agent-planner` sample uses all three in one workflow.

## Why a workflow, not a chain of calls

An agent asking another agent a question is just a method call — fine until the process
dies halfway through. A workflow journals each step's outcome *before* the next begins,
so a plan that has consulted two specialists and crashed resumes at the third rather than
re-consulting (and re-paying for) the first two.

The rule of thumb: if the coordination is worth money or takes minutes, it belongs in a
workflow.

## Sequential

Steps that depend on each other. Each `thenTransitionTo` is a durable commit point.

```scala
def selectSpecialistsStep(destination: String): StepEffect =
  val selection = client.forAgent(session).call(SelectorAgent.select).invoke(...)
  stepEffects
    .updateState(currentState.copy(selection = Some(selection)))
    .thenTransitionTo(PlannerWorkflow.consultSpecialists)
```

## Parallel

Agents that do not depend on each other should not wait for each other. `invokeAsync`
issues the calls, then one await per result collects them — so the step takes as long as
the slowest model call, not the sum.

```scala
val pending = chosen.map { specialist =>
  specialist -> client.forAgent(session).call(agentFor(specialist)).invokeAsync(input)
}

val contributions = pending.map { (specialist, answer) =>
  Contribution(specialist, ComponentClient.await(answer, 90.seconds))
}
```

This is safe *because* agents are sharded per session and serialized: the fan-out is
concurrent across agents, and each agent's own session access is not.

## Dynamic

The workflow does not decide which agents to consult — it asks. A selector agent returns
a structured reply, and the workflow executes whatever it names.

```scala
final class SelectorAgent extends Agent:
  def select(request: String): Effect[AgentSelection] =
    effects
      .systemMessage(s"""Available specialists: ${Specialist.All.mkString(", ")}.
                        |Reply with JSON: {"specialists":["..."],"reason":"..."}.""".stripMargin)
      .userMessage(request)
      .memory(MemoryProvider.none)
      .thenReplyAs[AgentSelection]
```

Adding a specialist then changes no orchestration code. Two details matter:

**Validate the selection.** A model can name a specialist that does not exist. Filter to
what you actually have, and have a fallback — a selector that names nothing usable
should not stall the plan.

```scala
val known  = selection.specialists.filter(Specialist.All.contains)
val chosen = if known.nonEmpty then known else List(Specialist.Activity)
```

**Keep the selector out of the conversation.** It uses `MemoryProvider.none`, so its
routing chatter never reaches the specialists or the summariser. Routing is plumbing, not
part of the discussion.

## The shared session

Every agent in a plan is addressed with the *same* session id — the workflow's own id:

```scala
private val session = SessionId(context.workflowId)
```

So they accumulate one conversation. The summariser reads it back, filtered to the
specialists:

```scala
effects
  .memory(MemoryProvider.limitedWindow.filtered(
    Specialist.All.foldLeft(MemoryFilter.all)(_ includeFromAgentId _)))
  .thenReply()
```

That filter is why every stored message carries an `agentId`. Sharing is the default
because collaboration is the common case; narrowing is a per-agent decision.

## Enriching an agent's own context

An agent reads what it needs rather than being handed it, which keeps the orchestrator
from having to know what each agent wants:

```scala
final class ActivityAgent extends Agent:
  def consult(request: Request): Effect[String] =
    val preferences = componentClient
      .forKeyValueEntity(EntityId(request.userId))
      .call(PreferencesEntity.get)
      .invoke()

    effects
      .systemMessage("You are a concise activity specialist.")
      .userMessage(s"What should I do in ${request.destination}?")
      .withContext(s"Traveller preferences: ${preferences.summary}")
      .thenReply()
```

`withContext` rather than appending to `userMessage`: memory then records what the user
actually asked, not the whole assembled prompt. Otherwise the next turn's history is
polluted with retrieved documents the user never saw.

## Agents in another language

Everything above holds for a service written in Python, because none of it lives in the
agent's own code: the loop, the session memory, compaction and the model are the sidecar's.
A Python workflow step calls `client.for_agent("selector", session).call("select").invoke(...)`
with the workflow's id as the session, and the agents accumulate one conversation exactly as
the Scala ones do; the process is asked only to plan, to run a tool and to check a guardrail
(`docs/polyglot.md`, *An agent*).

Two things it changes about deploying such a service. The model key is supplied through the
descriptor's `env` as before, but it lands on the **sidecar** container (`ANTHROPIC_*` and
`ANKKA_MODEL_*` are routed there), so the Python process never holds it; and the platform must
carry the `ankka-sidecar` image and tell the operator its name — see the README's *Deploying a
service written in Python*. For tests, `ANKKA_MODEL_SCRIPT` on the sidecar scripts its model the
way `TestModelProvider` does here, and the Python `AgentTestKit` scripts one without a sidecar.

## Observing a run

The workflow's own state answers "what did we decide"; the engine's lifecycle answers
"is it still going, and if not, why not". Both matter — a plan whose domain status reads
`consulting` looks identical whether it is mid-model-call or long dead.

```scala
client.forWorkflow(planId).call(PlannerWorkflow.plan).invoke()        // domain state
client.forWorkflow(planId).lifecycle(PlannerWorkflow).invoke()       // engine state
```

## Testing orchestration

Script the model and assert on the coordination, not the prose: which specialists ran,
that only those ran, that they shared a session, that the summariser saw their
contributions and not the routing.

```scala
model.expectText("""{"specialists":["weather","budget"],"reason":"..."}""")
model.expectToolCall("get_forecast", Json.obj("destination" -> Json.str("Lisbon")))
model.expectText("Lisbon is mild with occasional rain.")
model.expectText("Budget about 90 euros a day.")
model.expectText("A mild, affordable trip.")

// ...

assertEquals(plan.contributions.map(_.specialist), List("weather", "budget"))
assertEquals(plan.contributionFrom("activity"), None)
```

One caveat the sample's own tests ran into: a workflow left mid-flight keeps consuming
the shared scripted model, so a test that starts a plan must let it finish before
returning.
