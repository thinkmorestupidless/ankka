package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/** The start of every effect an agent's handler returns. */
public interface AgentEffects {

  /** The instruction that frames the conversation. */
  AgentBuilder systemMessage(String text);

  /** What the user said, recorded in the session as theirs. */
  AgentBuilder userMessage(String text);

  /** Answers this interaction with {@code provider} rather than the module's default. */
  AgentBuilder model(ModelProvider provider);

  /** Asks typed questions of a state, answered by the module's judgment provider. */
  JudgmentBuilder judgment();

  /** Refuses the request without calling a model, as a bad request. */
  <R> AgentEffect<R> error(String message);

  /** Refuses the request without calling a model, with the problem's status. */
  <R> AgentEffect<R> error(Problem problem);
}
