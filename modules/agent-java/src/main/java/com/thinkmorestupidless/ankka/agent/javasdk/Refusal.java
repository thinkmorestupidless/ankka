package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.util.List;
import java.util.function.Predicate;

/** One rule of a {@link JudgedGuardrail}: a question, and the answers to it that refuse. */
public interface Refusal {

  /** Refuses when the probability of yes is at least {@code atLeast}. */
  static Refusal ifYes(YesNoQuestion question, double atLeast) {
    return AgentBridge.ifYes(question, atLeast);
  }

  /** Refuses when one of {@code options} is chosen with at least {@code minConfidence}. */
  @SafeVarargs
  static <T> Refusal ifChosen(ChoiceQuestion<T> question, double minConfidence, T... options) {
    var list = new java.util.ArrayList<T>(options.length);
    for (var option : options) list.add(option);
    return AgentBridge.ifChosen(question, minConfidence, list);
  }

  /** Refuses when the score is at least {@code atLeast}. */
  static Refusal ifScore(ScoreQuestion question, double atLeast) {
    return AgentBridge.ifScore(question, atLeast);
  }

  /** Refuses when {@code refuse} holds of the answer. */
  static <A> Refusal when(Question<A> question, Predicate<A> refuse) {
    return AgentBridge.when(question, refuse);
  }
}
