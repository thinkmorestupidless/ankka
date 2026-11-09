package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** The planner's tests, written as the orchestration page describes them. */
public final class PlannerChecks {

  private PlannerChecks() {}

  // docs:start script
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
  // docs:end script

  // docs:start assert
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
  // docs:end assert

  /** Two plans in a row on one scripted model. */
  static PlannerWorkflow.Plan plansTwice(AnkkaTestKit kit, TestModelProvider model) {
    scriptPlan(model, "Lisbon", List.of("activity"), "A trip of trams.");
    kit.componentClient()
        .forWorkflow("p-twice")
        .call(PlannerWorkflow::start)
        .invoke(new PlannerWorkflow.Start("u-1", "Lisbon"));
    // docs:start drain
    // Let the first plan finish before the test ends. A workflow left mid-flight keeps
    // consuming the shared scripted model, which would starve the next test.
    completedPlan(kit, "p-twice");
    // docs:end drain

    scriptPlan(model, "Porto", List.of("budget"), "A cheap trip.");
    kit.componentClient()
        .forWorkflow("p-again")
        .call(PlannerWorkflow::start)
        .invoke(new PlannerWorkflow.Start("u-1", "Porto"));
    return completedPlan(kit, "p-again");
  }

  /** Waits for the plan to end, and reads its final state. */
  static PlannerWorkflow.Plan completedPlan(AnkkaTestKit kit, String planId) {
    return kit.componentClient()
        .forWorkflow(planId)
        .awaitEnd(PlannerWorkflow.class, Duration.ofSeconds(30));
  }

  private static void expect(Object actual, Object expected) {
    if (!Objects.equals(actual, expected))
      throw new AssertionError("expected " + expected + " but was " + actual);
  }
}
