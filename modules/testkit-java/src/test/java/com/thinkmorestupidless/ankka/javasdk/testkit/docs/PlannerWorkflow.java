package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentRef;
import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Plans a trip: a selector chooses specialists, they are consulted at once, a summariser ends. */
@ComponentId("planner")
public class PlannerWorkflow extends Workflow<PlannerWorkflow.Plan> {

  public static final List<String> SPECIALISTS = List.of("weather", "activity", "budget");

  public record Start(String userId, String destination) {}

  public record Contribution(String specialist, String answer) {}

  public record Plan(
      String userId,
      String destination,
      String status,
      Optional<SelectorAgent.Selection> selection,
      List<Contribution> contributions,
      Optional<String> summary) {

    public Optional<String> contributionFrom(String specialist) {
      return contributions.stream()
          .filter(c -> c.specialist().equals(specialist))
          .map(Contribution::answer)
          .findFirst();
    }
  }

  @Override
  protected Plan emptyState() {
    return new Plan("", "", "new", Optional.empty(), List.of(), Optional.empty());
  }

  // docs:start settings
  @Override
  protected WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .timeout(Duration.ofMinutes(5))
        // Model calls are slow, and a tool loop can legitimately take a while.
        .defaultStepTimeout(Duration.ofSeconds(90))
        .defaultRecovery(RecoverStrategy.maxRetries(1))
        .build();
  }
  // docs:end settings

  // docs:start session
  /** Every agent in a plan is addressed with the same session id: the workflow's own id. */
  private AgentRef agents() {
    return AgentClients.forAgent(context().componentClient(), context().entityId());
  }
  // docs:end session

  @Command("start")
  public Effect<Done> start(Start start) {
    var plan =
        new Plan(
            start.userId(),
            start.destination(),
            "selecting",
            Optional.empty(),
            List.of(),
            Optional.empty());
    return effects()
        .updateState(plan)
        .transitionTo(PlannerWorkflow::selectSpecialists, start.destination())
        .thenDone();
  }

  // docs:start select
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
  // docs:end select

  // docs:start parallel
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
  // docs:end parallel

  // docs:start summarise
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
  // docs:end summarise

  @Query("plan")
  public ReadOnlyEffect<Plan> plan() {
    return effects().reply(currentState());
  }
}
