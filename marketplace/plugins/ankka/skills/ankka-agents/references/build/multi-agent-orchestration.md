# Multi-agent orchestration

> Coordinate several agents from a workflow — sequentially, in parallel, or chosen dynamically by another agent — sharing one session, and test the coordination with a scripted model.

Source: https://docs.ankka.cloud/build/multi-agent-orchestration/
Several agents work on one task by being called from a workflow, each step consulting one or more of
them, with every agent addressed by the same session id so they share one conversation. This page shows
three ways to coordinate them — sequential, parallel and dynamic — using the multi-agent planner sample,
which uses all three in one workflow. The whole sample is in
[samples/multi-agent-planner](https://github.com/thinkmorestupidless/ankka/tree/main/samples/multi-agent-planner).

## Why a workflow, not a chain of calls

An agent can call another agent through the component client; that is just a method call, and it is fine
until the process dies halfway through. A workflow journals each step's outcome before the next step
begins, so a plan that has consulted two specialists and then crashed resumes at the third, rather than
consulting — and paying for — the first two again.

The rule of thumb: if the coordination costs money or takes minutes, it belongs in a workflow. See
[Workflows](workflows.md) for the component itself.

## Sequential

Steps that depend on each other run one after another, and each transition is a durable commit point.
The planner's first step asks a selector agent which specialists a request needs, records the answer, and
moves on:

```scala
/** Asks the selector which specialists this request needs. */
def selectSpecialistsStep(destination: String): StepEffect =
  val selection = client
    .forAgent(session)
    .call(SelectorAgent.select)
    .invoke(s"Plan a trip to $destination")

  // Ignore anything the model invented that is not a real specialist.
  val known = selection.specialists.filter(Specialist.All.contains)
  val chosen =
    if known.nonEmpty then known
    // A selector that names nothing usable should not stall the plan.
    else List(Specialist.Activity)

  stepEffects
    .updateState(
      currentState.copy(
        status = PlanState.Consulting,
        selection = Some(selection.copy(specialists = chosen))
      )
    )
    .thenTransitionTo(PlannerWorkflow.consultSpecialists)
```

Model calls are slow, so give steps that make them a timeout that fits, and a retry if running them again
is harmless:

```scala
override def settings: WorkflowSettings =
  WorkflowSettings.builder
    .timeout(5.minutes)
    // Model calls are slow, and a tool loop can legitimately take a while.
    .defaultStepTimeout(90.seconds)
    .defaultRecovery(RecoverStrategy.maxRetries(1))
    .build
```

## Parallel

Agents that do not depend on each other should not wait for each other. `invokeAsync` issues every call
and returns a `Future` for each; one `ComponentClient.await` per result then collects them. The step takes
as long as the slowest model call, not the sum of all of them.

```scala
/**
 * Consults every chosen specialist at once.
 *
 * `invokeAsync` rather than `invoke`: the specialists are independent, so waiting for each in
 * turn would make the step as slow as the sum of the model calls instead of the slowest one.
 */
def consultSpecialistsStep: StepEffect =
  val state  = currentState
  val chosen = state.selection.map(_.specialists).getOrElse(Nil)

  val pending = chosen.map { specialist =>
    val answer = specialist match
      case Specialist.Weather =>
        client.forAgent(session).call(WeatherAgent.consult).invokeAsync(state.destination)
      case Specialist.Activity =>
        client
          .forAgent(session)
          .call(ActivityAgent.consult)
          .invokeAsync(ActivityAgent.Request(state.userId, state.destination))
      case Specialist.Budget =>
        client.forAgent(session).call(BudgetAgent.consult).invokeAsync(state.destination)
      case other =>
        scala.concurrent.Future.successful(s"no specialist named '$other'")
    specialist -> answer
  }

  val contributions = pending.map { (specialist, pendingAnswer) =>
    Contribution(
      specialist,
      ComponentClient.await(pendingAnswer, 90.seconds)
    )
  }

  stepEffects
    .updateState(currentState.copy(contributions = contributions))
    .thenTransitionTo(PlannerWorkflow.summarise)
```

This is safe because each agent handles one request per session at a time: the calls run concurrently
across different agents, and no single agent ever has two requests on the session in flight.

## Dynamic

The workflow does not decide which agents to consult; it asks. A selector agent returns a structured
reply, and the workflow runs whatever it names. Adding a specialist then changes no orchestration code.

```scala
final class SelectorAgent extends Agent:

  def select(request: String): Effect[AgentSelection] =
    if request.isBlank then effects.error("nothing to plan", ErrorCode.BadRequest)
    else
      effects
        .systemMessage(
          s"""You route planning requests to specialists.
             |Available specialists: ${Specialist.All.mkString(", ")}.
             |Reply with JSON: {"specialists":["..."],"reason":"..."}.
             |Choose only the specialists the request genuinely needs.""".stripMargin
        )
        .userMessage(request)
        // The selector's own deliberation is not part of the conversation the
        // specialists and summariser share.
        .memory(MemoryProvider.none)
        .thenReplyAs[AgentSelection]

object SelectorAgent extends Agent.Companion[SelectorAgent](ComponentId("selector-agent")):
  def create(context: AgentContext) = new SelectorAgent
  val select                        = command("select")(_.select)
```

Two details matter.

**Validate the selection.** A model can name a specialist that does not exist. The select step keeps
only names it knows, and falls back to a default when nothing usable is left, so a selector that names
nothing sensible does not stall the plan.

**Keep the selector out of the conversation.** It uses `MemoryProvider.none`, so its routing chatter never
reaches the specialists or the summariser. Routing is plumbing, not part of the discussion.

## The shared session

Every agent in a plan is addressed with the same session id, the workflow's own id:

```scala
private val session = SessionId(context.workflowId)
```

So the specialists accumulate one conversation, and a summariser reads it back, filtered to the
specialists' contributions:

```scala
final class SummaryAgent extends Agent:

  def summarise(destination: String): Effect[String] =
    effects
      .systemMessage(
        "You write short trip briefs. Combine what the specialists said into two sentences."
      )
      .userMessage(s"Write a brief for a trip to $destination.")
      .memory(
        MemoryProvider.limitedWindow.filtered(
          Specialist.All.foldLeft(MemoryFilter.all)((filter, id) => filter.includeFromAgentId(id))
        )
      )
      .thenReply()
```

The filter works because every stored message carries the role of the agent that wrote it; each
specialist's companion sets `role`. Sharing is the default because collaboration is the common case, and
narrowing is each agent's own decision through `memory(...)`.

```scala
/** Combines the contributions, reading them back from the shared session. */
def summariseStep: StepEffect =
  val brief = client
    .forAgent(session)
    .call(SummaryAgent.summarise)
    .invoke(currentState.destination)

  stepEffects
    .updateState(currentState.copy(status = PlanState.Completed, summary = Some(brief)))
    .thenEnd
```

## Enriching an agent's own context

An agent reads what it needs rather than being handed it, which keeps the workflow from having to know
what each agent wants. The activity specialist reads the traveller's stored preferences itself and passes
them as context:

```scala
final class ActivityAgent extends Agent:

  def consult(request: ActivityAgent.Request): Effect[String] =
    // `componentClient` is inherited from `Agent`; no constructor plumbing needed.
    val preferences = componentClient
      .forKeyValueEntity(EntityId(request.userId))
      .call(PreferencesEntity.get)
      .invoke()

    effects
      .systemMessage(
        "You are a concise activity specialist. Suggest two activities, in one sentence."
      )
      .userMessage(s"What should I do in ${request.destination}?")
      // Preferences are context, not something the user said — so memory records the
      // question, not the whole assembled prompt.
      .withContext(s"Traveller preferences: ${preferences.summary}")
      .thenReply()
```

`withContext` rather than appending to `userMessage`: memory then records what the user asked, not the
whole assembled prompt, so later turns' history is not filled with data the user never typed.

## Agents in Python

Everything on this page holds for a Python service, because none of it lives in the agent's own code: the
loop, the session memory and the model are the sidecar's. A Python workflow step calls an agent with the
workflow's id as the session, and the agents accumulate one conversation exactly as the Scala ones do.
In this excerpt `Plan` and `Selection` are the workflow's own dataclasses:

```python
@step("select")
async def select(self) -> WorkflowStepEffect[Plan]:
    selection = await self.context.client.for_agent("selector", self.entity_id).call("select").invoke(self.state.destination, reply=Selection)
    return self.step_effects.update_state(replace(self.state, selection=selection)).then_transition_to("consult")
```

`asyncio.gather` over several `invoke` calls is the parallel pattern. A selector that should stay out of
the conversation uses `memory(False)`.

## Observing a run

A workflow's own state answers "what did we decide"; the engine's lifecycle answers "is it still going,
and if not, why not". Both matter: a plan whose state reads "consulting" looks identical whether a model
call is in flight or the workflow failed long ago.

```scala
client.forWorkflow(planId).call(PlannerWorkflow.plan).invoke()      // the domain state
client.forWorkflow(planId).lifecycle(PlannerWorkflow).invoke()      // the engine's state
```

The local console shows each request's trace, including the time spent waiting on models; see
[The local console](../operate/local-console.md).

## Testing orchestration

Script the model and assert on the coordination, not the prose: which specialists ran, that only those
ran, that they shared a session, and that the summariser saw their contributions and not the routing.
`TestModelProvider` answers from its script in order and fails loudly when the script runs out.

```scala
/** Scripts the selector's structured reply plus one answer per specialist. */
private def scriptPlan(specialists: List[String], summary: String): Unit =
  val json = specialists.map(s => s"\"$s\"").mkString("[", ",", "]")
  model.expectText(s"""{"specialists":$json,"reason":"chosen for the test"}""")
  specialists.foreach {
    case Specialist.Weather =>
      // The weather specialist has a tool, so it takes two model turns.
      model
        .expectToolCall("get_forecast", Json.obj("destination" -> Json.str("Lisbon")))
        .expectText("Lisbon is mild with occasional rain.")
    case Specialist.Activity => model.expectText("Try the tram and a pastel de nata.")
    case Specialist.Budget   => model.expectText("Budget about 90 euros a day.")
    case other               => fail(s"unscripted specialist '$other'")
  }
  model.expectText(summary): Unit
```

```scala
test("the selector decides which specialists run, and only those run") {
  scriptPlan(List(Specialist.Weather, Specialist.Budget), "A mild, affordable trip.")

  assertEquals(
    planner("p-select")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      ),
    Done
  )

  val plan = completedPlan("p-select")
  assertEquals(
    plan.selection.map(_.specialists),
    Some(List(Specialist.Weather, Specialist.Budget))
  )
  assertEquals(plan.contributions.map(_.specialist), List(Specialist.Weather, Specialist.Budget))
  // The activity specialist was never consulted, so it contributed nothing.
  assertEquals(plan.contributionFrom(Specialist.Activity), None)
  assertEquals(plan.summary, Some("A mild, affordable trip."))
}
```

**Let a workflow finish before the test ends.** A workflow left mid-flight keeps consuming the shared
scripted model, which starves the next test:

```scala
// Let the first plan finish before the test ends. A workflow left mid-flight keeps
// consuming the shared scripted model, which would starve the next test.
val _ = completedPlan("p-twice")
```

See [Testing](testing.md) for the test kits.
