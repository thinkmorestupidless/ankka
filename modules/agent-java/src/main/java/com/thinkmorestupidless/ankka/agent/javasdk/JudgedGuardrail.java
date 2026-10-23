package com.thinkmorestupidless.ankka.agent.javasdk;

/**
 * A guardrail that asks questions of the text rather than matching it, made by {@link
 * Guardrail#judged}. It refuses when any of its rules is met, and is answered by the module's
 * judgment provider unless given one. A provider that cannot answer refuses nothing: the request is
 * answered unavailable, not forbidden.
 */
public interface JudgedGuardrail extends Guardrail {

  /** Rules asked of what the model is given. */
  JudgedGuardrail onInput(Refusal... rules);

  /** Rules asked of what the model wrote. */
  JudgedGuardrail onOutput(Refusal... rules);

  /** Rules asked of what an MCP server's tool answered. */
  JudgedGuardrail onResult(Refusal... rules);

  JudgedGuardrail provider(JudgmentProvider provider);
}
