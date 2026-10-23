package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.time.Duration;

/**
 * A multi-agent process described as data: its workers, its steps and what each reads, an input
 * shape, and optionally a schedule and budgets. A blueprint names tools, models, guardrails,
 * questions and handlers by the names the module's {@link BlueprintRegistry} gives them, so one
 * written as JSON elsewhere is the same as one built here. Each method returns a new blueprint.
 *
 * <pre>{@code
 * Blueprint.named("keeper")
 *     .worker(Worker.named("writer").instructions("List papers.").budget(2))
 *     .step(Step.named("papers").ask("writer").reads("input").result(Shape.arr(Shape.string())))
 *     .step(Step.named("keep").call("keep_paper").reads("papers"));
 * }</pre>
 */
public interface Blueprint {

  static Blueprint named(String name) {
    return AgentBridge.blueprint(name);
  }

  /** A blueprint from its JSON form; refused, naming why, when the text is not one. */
  static Blueprint fromJson(String json) {
    return AgentBridge.blueprintFromJson(json);
  }

  /** A blueprint from a JSON resource on the class path. */
  static Blueprint fromResource(String path) {
    return AgentBridge.blueprintFromResource(path);
  }

  String name();

  /** The shape a run's input must have; an object with nothing required by default. */
  Blueprint input(Shape shape);

  Blueprint worker(Worker worker);

  Blueprint step(Step step);

  /** Runs this blueprint on a cadence, as well as when started. */
  Blueprint schedule(Schedule schedule);

  /** The model calls one run may make, across every step. */
  Blueprint runBudget(int modelCalls);

  /** How long one run may take. */
  Blueprint timeLimit(Duration limit);

  /** The JSON form. */
  String json();

  /** The digest of the canonical form: two blueprints with one digest are one version. */
  String digest();
}
