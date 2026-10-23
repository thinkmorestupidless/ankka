package com.thinkmorestupidless.ankka.agent.javasdk;

/** What a task type's rule says of a result: accepted, or rejected with the reason. */
public sealed interface RuleResult {

  record Accepted() implements RuleResult {}

  record Rejected(String reason) implements RuleResult {}

  static RuleResult accepted() {
    return new Accepted();
  }

  static RuleResult rejected(String reason) {
    return new Rejected(reason);
  }
}
