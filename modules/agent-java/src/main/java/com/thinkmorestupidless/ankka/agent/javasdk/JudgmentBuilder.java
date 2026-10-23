package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.function.Function;

/**
 * Describes a judgment, inside an agent's handler: {@code
 * effects().judgment().state(ticket.text()).question(ROUTE, REFUND).thenReply()}. Building one asks
 * nothing; a judgment is asked of the state it is given and nothing else, so it has no memory, tool
 * or guardrail.
 */
public interface JudgmentBuilder {

  /** The text the questions are about. */
  JudgmentBuilder state(String text);

  /** A record the questions are about, sent to the provider as structured data. */
  JudgmentBuilder state(Object value);

  /** Adds questions to ask; all are asked in one request. */
  JudgmentBuilder question(Question<?>... questions);

  /** Asks this provider rather than the module's. */
  JudgmentBuilder provider(JudgmentProvider provider);

  /** Replies with the judgment. */
  AgentEffect<Judgment> thenReply();

  /**
   * Replies with a value computed from the judgment, so the caller never sees it. {@code reply}
   * should be a pure function of the judgment.
   */
  <T> AgentEffect<T> thenReply(Function<Judgment, T> reply);
}
