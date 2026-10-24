package com.thinkmorestupidless.ankka.agent.javasdk;

/**
 * One interaction with a model, being described. Immutable: each method returns a new builder, and
 * nothing is asked of a model until the platform runs the effect a {@code then} method returns.
 */
public interface AgentBuilder {

  AgentBuilder systemMessage(String text);

  AgentBuilder userMessage(String text);

  /**
   * Context the user did not type, such as a retrieved document; kept apart from the user's message
   * so the session records what the user said.
   */
  AgentBuilder withContext(String text);

  AgentBuilder model(ModelProvider provider);

  /** How the session's history is read and written; the whole session by default. */
  AgentBuilder memory(Memory memory);

  /**
   * Tools the model may call: a {@link FunctionTool}, an object whose {@link Tool} methods are
   * tools, or a class with a no-argument constructor whose {@link Tool} methods are, built once per
   * turn.
   */
  AgentBuilder tools(Object... tools);

  /** Checks on what goes into and comes out of the model, run in order; the first refusal wins. */
  AgentBuilder guardrails(Guardrail... guardrails);

  /** Bounds this turn's tool round trips, in place of the agent's own setting. */
  AgentBuilder maxToolCallSteps(int steps);

  /** Replies with the model's text. */
  AgentEffect<String> thenReply();

  /**
   * Replies with the model's JSON read as {@code type}, by the same codec the service's records are
   * read with. The schema is not sent: say what is wanted in the system message.
   */
  <R> AgentEffect<R> thenReply(Class<R> type);

  /** Streams the reply as it is written, every turn's text included. */
  StreamEffect thenStream();
}
