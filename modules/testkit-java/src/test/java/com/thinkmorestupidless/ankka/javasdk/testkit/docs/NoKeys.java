package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import java.util.Optional;

// docs:start custom-guardrail
/** Refuses a reply that carries what looks like a key. */
public final class NoKeys implements Guardrail {

  @Override
  public String name() {
    return "no-keys";
  }

  @Override
  public Optional<String> checkOutput(String text) {
    return text.contains("sk-")
        ? Optional.of("the reply contains what looks like a key")
        : Optional.empty();
  }
}
// docs:end custom-guardrail
