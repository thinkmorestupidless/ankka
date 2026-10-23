package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.util.List;

/**
 * A typed question a judgment asks of a state: a choice, a score or a yes/no, answered with
 * probabilities. {@code A} is its answer's type, so {@code judgment.answer(question)} is typed. The
 * id and every option key are wire names: what a stored judgment is read by.
 *
 * <pre>{@code
 * static final ChoiceQuestion<Team> ROUTE = Question.choice("route", "Which team handles this?",
 *     ChoiceOption.of(Team.BILLING, "billing", "Payments, invoicing, refunds"),
 *     ChoiceOption.of(Team.TECHNICAL, "technical", "Bugs, outages, integrations"));
 * }</pre>
 */
public interface Question<A> {

  /** The question's wire id. */
  String id();

  String instructions();

  /** A choice among two or more options, its answer read back as the chosen option's value. */
  @SafeVarargs
  static <T> ChoiceQuestion<T> choice(
      String id, String instructions, ChoiceOption<T>... options) {
    var list = new java.util.ArrayList<ChoiceOption<T>>(options.length);
    for (var option : options) list.add(option);
    return AgentBridge.choice(id, instructions, list);
  }

  /** A score over two to ten ordered levels, the first of which is level 0. */
  static ScoreQuestion score(String id, String instructions, String... levels) {
    return AgentBridge.score(id, instructions, List.of(levels));
  }

  /** A yes or no, answered as the probability that the answer is yes. */
  static YesNoQuestion yesNo(String id, String instructions) {
    return AgentBridge.yesNo(id, instructions);
  }
}
