# Multi-agent orchestration

> Coordinate several agents from a workflow — sequentially, in parallel, or chosen dynamically by another agent — sharing one session, and test the coordination with a scripted model.

Source: https://docs.ankka.cloud/build/multi-agent-orchestration/
Several agents work on one task by being called from a workflow, each step consulting one or more of
them, with every agent addressed by the same session id so they share one conversation. This page shows
three ways to coordinate them — sequential, parallel and dynamic — using the multi-agent planner sample,
which uses all three in one workflow. The whole sample is in
[samples/multi-agent-planner](https://github.com/thinkmorestupidless/ankka/tree/main/samples/multi-agent-planner).

## A workflow, or an autonomous agent

A workflow is the answer when the steps are known in advance — select, consult, summarise — and each step
asks an agent for a decision. When the model should decide what to do next, for as many iterations as it
takes, and the result is read later rather than awaited, an [autonomous agent](autonomous-agents.md) is the
shape instead: it records every iteration, resumes after a crash and stops at its budget, with no workflow
to write.

## Or a blueprint

When the steps are a known pattern — ask, consult several, summarise; search sources, keep what they found;
draft until a critic passes it — they can be written as data rather than code: a [blueprint](blueprints.md)
names the workers and the steps between them, and the platform runs it, keeps each step's result, and
resumes after a crash. The planner's three steps are [a blueprint too](blueprints.md#a-choice-read-from-data),
beside the workflow on this page; a selection read from one step chooses which workers the next consults.
A workflow is still the shape for coordination with conditions in it.

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

**Scala**

```scala
/** Asks the selector which specialists this request needs. */
def selectSpecialistsStep(destination: String): StepEffect =
  val selection = client
    .forAgent(session)
    .call(SelectorAgent.select)
    .invoke(s"Plan a trip to $destination")

  // Ignore anything the model invented that is not a real specialist.
  val known  = selection.specialists.filter(Specialist.All.contains)
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

**Java**

```java
/** Asks the selector which specialists this request needs. */
@Step("select")
public StepEffect selectSpecialists(String destination) {
  var selection =
      agents().call(SelectorAgent::select).invoke("Plan a trip to " + destination);

  // Ignore anything the model invented that is not a real specialist.
  var known = selection.specialists().stream().filter(SPECIALISTS::contains).toList();
  // A selector that names nothing usable should not stall the plan.
  var chosen = known.isEmpty() ? List.of("activity") : known;

  var state = currentState();
  var kept = new SelectorAgent.Selection(chosen, selection.reason());
  return stepEffects()
      .updateState(
          new Plan(
              state.userId(),
              state.destination(),
              "consulting",
              Optional.of(kept),
              state.contributions(),
              state.summary()))
      .thenTransitionTo(PlannerWorkflow::consultSpecialists);
}
```

**Kotlin**

```kotlin
/** Asks the selector which specialists this request needs. */
@Step("select-specialists")
suspend fun selectSpecialists(destination: String): StepEffect {
    val selection = client.forAgent(session).call(agentRef(SelectorAgent::select)).invokeSuspending("Plan a trip to $destination")

    // Ignore anything the model invented that is not a real specialist.
    val known = selection.specialists.filter { it in Specialist.ALL }
    // A selector that names nothing usable should not stall the plan.
    val chosen = known.ifEmpty { listOf(Specialist.ACTIVITY) }

    return stepEffects {
        updateState(currentState().copy(status = PlanState.CONSULTING, selection = selection.copy(specialists = chosen)))
            .thenTransitionTo(PlannerWorkflow::consultSpecialists)
    }
}
```

**Python**

```python
@step("select-specialists")
async def select_specialists(self, destination: str) -> WorkflowStepEffect[PlanState]:
    """Asks the selector which specialists this request needs."""
    selection = await self._agent("selector-agent").call("select").invoke(f"Plan a trip to {destination}", reply=AgentSelection)
    # Ignore anything the model invented that is not a real specialist.
    known = [s for s in selection.specialists if s in SPECIALISTS]
    # A selector that names nothing usable should not stall the plan.
    chosen = known or [ACTIVITY]
    consulting = replace(self.state, status=CONSULTING, selection=replace(selection, specialists=chosen))
    return self.step_effects.update_state(consulting).then_transition_to("consult-specialists")
```

**TypeScript**

```ts
/** Asks the selector which specialists this request needs. */
async selectSpecialistsStep() {
  const selection = await this.client
    .of(SelectorAgent, this.session)
    .call(SelectorAgent.handlers.select)
    .invoke(`Plan a trip to ${this.state.destination}`)

  // Ignore anything the model invented that is not a real specialist.
  const known = selection.specialists.filter((name) => (Specialist.All as readonly string[]).includes(name))
  // A selector that names nothing usable should not stall the plan.
  const chosen = known.length > 0 ? known : [Specialist.Activity]

  return this.stepEffects
    .updateState({ ...this.state, status: "consulting", selection: { ...selection, specialists: chosen } })
    .thenTransitionTo("consult")
}
```

**Rust**

```rust
/// Asks the selector which specialists this request needs.
fn select_specialists(
    plan: &PlanState,
    destination: String,
    ctx: &Context,
) -> StepEffect<PlanState> {
    let asked: Result<String, CommandError> = ctx.client().invoke(
        SelectorAgent,
        PlannerWorkflow::session(ctx),
        "select",
        format!("Plan a trip to {destination}"),
    );
    // The agent's reply is the model's text; the selector asked for JSON, so decode it.
    let selection: AgentSelection = match asked.and_then(|text| {
        ankka::serde_json::from_str(&text).map_err(|e| {
            CommandError::new(ErrorCode::Internal, format!("not a selection: {e}"))
        })
    }) {
        Ok(selection) => selection,
        Err(e) => return step_effects::fail(e),
    };

    // Ignore anything the model invented that is not a real specialist.
    let known: Vec<String> = selection
        .specialists
        .into_iter()
        .filter(|s| specialist::ALL.contains(&s.as_str()))
        .collect();
    let chosen = if known.is_empty() {
        // A selector that names nothing usable should not stall the plan.
        vec![specialist::ACTIVITY.to_string()]
    } else {
        known
    };

    step_effects::update_state(PlanState {
        status: plan_status::CONSULTING.to_string(),
        selection: Some(AgentSelection {
            specialists: chosen,
            reason: selection.reason,
        }),
        ..plan.clone()
    })
    .then_transition_to("consult-specialists")
}
```

**Go**

```go
// selectSpecialists asks the selector which specialists this request needs.
func selectSpecialists(ctx context.Context, s PlanState, destination string) (planStep, error) {
	selection, err := ankka.Invoke[AgentSelection](ctx, "selector-agent", session(ctx), "select",
		"Plan a trip to "+destination)
	if err != nil {
		return planStep{}, err
	}
	// Ignore anything the model invented that is not a real specialist.
	var known []string
	for _, name := range selection.Specialists {
		if slices.Contains(AllSpecialists, name) {
			known = append(known, name)
		}
	}
	if len(known) == 0 {
		// A selector that names nothing usable should not stall the plan.
		known = []string{SpecialistActivity}
	}
	selection.Specialists = known
	s.Status, s.Selection = "consulting", &selection
	return ankka.StepUpdate(s).ThenTransitionTo("consult-specialists", ankka.Unit{}), nil
}
```

Model calls are slow, so give steps that make them a timeout that fits, and a retry if running them again
is harmless:

**Scala**

```scala
override def settings: WorkflowSettings =
  WorkflowSettings.builder
    .timeout(5.minutes)
    // Model calls are slow, and a tool loop can legitimately take a while.
    .defaultStepTimeout(90.seconds)
    .defaultRecovery(RecoverStrategy.maxRetries(1))
    .build
```

**Java**

```java
@Override
protected WorkflowSettings settings() {
  return WorkflowSettings.builder()
      .timeout(Duration.ofMinutes(5))
      // Model calls are slow, and a tool loop can legitimately take a while.
      .defaultStepTimeout(Duration.ofSeconds(90))
      .defaultRecovery(RecoverStrategy.maxRetries(1))
      .build();
}
```

**Kotlin**

```kotlin
override fun settings(): WorkflowSettings =
    WorkflowSettings.builder()
        .timeout(Duration.ofMinutes(5))
        // Model calls are slow, and a tool loop can legitimately take a while.
        .defaultStepTimeout(Duration.ofSeconds(90))
        .defaultRecovery(RecoverStrategy.maxRetries(1))
        .build()
```

**Python**

```python
settings = WorkflowSettings(
    timeout=timedelta(minutes=5),
    # Model calls are slow, and a tool loop can legitimately take a while.
    default_step_timeout=timedelta(seconds=90),
    default_recovery=Recovery(max_retries=1),
)
```

**TypeScript**

```ts
static readonly settings = workflowSettings({
  timeout: Duration.ofMinutes(5),
  // Model calls are slow, and a tool loop can legitimately take a while.
  defaultStepTimeout: Duration.ofSeconds(90),
  defaultRecovery: { maxRetries: 1 },
})
```

**Rust**

```rust
fn settings() -> WorkflowSettings {
    WorkflowSettings::new()
        .timeout(Duration::of_minutes(5))
        // Model calls are slow, and a tool loop can legitimately take a while.
        .default_step_timeout(Duration::of_seconds(90))
        .default_recovery(Recovery::retries(1))
}
```

**Go**

```go
ankka.WorkflowTimeout(5*time.Minute),
// Model calls are slow, and a tool loop can legitimately take a while.
ankka.StepTimeout(90*time.Second),
ankka.ForStep("select-specialists", retryOnce),
ankka.ForStep("consult-specialists", retryOnce),
ankka.ForStep("summarise", retryOnce),
```

## Parallel

Agents that do not depend on each other should not wait for each other. `invokeAsync` issues every call
and returns a `Future` for each; one `ComponentClient.await` per result then collects them. The step takes
as long as the slowest model call, not the sum of all of them.

**Scala**

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

**Java**

```java
/**
 * Consults every chosen specialist at once.
 *
 * <p>{@code invokeAsync} rather than {@code invoke}: the specialists are independent, so
 * waiting for each in turn would make the step as slow as the sum of the model calls instead
 * of the slowest one.
 */
@Step("consult")
public StepEffect consultSpecialists() {
  var state = currentState();
  var chosen = state.selection().map(SelectorAgent.Selection::specialists).orElse(List.of());

  var pending = new LinkedHashMap<String, CompletionStage<String>>();
  for (var specialist : chosen) {
    pending.put(
        specialist,
        switch (specialist) {
          case "weather" ->
              agents()
                  .call(WeatherAgent::ask)
                  .invokeAsync("What is the weather in " + state.destination() + "?");
          case "activity" ->
              agents()
                  .call(ActivityAgent::consult)
                  .invokeAsync(new ActivityAgent.Request(state.userId(), state.destination()));
          case "budget" -> agents().call(BudgetAgent::consult).invokeAsync(state.destination());
          default ->
              CompletableFuture.completedFuture("no specialist named '" + specialist + "'");
        });
  }

  var contributions =
      pending.entrySet().stream()
          .map(
              entry ->
                  new Contribution(
                      entry.getKey(),
                      entry
                          .getValue()
                          .toCompletableFuture()
                          .orTimeout(90, TimeUnit.SECONDS)
                          .join()))
          .toList();

  return stepEffects()
      .updateState(
          new Plan(
              state.userId(),
              state.destination(),
              state.status(),
              state.selection(),
              contributions,
              state.summary()))
      .thenTransitionTo(PlannerWorkflow::summarise);
}
```

**Kotlin**

```kotlin
/**
 * Consults every chosen specialist at once.
 *
 * One coroutine per specialist, on the step's own thread: the specialists are independent, so
 * waiting for each in turn would make the step as slow as the sum of the model calls instead of
 * the slowest one.
 */
@Step("consult-specialists")
suspend fun consultSpecialists(): StepEffect = coroutineScope {
    val state = currentState()
    val chosen = state.selection?.specialists.orEmpty()

    val pending =
        chosen.map { specialist ->
            specialist to
                async {
                    when (specialist) {
                        Specialist.WEATHER ->
                            client.forAgent(session).call(agentRef(WeatherAgent::consult)).invokeSuspending(state.destination)
                        Specialist.ACTIVITY ->
                            client
                                .forAgent(session)
                                .call(ActivityAgent::consult)
                                .invokeSuspending(ActivityAgent.Request(state.userId, state.destination))
                        Specialist.BUDGET ->
                            client.forAgent(session).call(agentRef(BudgetAgent::consult)).invokeSuspending(state.destination)
                        else -> "no specialist named '$specialist'"
                    }
                }
        }

    val contributions = pending.map { (specialist, answer) -> Contribution(specialist, answer.await()) }

    stepEffects {
        updateState(currentState().copy(contributions = contributions)).thenTransitionTo(PlannerWorkflow::summarise)
    }
}
```

**Python**

```python
@step("consult-specialists")
async def consult_specialists(self) -> WorkflowStepEffect[PlanState]:
    """Consults every chosen specialist at once. `asyncio.gather` rather than one await after
    another: the specialists are independent, so waiting for each in turn would make the step
    as slow as the sum of the model calls instead of the slowest one."""
    chosen = self.state.selection.specialists if self.state.selection is not None else []
    answers = await asyncio.gather(*(self._consult(specialist) for specialist in chosen))
    contributions = [Contribution(specialist, answer) for specialist, answer in zip(chosen, answers, strict=True)]
    return self.step_effects.update_state(replace(self.state, contributions=contributions)).then_transition_to("summarise")

async def _consult(self, specialist: str) -> str:
    state = self.state
    if specialist == WEATHER:
        return await self._agent("weather-agent").call("consult").invoke(state.destination, reply=str)
    if specialist == ACTIVITY:
        request = ActivityRequest(state.userId, state.destination)
        return await self._agent("activity-agent").call("consult").invoke(request, reply=str)
    if specialist == BUDGET:
        return await self._agent("budget-agent").call("consult").invoke(state.destination, reply=str)
    return f"no specialist named '{specialist}'"
```

**TypeScript**

```ts
/**
 * Consults every chosen specialist at once.
 *
 * `Promise.all` rather than one `await` after another: the specialists are independent, so waiting
 * for each in turn would make the step as slow as the sum of the model calls instead of the slowest.
 */
async consultSpecialistsStep() {
  const { userId, destination } = this.state
  const chosen = this.state.selection?.specialists ?? []

  const answer = (specialist: string): Promise<string> => {
    switch (specialist) {
      case Specialist.Weather:
        return this.client.of(ForecastAgent, this.session).call(ForecastAgent.handlers.consult).invoke(destination)
      case Specialist.Activity:
        return this.client.of(ActivityAgent, this.session).call(ActivityAgent.handlers.consult).invoke({ userId, destination })
      case Specialist.Budget:
        return this.client.of(BudgetAgent, this.session).call(BudgetAgent.handlers.consult).invoke(destination)
      default:
        return Promise.resolve(`no specialist named '${specialist}'`)
    }
  }
  const answers = await Promise.all(chosen.map(answer))
  const contributions = chosen.map((specialist, i) => ({ specialist, text: answers[i]! }))

  return this.stepEffects.updateState({ ...this.state, contributions }).thenTransitionTo("summarise")
}
```

**Rust**

```rust
/// Consults every chosen specialist, one after another. A module's calls wait for their
/// answer, so the step takes the sum of the model calls; each is journalled with the step.
fn consult_specialists(plan: &PlanState, _: (), ctx: &Context) -> StepEffect<PlanState> {
    let session = PlannerWorkflow::session(ctx);
    let client = ctx.client();
    let chosen = plan
        .selection
        .as_ref()
        .map(|s| s.specialists.clone())
        .unwrap_or_default();

    let mut contributions = Vec::new();
    for specialist in chosen {
        let answer: Result<String, CommandError> = match specialist.as_str() {
            specialist::WEATHER => {
                client.invoke(WeatherAgent, session, "consult", plan.destination.clone())
            }
            specialist::ACTIVITY => {
                let request = ActivityRequest {
                    user_id: plan.user_id.clone(),
                    destination: plan.destination.clone(),
                };
                client.invoke(ActivityAgent, session, "consult", request)
            }
            specialist::BUDGET => {
                client.invoke(BudgetAgent, session, "consult", plan.destination.clone())
            }
            other => Ok(format!("no specialist named '{other}'")),
        };
        match answer {
            Ok(answer) => contributions.push(Contribution { specialist, answer }),
            Err(e) => return step_effects::fail(e),
        }
    }

    step_effects::update_state(PlanState {
        contributions,
        ..plan.clone()
    })
    .then_transition_to("summarise")
}
```

**Go**

```go
// consultSpecialists consults every chosen specialist at once: a goroutine per call rather than
// one call after another, because the specialists are independent, so the step takes as long as
// the slowest model call instead of the sum of them.
func consultSpecialists(ctx context.Context, s PlanState, _ ankka.Unit) (planStep, error) {
	chosen := s.Selection.Specialists
	contributions := make([]Contribution, len(chosen))
	failures := make([]error, len(chosen))
	var pending sync.WaitGroup
	for i, specialist := range chosen {
		pending.Add(1)
		go func() {
			defer pending.Done()
			answer, err := consult(ctx, s, specialist)
			contributions[i], failures[i] = Contribution{Specialist: specialist, Answer: answer}, err
		}()
	}
	pending.Wait()
	if err := errors.Join(failures...); err != nil {
		return planStep{}, err
	}
	s.Contributions = contributions
	return ankka.StepUpdate(s).ThenTransitionTo("summarise", ankka.Unit{}), nil
}

// consult asks one specialist, in the plan's session.
func consult(ctx context.Context, s PlanState, specialist string) (string, error) {
	switch specialist {
	case SpecialistWeather:
		return ankka.Invoke[string](ctx, "weather-agent", session(ctx), "consult", s.Destination)
	case SpecialistActivity:
		return ankka.Invoke[string](ctx, "activity-agent", session(ctx), "consult",
			ActivityRequest{UserID: s.UserID, Destination: s.Destination})
	case SpecialistBudget:
		return ankka.Invoke[string](ctx, "budget-agent", session(ctx), "consult", s.Destination)
	}
	return fmt.Sprintf("no specialist named '%s'", specialist), nil
}
```

This is safe because each agent handles one request per session at a time: the calls run concurrently
across different agents, and no single agent ever has two requests on the session in flight.

## Dynamic

The workflow does not decide which agents to consult; it asks. A selector agent returns a structured
reply, and the workflow runs whatever it names. Adding a specialist then changes no orchestration code.

**Scala**

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

**Java**

```java
/** Routes a planning request to the specialists it needs. */
@ComponentId("selector-agent")
public class SelectorAgent extends Agent {

  public record Selection(List<String> specialists, String reason) {}

  @Command("select")
  public AgentEffect<Selection> select(String request) {
    if (request.isBlank()) return effects().error(Problem.badRequest("nothing to plan"));
    return effects()
        .systemMessage(
            "You route planning requests to specialists. Available specialists: "
                + String.join(", ", PlannerWorkflow.SPECIALISTS)
                + ". Reply with JSON: {\"specialists\":[\"...\"],\"reason\":\"...\"}."
                + " Choose only the specialists the request genuinely needs.")
        .userMessage(request)
        // The selector's own deliberation is not part of the conversation the specialists and
        // the summariser share.
        .memory(Memory.none())
        .thenReply(Selection.class);
  }
}
```

**Kotlin**

```kotlin
@ComponentId("selector-agent")
class SelectorAgent : Agent() {

    @Command("select")
    fun select(request: String): AgentEffect<AgentSelection> =
        if (request.isBlank()) {
            effects { error("nothing to plan") }
        } else {
            effects {
                systemMessage(
                    """You route planning requests to specialists.
                      |Available specialists: ${Specialist.ALL.joinToString(", ")}.
                      |Reply with JSON: {"specialists":["..."],"reason":"..."}.
                      |Choose only the specialists the request genuinely needs."""
                        .trimMargin(),
                )
                    .userMessage(request)
                    // The selector's own deliberation is not part of the conversation the
                    // specialists and summariser share.
                    .memory(Memory.none())
                    .thenReply<AgentSelection>()
            }
        }
}
```

**Python**

```python
class SelectorAgent(Agent):
    """Decides which specialists a request actually needs: the workflow asks, rather than
    hard-coding which agents to consult."""

    component_id = "selector-agent"

    @command("select")
    def select(self, request: str) -> AgentEffect[AgentSelection]:
        if not request.strip():
            return self.effects.error("nothing to plan", ErrorCode.BAD_REQUEST)
        return (
            self.effects.system_message(
                "You route planning requests to specialists.\n"
                f"Available specialists: {', '.join(SPECIALISTS)}.\n"
                'Reply with JSON: {"specialists":["..."],"reason":"..."}.\n'
                "Choose only the specialists the request genuinely needs."
            )
            .user_message(request)
            # The selector's own deliberation is not part of the conversation the
            # specialists and summariser share.
            .memory(False)
            .then_reply_json()
        )
```

**TypeScript**

```ts
export class SelectorAgent extends Agent {
  static readonly componentId = "selector-agent"

  static readonly handlers = {
    select: command("select", s.string, AgentSelection, (a: SelectorAgent, request) => a.select(request)),
  }

  select(request: string) {
    if (request.trim() === "") return this.effects.error("nothing to plan", ErrorCode.BadRequest)
    return (
      this.effects
        .systemMessage(
          `You route planning requests to specialists.
Available specialists: ${Specialist.All.join(", ")}.
Reply with JSON: {"specialists":["..."],"reason":"..."}.
Choose only the specialists the request genuinely needs.`,
        )
        .userMessage(request)
        // The selector's own deliberation is not part of the conversation the specialists and
        // summariser share.
        .memory(false)
        .thenReplyJson<AgentSelection>()
    )
  }
}
```

**Rust**

```rust
pub struct SelectorAgent;

impl SelectorAgent {
    fn select(request: String, _: &Context) -> AgentEffect {
        if request.trim().is_empty() {
            return agent::error(ErrorCode::BadRequest, "nothing to plan");
        }
        agent::system_message(format!(
            "You route planning requests to specialists.\n\
             Available specialists: {}.\n\
             Reply with JSON: {{\"specialists\":[\"...\"],\"reason\":\"...\"}}.\n\
             Choose only the specialists the request genuinely needs.",
            specialist::ALL.join(", ")
        ))
        .user_message(request)
        // The selector's own deliberation is not part of the conversation the specialists and
        // summariser share.
        .session_memory(false)
        .json_shape(r#"{"specialists":["..."],"reason":"..."}"#)
        .then_reply()
    }
}

impl Agent for SelectorAgent {
    const COMPONENT_ID: &'static str = "selector-agent";

    fn handlers() -> AgentHandlers<SelectorAgent> {
        AgentHandlers::new().command("select", SelectorAgent::select)
    }
}
```

**Go**

```go
// SelectorAgent routes a request to specialists. Its own deliberation is not part of the
// conversation the specialists and the summariser share, so it keeps no session memory.
var SelectorAgent = ankka.Agent("selector-agent",
	ankka.Ask("select", func(_ context.Context, request string) (ankka.AgentEffect, error) {
		if strings.TrimSpace(request) == "" {
			return ankka.AgentEffect{}, ankka.Refuse(ankka.BadRequest, "nothing to plan")
		}
		return ankka.SystemMessage("You route planning requests to specialists.\n" +
			"Available specialists: " + strings.Join(AllSpecialists, ", ") + ".\n" +
			`Reply with JSON: {"specialists":["..."],"reason":"..."}.` + "\n" +
			"Choose only the specialists the request genuinely needs.").
			UserMessage(request).
			Memory(false).
			ThenReplyJSON("the specialists to consult, and why"), nil
	}),
)
```

Two details matter.

**Validate the selection.** A model can name a specialist that does not exist. The select step keeps
only names it knows, and falls back to a default when nothing usable is left, so a selector that names
nothing sensible does not stall the plan.

**Keep the selector out of the conversation.** It uses `MemoryProvider.none`, so its routing chatter never
reaches the specialists or the summariser. Routing is plumbing, not part of the discussion.

## The shared session

Every agent in a plan is addressed with the same session id, the workflow's own id:

**Scala**

```scala
private val session = SessionId(context.workflowId)
```

**Java**

```java
/** Every agent in a plan is addressed with the same session id: the workflow's own id. */
private AgentRef agents() {
  return AgentClients.forAgent(context().componentClient(), context().entityId());
}
```

**Kotlin**

```kotlin
/** Every agent in a plan is addressed with the same session id: the workflow's own id. */
private val session: String
    get() = context().entityId()
```

**Python**

```python
class SummaryAgent(Agent):
    """Combines the specialists' answers, read back from the shared session."""

    component_id = "summary-agent"
    role = "summary"

    @command("summarise")
    def summarise(self, destination: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You write short trip briefs. Combine what the specialists said into two sentences.")
            .user_message(f"Write a brief for a trip to {destination}.")
            .then_reply()
        )
```

**TypeScript**

```ts
/** Every agent in a plan is addressed with the same session id, the workflow's own id. */
get session(): string {
  return this.entityId
}
```

**Rust**

```rust
/// Every agent in a plan is addressed with the same session id: the workflow's own.
fn session(ctx: &Context) -> &str {
    ctx.entity_id()
}
```

**Go**

```go
// session is the session every agent in a plan is addressed with: the workflow's own id, so the
// specialists accumulate one conversation and the summariser reads it back.
func session(ctx context.Context) string {
	c, _ := ankka.CommandFrom(ctx)
	return c.EntityID
}
```

So the specialists accumulate one conversation, and a summariser reads it back, filtered to the
specialists' contributions:

**Scala**

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

**Java**

```java
/** Combines what the specialists said, reading it back from the session they all wrote to. */
@ComponentId("summary-agent")
public class SummaryAgent extends Agent {

  @Command("summarise")
  public AgentEffect<String> summarise(String destination) {
    return effects()
        .systemMessage(
            "You write short trip briefs. Combine what the specialists said into two sentences.")
        .userMessage("Write a brief for a trip to " + destination + ".")
        // The whole shared session: the specialists' contributions are in it, and the selector's
        // routing is not, because the selector kept itself out with Memory.none(). The Java
        // Memory has no filter by the agent that wrote a message.
        .memory(Memory.session())
        .thenReply();
  }
}
```

**TypeScript**

```ts
export class SummaryAgent extends Agent {
  static readonly componentId = "summary-agent"

  static readonly handlers = {
    summarise: command("summarise", s.string, s.string, (a: SummaryAgent, destination) =>
      a.effects
        .systemMessage("You write short trip briefs. Combine what the specialists said into two sentences.")
        .userMessage(`Write a brief for a trip to ${destination}.`)
        // The session is shared with the specialists, so their contributions are the history this
        // turn reads; the selector kept its routing out of it with `memory(false)`.
        .memory(true)
        .thenReply(),
    ),
  }
}
```

**Rust**

```rust
pub struct SummaryAgent;

impl SummaryAgent {
    fn summarise(destination: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You write short trip briefs. Combine what the specialists said into two sentences.",
        )
        .user_message(format!("Write a brief for a trip to {destination}."))
        // The session's memory is read whole: the specialists' contributions, and nothing of
        // the selector's, which kept its deliberation out of the session.
        .then_reply()
    }
}

impl Agent for SummaryAgent {
    const COMPONENT_ID: &'static str = "summary-agent";

    fn handlers() -> AgentHandlers<SummaryAgent> {
        AgentHandlers::new().command("summarise", SummaryAgent::summarise)
    }
}
```

**Go**

```go
// SummaryAgent combines what the specialists said, reading them back from the shared session:
// every message the specialists wrote carries its agent's role.
var SummaryAgent = ankka.Agent("summary-agent",
	ankka.Ask("summarise", func(_ context.Context, destination string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage(
			"You write short trip briefs. Combine what the specialists said into two sentences.").
			UserMessage("Write a brief for a trip to " + destination + ".").
			ThenReply(), nil
	}),
)
```

The filter works because every stored message carries the role of the agent that wrote it; each
specialist's companion sets `role`. Sharing is the default because collaboration is the common case, and
narrowing is each agent's own decision through `memory(...)`.

**Scala**

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

**Java**

```java
/** Combines the contributions, reading them back from the shared session. */
@Step("summarise")
public StepEffect summarise() {
  var state = currentState();
  var brief = agents().call(SummaryAgent::summarise).invoke(state.destination());

  return stepEffects()
      .updateState(
          new Plan(
              state.userId(),
              state.destination(),
              "completed",
              state.selection(),
              state.contributions(),
              Optional.of(brief)))
      .thenEnd();
}
```

**Kotlin**

```kotlin
/** Combines the contributions, reading them back from the shared session. */
@Step("summarise")
suspend fun summarise(): StepEffect {
    val brief = client.forAgent(session).call(agentRef(SummaryAgent::summarise)).invokeSuspending(currentState().destination)

    return stepEffects { updateState(currentState().copy(status = PlanState.COMPLETED, summary = brief)).thenEnd() }
}
```

**Python**

```python
@step("summarise")
async def summarise(self) -> WorkflowStepEffect[PlanState]:
    """Combines the contributions, reading them back from the shared session."""
    brief = await self._agent("summary-agent").call("summarise").invoke(self.state.destination, reply=str)
    return self.step_effects.update_state(replace(self.state, status=COMPLETED, summary=brief)).then_end()
```

**TypeScript**

```ts
/** Combines the contributions, reading them back from the shared session. */
async summariseStep() {
  const brief = await this.client
    .of(SummaryAgent, this.session)
    .call(SummaryAgent.handlers.summarise)
    .invoke(this.state.destination)

  return this.stepEffects.updateState({ ...this.state, status: "completed", summary: brief }).thenEnd()
}
```

**Rust**

```rust
/// Combines the contributions, reading them back from the shared session.
fn summarise(plan: &PlanState, _: (), ctx: &Context) -> StepEffect<PlanState> {
    let brief: String = match ctx.client().invoke(
        SummaryAgent,
        PlannerWorkflow::session(ctx),
        "summarise",
        plan.destination.clone(),
    ) {
        Ok(brief) => brief,
        Err(e) => return step_effects::fail(e),
    };

    step_effects::update_state(PlanState {
        status: plan_status::COMPLETED.to_string(),
        summary: Some(brief),
        ..plan.clone()
    })
    .then_end()
}
```

**Go**

```go
// summarise combines the contributions, reading them back from the shared session.
func summarise(ctx context.Context, s PlanState, _ ankka.Unit) (planStep, error) {
	brief, err := ankka.Invoke[string](ctx, "summary-agent", session(ctx), "summarise", s.Destination)
	if err != nil {
		return planStep{}, err
	}
	s.Status, s.Summary = "completed", &brief
	return ankka.StepUpdate(s).ThenEnd(), nil
}
```

## Enriching an agent's own context

An agent reads what it needs rather than being handed it, which keeps the workflow from having to know
what each agent wants. The activity specialist reads the traveller's stored preferences itself and passes
them as context:

**Scala**

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

**Java**

```java
/** Suggests activities for a destination, reading the traveller's preferences itself. */
@ComponentId("activity-agent")
public class ActivityAgent extends Agent {

  public record Request(String userId, String destination) {}

  @Command("consult")
  public AgentEffect<String> consult(Request request) {
    // componentClient() is inherited from Agent; no constructor plumbing needed.
    var preferences =
        componentClient()
            .forKeyValueEntity(request.userId())
            .call(Preferences::get)
            .invoke();

    return effects()
        .systemMessage(
            "You are a concise activity specialist. Suggest two activities, in one sentence.")
        .userMessage("What should I do in " + request.destination() + "?")
        // Preferences are context, not something the user said, so memory records the
        // question, not the whole assembled prompt.
        .withContext("Traveller preferences: " + preferences.summary())
        .thenReply();
  }
}
```

**Kotlin**

```kotlin
@ComponentId("activity-agent")
class ActivityAgent : Agent() {

    data class Request(val userId: String, val destination: String)

    override fun settings(): AgentSettings = AgentSettings.defaults().role(Specialist.ACTIVITY)

    @Command("consult")
    suspend fun consult(request: Request): AgentEffect<String> {
        // `componentClient()` is inherited from `Agent`; no constructor plumbing needed.
        val preferences =
            componentClient().forKeyValueEntity(request.userId).call(PreferencesEntity::get).invokeSuspending()

        return effects {
            systemMessage("You are a concise activity specialist. Suggest two activities, in one sentence.")
                .userMessage("What should I do in ${request.destination}?")
                // Preferences are context, not something the user said: memory records the
                // question, not the whole assembled prompt.
                .withContext("Traveller preferences: ${preferences.summary}")
                .thenReply()
        }
    }
}
```

**Python**

```python
class ActivityAgent(Agent):
    component_id = "activity-agent"
    role = ACTIVITY

    @command("consult")
    async def consult(self, request: ActivityRequest) -> AgentEffect[str]:
        # `client` is the agent's own; no constructor plumbing needed.
        assert self.client is not None
        preferences = await self.client.for_key_value_entity("preferences", request.userId).call("get").invoke(reply=Preferences)
        return (
            self.effects.system_message("You are a concise activity specialist. Suggest two activities, in one sentence.")
            .user_message(f"What should I do in {request.destination}?")
            # Preferences are context, not something the user said — so memory records the
            # question, not the whole assembled prompt.
            .with_context(f"Traveller preferences: {preferences.summary}")
            .then_reply()
        )
```

**TypeScript**

```ts
const ActivityRequest = s.record("ActivityRequest", { userId: s.string, destination: s.string })
export type ActivityRequest = Infer<typeof ActivityRequest>

export class ActivityAgent extends Agent {
  static readonly componentId = "activity-agent"
  static readonly role = Specialist.Activity

  static readonly handlers = {
    consult: command("consult", ActivityRequest, s.string, (a: ActivityAgent, request) => a.consult(request)),
  }

  async consult(request: ActivityRequest) {
    // `client` is inherited from `Agent`; no constructor plumbing needed.
    const preferences = await this.client.of(PreferencesEntity, request.userId).call(PreferencesEntity.handlers.get).invoke()

    return this.effects
      .systemMessage("You are a concise activity specialist. Suggest two activities, in one sentence.")
      .userMessage(`What should I do in ${request.destination}?`)
      // Preferences are context, not something the user said — so memory records the
      // question, not the whole assembled prompt.
      .withContext(`Traveller preferences: ${summary(preferences)}`)
      .thenReply()
  }
}
```

**Rust**

```rust
pub struct ActivityAgent;

impl ActivityAgent {
    fn consult(request: ActivityRequest, ctx: &Context) -> AgentEffect {
        // The client is the context's; no constructor plumbing needed.
        let preferences: Preferences =
            match ctx
                .client()
                .invoke(PreferencesEntity, &request.user_id, "get", ())
            {
                Ok(preferences) => preferences,
                Err(e) => return agent::error(e.code, e.message),
            };

        agent::system_message(
            "You are a concise activity specialist. Suggest two activities, in one sentence.",
        )
        .user_message(format!("What should I do in {}?", request.destination))
        // Preferences are context, not something the user said — so memory records the
        // question, not the whole assembled prompt.
        .context(format!("Traveller preferences: {}", preferences.summary()))
        .then_reply()
    }
}

impl Agent for ActivityAgent {
    const COMPONENT_ID: &'static str = "activity-agent";

    fn handlers() -> AgentHandlers<ActivityAgent> {
        AgentHandlers::new().command("consult", ActivityAgent::consult)
    }
}
```

**Go**

```go
// ActivityRequest is who is asking and where they are going.
type ActivityRequest struct {
	UserID      string `json:"userId"`
	Destination string `json:"destination"`
}

// ActivityAgent suggests activities, reading the traveller's preferences itself.
var ActivityAgent = ankka.Agent("activity-agent",
	// The role every message this agent writes to a session carries.
	ankka.Role(SpecialistActivity),
	ankka.Ask("consult", func(ctx context.Context, req ActivityRequest) (ankka.AgentEffect, error) {
		// The agent reads what it needs through the client; no constructor plumbing needed.
		prefs, err := ankka.Invoke[Preferences](ctx, "preferences", req.UserID, "get", ankka.Unit{})
		if err != nil {
			return ankka.AgentEffect{}, err
		}
		return ankka.SystemMessage(
			"You are a concise activity specialist. Suggest two activities, in one sentence.").
			UserMessage("What should I do in " + req.Destination + "?").
			// Preferences are context, not something the user said, so memory records the
			// question, not the whole assembled prompt.
			WithContext("Traveller preferences: " + prefs.Summary).
			ThenReply(), nil
	}),
)
```

`withContext` rather than appending to `userMessage`: memory then records what the user asked, not the
whole assembled prompt, so later turns' history is not filled with data the user never typed.

## The same patterns outside Scala

The worked example above is the Scala multi-agent planner, because that is the sample this repository
carries; the tabs show the same planner in each other language. Every pattern on this page holds in
Python, TypeScript, Rust and Go unchanged, except the summariser's filtered memory, which is the Scala
SDK's: none of the rest lives in the agent's own code, since the loop, the session memory and the model
are the runtime's. A step calls an agent with the workflow's id as the session, and the agents
accumulate one conversation exactly as the Scala ones do:

**Scala**

```scala
/** Asks the selector which specialists this request needs. */
def selectSpecialistsStep(destination: String): StepEffect =
  val selection = client
    .forAgent(session)
    .call(SelectorAgent.select)
    .invoke(s"Plan a trip to $destination")

  // Ignore anything the model invented that is not a real specialist.
  val known  = selection.specialists.filter(Specialist.All.contains)
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

**Java**

```java
/** Asks the selector which specialists this request needs. */
@Step("select")
public StepEffect selectSpecialists(String destination) {
  var selection =
      agents().call(SelectorAgent::select).invoke("Plan a trip to " + destination);

  // Ignore anything the model invented that is not a real specialist.
  var known = selection.specialists().stream().filter(SPECIALISTS::contains).toList();
  // A selector that names nothing usable should not stall the plan.
  var chosen = known.isEmpty() ? List.of("activity") : known;

  var state = currentState();
  var kept = new SelectorAgent.Selection(chosen, selection.reason());
  return stepEffects()
      .updateState(
          new Plan(
              state.userId(),
              state.destination(),
              "consulting",
              Optional.of(kept),
              state.contributions(),
              state.summary()))
      .thenTransitionTo(PlannerWorkflow::consultSpecialists);
}
```

**Kotlin**

```kotlin
/** Asks the selector which specialists this request needs. */
@Step("select-specialists")
suspend fun selectSpecialists(destination: String): StepEffect {
    val selection = client.forAgent(session).call(agentRef(SelectorAgent::select)).invokeSuspending("Plan a trip to $destination")

    // Ignore anything the model invented that is not a real specialist.
    val known = selection.specialists.filter { it in Specialist.ALL }
    // A selector that names nothing usable should not stall the plan.
    val chosen = known.ifEmpty { listOf(Specialist.ACTIVITY) }

    return stepEffects {
        updateState(currentState().copy(status = PlanState.CONSULTING, selection = selection.copy(specialists = chosen)))
            .thenTransitionTo(PlannerWorkflow::consultSpecialists)
    }
}
```

**Python**

```python
@step("select-specialists")
async def select_specialists(self, destination: str) -> WorkflowStepEffect[PlanState]:
    """Asks the selector which specialists this request needs."""
    selection = await self._agent("selector-agent").call("select").invoke(f"Plan a trip to {destination}", reply=AgentSelection)
    # Ignore anything the model invented that is not a real specialist.
    known = [s for s in selection.specialists if s in SPECIALISTS]
    # A selector that names nothing usable should not stall the plan.
    chosen = known or [ACTIVITY]
    consulting = replace(self.state, status=CONSULTING, selection=replace(selection, specialists=chosen))
    return self.step_effects.update_state(consulting).then_transition_to("consult-specialists")
```

**TypeScript**

```ts
/** Asks the selector which specialists this request needs. */
async selectSpecialistsStep() {
  const selection = await this.client
    .of(SelectorAgent, this.session)
    .call(SelectorAgent.handlers.select)
    .invoke(`Plan a trip to ${this.state.destination}`)

  // Ignore anything the model invented that is not a real specialist.
  const known = selection.specialists.filter((name) => (Specialist.All as readonly string[]).includes(name))
  // A selector that names nothing usable should not stall the plan.
  const chosen = known.length > 0 ? known : [Specialist.Activity]

  return this.stepEffects
    .updateState({ ...this.state, status: "consulting", selection: { ...selection, specialists: chosen } })
    .thenTransitionTo("consult")
}
```

**Rust**

```rust
/// Asks the selector which specialists this request needs.
fn select_specialists(
    plan: &PlanState,
    destination: String,
    ctx: &Context,
) -> StepEffect<PlanState> {
    let asked: Result<String, CommandError> = ctx.client().invoke(
        SelectorAgent,
        PlannerWorkflow::session(ctx),
        "select",
        format!("Plan a trip to {destination}"),
    );
    // The agent's reply is the model's text; the selector asked for JSON, so decode it.
    let selection: AgentSelection = match asked.and_then(|text| {
        ankka::serde_json::from_str(&text).map_err(|e| {
            CommandError::new(ErrorCode::Internal, format!("not a selection: {e}"))
        })
    }) {
        Ok(selection) => selection,
        Err(e) => return step_effects::fail(e),
    };

    // Ignore anything the model invented that is not a real specialist.
    let known: Vec<String> = selection
        .specialists
        .into_iter()
        .filter(|s| specialist::ALL.contains(&s.as_str()))
        .collect();
    let chosen = if known.is_empty() {
        // A selector that names nothing usable should not stall the plan.
        vec![specialist::ACTIVITY.to_string()]
    } else {
        known
    };

    step_effects::update_state(PlanState {
        status: plan_status::CONSULTING.to_string(),
        selection: Some(AgentSelection {
            specialists: chosen,
            reason: selection.reason,
        }),
        ..plan.clone()
    })
    .then_transition_to("consult-specialists")
}
```

**Go**

```go
// selectSpecialists asks the selector which specialists this request needs.
func selectSpecialists(ctx context.Context, s PlanState, destination string) (planStep, error) {
	selection, err := ankka.Invoke[AgentSelection](ctx, "selector-agent", session(ctx), "select",
		"Plan a trip to "+destination)
	if err != nil {
		return planStep{}, err
	}
	// Ignore anything the model invented that is not a real specialist.
	var known []string
	for _, name := range selection.Specialists {
		if slices.Contains(AllSpecialists, name) {
			known = append(known, name)
		}
	}
	if len(known) == 0 {
		// A selector that names nothing usable should not stall the plan.
		known = []string{SpecialistActivity}
	}
	selection.Specialists = known
	s.Status, s.Selection = "consulting", &selection
	return ankka.StepUpdate(s).ThenTransitionTo("consult-specialists", ankka.Unit{}), nil
}
```

In Python `PlanState` and `AgentSelection` are the workflow's own dataclasses; in TypeScript they are its shapes.
The parallel pattern is `invokeAsync` with `ComponentClient.await` in Scala, `asyncio.gather` in Python
and `Promise.all` in TypeScript. A selector that should stay out of the conversation turns memory off:
`memory(MemoryProvider.none)`, `memory(False)` and `memory(false)`.

## Observing a run

A workflow's own state answers "what did we decide"; the engine's lifecycle answers "is it still going,
and if not, why not". Both matter: a plan whose state reads "consulting" looks identical whether a model
call is in flight or the workflow failed long ago.

**Scala**

```scala
get("/{planId}") { (planId: String) =>
  client.forWorkflow(EntityId(planId)).call(PlannerWorkflow.plan).invoke()
}

/** The engine's own view: running, paused, completed or failed, and why. */
get("/{planId}/lifecycle") { (planId: String) =>
  val lifecycle = client.forWorkflow(EntityId(planId)).lifecycle(PlannerWorkflow).invoke()
  s"${lifecycle.status}${lifecycle.failure.map(reason => s": $reason").getOrElse("")}"
}
```

**Java**

```java
/** The domain state: what the workflow itself keeps. */
public static PlannerWorkflow.Plan plan(ComponentClient client, String planId) {
  return client.forWorkflow(planId).call(PlannerWorkflow::plan).invoke();
}

/** The engine's state: running, paused, completed or failed, and at which step. */
public static WorkflowLifecycle lifecycle(ComponentClient client, String planId) {
  return client.forWorkflow(planId).lifecycle(PlannerWorkflow.class).invoke();
}
```

**Kotlin**

```kotlin
@Test
fun `a plan's own state and the engine's lifecycle are read apart`() {
    scriptPlan(listOf(Specialist.BUDGET), "Cheap.")
    planner("p-observe").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))
    completedPlan("p-observe")

    val state = planner("p-observe").call(PlannerWorkflow::plan).invoke() // the domain state
    val lifecycle = planner("p-observe").lifecycle(PlannerWorkflow::class.java).invoke() // the engine's state
    assertEquals(PlanState.COMPLETED, state.status)
    assertTrue(lifecycle.isCompleted, lifecycle.status())
}
```

**Python**

```python
state = await planner.call("plan").invoke(reply=PlanState)  # the domain state
lifecycle = await planner.lifecycle()  # the engine's state
assert state.status == COMPLETED and lifecycle.is_completed
```

**TypeScript**

```ts
const state = await planner.call(PlannerWorkflow.handlers.plan).invoke() // the domain state
const lifecycle = await planner.lifecycle() // the engine's state
assert.equal(state.status, "completed")
assert.equal(lifecycle.status, "completed")
```

**Rust**

```rust
/// A run read two ways: the plan the workflow keeps, from its own `plan` query, and the engine's
/// standing of the run, which runs no handler. They are separate on purpose: the plan says what the
/// workflow decided, the standing whether it is running, paused, completed or failed.
pub fn observe(
    client: &ankka::Client,
    plan_id: &str,
) -> Result<(PlanState, Lifecycle), CommandError> {
    let state: PlanState = client.invoke(PlannerWorkflow, plan_id, "plan", ())?; // the domain state
    let lifecycle = client.lifecycle(PlannerWorkflow, plan_id)?; // the engine's state
    Ok((state, lifecycle))
}
```

**Go**

```go
// ObservePlan reads a run two ways: the plan the workflow keeps, from its own "plan" query, and the
// engine's standing of the run, which runs no handler. The plan says what the workflow decided; the
// standing whether it is running, paused, completed or failed.
func ObservePlan(ctx context.Context, planID string) (PlanState, ankka.WorkflowLifecycle, error) {
	state, err := ankka.Invoke[PlanState](ctx, "planner", planID, "plan", ankka.Unit{}) // the domain state
	if err != nil {
		return PlanState{}, ankka.WorkflowLifecycle{}, err
	}
	lifecycle, err := ankka.Lifecycle(ctx, "planner", planID) // the engine's state
	return state, lifecycle, err
}
```

The local console shows each request's trace, including the time spent waiting on models; see
[The local console](../operate/local-console.md).

## Testing orchestration

Script the model and assert on the coordination, not the prose: which specialists ran, that only those
ran, that they shared a session, and that the summariser saw their contributions and not the routing.
`TestModelProvider` answers from its script in order and fails loudly when the script runs out.

**Scala**

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

**Java**

```java
/** Scripts the selector's structured reply plus one answer per specialist, and the summary. */
static void scriptPlan(
    TestModelProvider model, String destination, List<String> specialists, String summary) {
  var json = specialists.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
  // The selector asks first, so its reply is the script's; the specialists are consulted at
  // once, so each is answered by a rule on what it asks rather than by a place in the script,
  // and the summariser, which asks after them, likewise.
  model.expectText("{\"specialists\":[" + json + "],\"reason\":\"chosen for the test\"}");
  for (var specialist : specialists) {
    switch (specialist) {
      case "weather" ->
          model.whenUserAsks("weather in " + destination, destination + " is mild, with rain.");
      case "activity" ->
          model.whenUserAsks("do in " + destination, "Try the tram and a pastel de nata.");
      case "budget" ->
          model.whenUserAsks("budget per day in " + destination, "Budget about 90 euros a day.");
      default -> throw new AssertionError("unscripted specialist '" + specialist + "'");
    }
  }
  model.whenUserAsks("trip to " + destination, summary);
}
```

**Kotlin**

```kotlin
/** Scripts the selector's structured reply plus one answer per specialist. */
private fun scriptPlan(specialists: List<String>, summary: String) {
    val json = specialists.joinToString(",", "[", "]") { "\"$it\"" }
    model.expectText("""{"specialists":$json,"reason":"chosen for the test"}""")
    specialists.forEach { specialist ->
        when (specialist) {
            // The weather specialist has a tool, so it takes two model turns.
            Specialist.WEATHER ->
                model
                    .expectToolCall("get_forecast", """{"destination":"Lisbon"}""")
                    .expectText("Lisbon is mild with occasional rain.")
            Specialist.ACTIVITY -> model.expectText("Try the tram and a pastel de nata.")
            Specialist.BUDGET -> model.expectText("Budget about 90 euros a day.")
            else -> fail("unscripted specialist '$specialist'")
        }
    }
    model.expectText(summary)
}
```

**Python**

```python
def plan_script() -> str:
    """Scripts the sidecar's model by rule rather than by turn, because the specialists are consulted
    at once and their requests arrive in no fixed order: each rule answers the request whose latest
    user message, or latest tool result, contains its text. The weather specialist has a tool, so it
    takes two model turns."""
    return json.dumps(
        [
            {"when": "Plan a trip to Lisbon", "text": json.dumps({"specialists": [WEATHER, BUDGET], "reason": "chosen for the test"})},
            {"when": "Plan a trip to Porto", "text": json.dumps({"specialists": [ACTIVITY], "reason": "chosen for the test"})},
            {"when": "budget per day", "text": "Budget about 90 euros a day."},
            {"when": "What should I do", "text": "Try the tram and a pastel de nata."},
            {"when": "Write a brief", "text": "A mild, affordable trip."},
            {"when_tool_result": "occasional rain", "text": "Lisbon is mild with occasional rain."},
            {"when": "weather like", "tool": "get_forecast", "arguments": {"destination": "Lisbon"}},
        ]
    )
```

**TypeScript**

```ts
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
```

**Rust**

```rust
#[test]
fn the_selector_keeps_its_deliberation_out_of_the_session() {
    let text = r#"{"specialists":["weather","budget"],"reason":"rain and cost"}"#;
    let model = ScriptedModel::new().expect_text(text);
    let mut kit = AgentTestKit::<SelectorAgent>::new("plan-1", model);
    let reply = kit.call("select", "Plan a trip to Lisbon".to_string());
    assert_eq!(
        reply.plan.memory,
        ankka::proto::agent_plan::Memory::None as i32
    );
    assert!(
        reply
            .plan
            .system
            .unwrap()
            .contains("weather, activity, budget")
    );
    assert_eq!(reply.reply.as_deref(), Some(text));
}

#[test]
fn the_activity_agent_reads_the_travellers_preferences_as_context() {
    // With nothing answering the preferences entity, the agent's handler refuses rather than
    // guessing; the kit reports that refusal as the plan's.
    let mut kit = AgentTestKit::<ActivityAgent>::new("plan-1", ScriptedModel::new());
    let request = ActivityRequest {
        user_id: "u-1".into(),
        destination: "Lisbon".into(),
    };
    let refused = kit.call("consult", request);
    assert_eq!(refused.error.map(|e| e.code), Some(ErrorCode::Unavailable));
}

#[test]
fn a_plan_starts_at_the_selection_and_a_step_without_its_agent_fails() {
    let mut kit = WorkflowTestKit::<PlannerWorkflow>::new("plan-1")
        .with_service(shopping_cart::planner::build());
    let start = Start {
        user_id: "u-1".into(),
        destination: "Lisbon".into(),
    };
    assert_eq!(
        kit.command("start", start.clone()).reply::<Done>(),
        Ok(Done)
    );
    assert_eq!(kit.state().status, plan_status::SELECTING);
    // Started twice is a conflict.
    assert_eq!(
        kit.command("start", start).error().map(|e| e.code),
        Some(ErrorCode::Conflict)
    );
    // The selector is an agent, which the unit kit does not answer: the step's call fails and the
    // step fails with it, as it would with a model that could not be reached.
    match kit.run_step() {
        StepNext::Fail(e) => assert_eq!(e.code, ErrorCode::Unavailable),
        other => panic!("the step ran without its agent: {other:?}"),
    }
}
```

**Go**

```go
// scriptPlan scripts the sidecar's model: the selector's structured reply as the first turn, then
// one standing rule per specialist and one for the summary, each matched on what its agent asks.
// Rules rather than turns, because the specialists are consulted at once, in no fixed order.
func scriptPlan(specialists []string, summary string) string {
	selection := fmt.Sprintf(`{"specialists":%s,"reason":"chosen for the test"}`, mustJSON(specialists))
	// The first plan's selection is the one ordered turn; every later plan's is a rule.
	turns := []map[string]any{{"text": selection}, {"when": "Plan a trip", "text": selection}}
	for _, specialist := range specialists {
		switch specialist {
		case SpecialistActivity:
			turns = append(turns, map[string]any{"when": "What should I do", "text": "Try the tram and a pastel de nata."})
		case SpecialistBudget:
			turns = append(turns, map[string]any{"when": "cost per day", "text": "Budget about 90 euros a day."})
		default:
			panic("unscripted specialist '" + specialist + "'")
		}
	}
	return mustJSON(append(turns, map[string]any{"when": "Write a brief", "text": summary}))
}
```

**Scala**

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

**Java**

```java
/** The selector decides which specialists run, and only those run. */
static PlannerWorkflow.Plan selectorDecides(AnkkaTestKit kit, TestModelProvider model) {
  scriptPlan(model, "Lisbon", List.of("weather", "budget"), "A mild, affordable trip.");

  var started =
      kit.componentClient()
          .forWorkflow("p-select")
          .call(PlannerWorkflow::start)
          .invoke(new PlannerWorkflow.Start("u-1", "Lisbon"));
  expect(started, Done.done());

  var plan = completedPlan(kit, "p-select");
  expect(
      plan.selection().map(SelectorAgent.Selection::specialists),
      Optional.of(List.of("weather", "budget")));
  expect(
      plan.contributions().stream().map(PlannerWorkflow.Contribution::specialist).toList(),
      List.of("weather", "budget"));
  // The activity specialist was never consulted, so it contributed nothing.
  expect(plan.contributionFrom("activity"), Optional.empty());
  expect(plan.summary(), Optional.of("A mild, affordable trip."));
  return plan;
}
```

**Kotlin**

```kotlin
@Test
fun `the selector decides which specialists run, and only those run`() {
    scriptPlan(listOf(Specialist.WEATHER, Specialist.BUDGET), "A mild, affordable trip.")

    assertEquals(Done.DONE, planner("p-select").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon")))

    val plan = completedPlan("p-select")
    assertEquals(listOf(Specialist.WEATHER, Specialist.BUDGET), plan.selection?.specialists)
    assertEquals(listOf(Specialist.WEATHER, Specialist.BUDGET), plan.contributions.map { it.specialist })
    // The activity specialist was never consulted, so it contributed nothing.
    assertNull(plan.contributionFrom(Specialist.ACTIVITY))
    assertEquals("A mild, affordable trip.", plan.summary)
}
```

**Python**

```python
@pytest.mark.slow
async def test_the_selector_decides_which_specialists_run_and_only_those_run() -> None:
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": plan_script()}) as kit:
        planner = kit.client.for_workflow("planner", "p-select")
        assert await planner.call("start").invoke(Start("u-1", "Lisbon"), reply=type(DONE)) == DONE

        plan = await planner.await_end(90, reply=PlanState)
        assert plan.status == COMPLETED
        assert plan.selection is not None and plan.selection.specialists == [WEATHER, BUDGET]
        assert [c.specialist for c in plan.contributions] == [WEATHER, BUDGET]
        # The activity specialist was never consulted, so it contributed nothing.
        assert plan.contribution_from(ACTIVITY) is None
        assert plan.summary == "A mild, affordable trip."
```

**TypeScript**

```ts
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
```

**Go**

```go
if _, err := ankka.Invoke[ankka.Done](ctx, "planner", "p-select", "start",
	StartPlan{UserID: "u-1", Destination: "Lisbon"}); err != nil {
	t.Fatal(err)
}
plan, err := ankka.AwaitEnd[PlanState](ctx, "planner", "p-select", 60*time.Second)
if err != nil {
	t.Fatalf("the plan did not complete: %v\n%s", err, kit.SidecarLogs(ctx))
}
if plan.Selection == nil || !slices.Equal(plan.Selection.Specialists, chosen) {
	t.Fatalf("the selection is %+v", plan.Selection)
}
var consulted []string
for _, c := range plan.Contributions {
	consulted = append(consulted, c.Specialist)
}
if !slices.Equal(consulted, chosen) {
	t.Fatalf("the contributions are %+v", plan.Contributions)
}
// The weather specialist was never consulted, so it contributed nothing.
if _, ok := plan.ContributionFrom(SpecialistWeather); ok {
	t.Fatal("the weather specialist contributed")
}
if plan.Summary == nil || *plan.Summary != "A mild, affordable trip." {
	t.Fatalf("the summary is %v", plan.Summary)
}
```

**Let a workflow finish before the test ends.** A workflow left mid-flight keeps consuming the shared
scripted model, which starves the next test:

**Scala**

```scala
// Let the first plan finish before the test ends. A workflow left mid-flight keeps
// consuming the shared scripted model, which would starve the next test.
val _ = completedPlan("p-twice")
```

**Java**

```java
// Let the first plan finish before the test ends. A workflow left mid-flight keeps
// consuming the shared scripted model, which would starve the next test.
completedPlan(kit, "p-twice");
```

**Kotlin**

```kotlin
// Let the first plan finish before the test ends. A workflow left mid-flight keeps
// consuming the shared scripted model, which would starve the next test.
completedPlan("p-twice")
```

**Python**

```python
# Let the plan finish before the test ends. A workflow left mid-flight keeps consuming
# the scripted model, and a kit stopped under it reports the stop, not the test.
porto = await kit.client.for_workflow("planner", "p-porto").await_end(90, reply=PlanState)
```

**Go**

```go
// Let the second plan finish before the test ends. A workflow left mid-flight keeps consuming
// the shared scripted model, which would starve the next test.
if _, err := ankka.Invoke[ankka.Done](ctx, "planner", "p-twice", "start",
	StartPlan{UserID: "u-1", Destination: "Lisbon"}); err != nil {
	t.Fatal(err)
}
if _, err := ankka.AwaitEnd[PlanState](ctx, "planner", "p-twice", 60*time.Second); err != nil {
	t.Fatal(err)
}
```

See [Testing](testing.md) for the test kits.
