package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/** The judgment providers the platform offers. */
public final class JudgmentProviders {

  private JudgmentProviders() {}

  /** TypeSafe AI's Jev at its current model, reading {@code TYPESAFE_API_KEY}. */
  public static JudgmentProvider jev() {
    return AgentBridge.jev(java.util.Optional.empty());
  }

  /** Jev at {@code model}, reading {@code TYPESAFE_API_KEY} and {@code TYPESAFE_BASE_URL}. */
  public static JudgmentProvider jev(String model) {
    return AgentBridge.jev(java.util.Optional.of(model));
  }
}
