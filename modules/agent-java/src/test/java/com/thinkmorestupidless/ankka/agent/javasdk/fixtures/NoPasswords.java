package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import java.util.Optional;

/** A guardrail written in Java: refuses a tool's result that mentions a password. */
public final class NoPasswords implements Guardrail {

  @Override
  public String name() {
    return "no-passwords";
  }

  @Override
  public Optional<String> checkResult(String text) {
    return text.toLowerCase().contains("password")
        ? Optional.of("a result may not carry a password")
        : Optional.empty();
  }
}
