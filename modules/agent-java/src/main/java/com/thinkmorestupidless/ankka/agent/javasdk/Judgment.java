package com.thinkmorestupidless.ankka.agent.javasdk;

/**
 * The answers a judgment gave, read through the questions that were asked: {@code
 * judgment.answer(ROUTE).choice()}. A handler that replies with one declares {@code
 * AgentEffect<Judgment>}; it is stored and sent as the Scala SDK's judgment is.
 */
public interface Judgment {

  /**
   * The answer to {@code question}, typed. Throws when the judgment holds no answer to it, or an
   * answer of another kind: a question that was not asked has no answer.
   */
  <A> A answer(Question<A> question);

  boolean contains(Question<?> question);

  /** The model that answered. */
  String model();
}
