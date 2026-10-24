package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.util.Optional;

/**
 * A check on what goes into a model, what comes out of it, or what an MCP server's tool answered.
 * A check answers the reason it refuses, or nothing to allow; each allows by default, so a
 * guardrail overrides only the stage it cares about. An input refusal costs no model call; an
 * output refusal leaves nothing in the session.
 */
public interface Guardrail {

  /** The name a refusal is reported under. */
  String name();

  default Optional<String> checkInput(String text) {
    return Optional.empty();
  }

  default Optional<String> checkOutput(String text) {
    return Optional.empty();
  }

  /** Run only for an agent that lists this guardrail in its settings' result guardrails. */
  default Optional<String> checkResult(String text) {
    return Optional.empty();
  }

  /** Refuses input, output or a result in which {@code regex} is found. */
  static Guardrail forbidding(String name, String regex) {
    return AgentBridge.forbidding(name, regex);
  }

  /** Refuses input longer than {@code maxChars}, before it costs a model call. */
  static Guardrail maxInputLength(int maxChars) {
    return AgentBridge.maxInputLength(maxChars);
  }

  /** A guardrail that asks questions of the text; add its rules with {@code onInput} and the rest. */
  static JudgedGuardrail judged(String name) {
    return AgentBridge.judged(name);
  }
}
