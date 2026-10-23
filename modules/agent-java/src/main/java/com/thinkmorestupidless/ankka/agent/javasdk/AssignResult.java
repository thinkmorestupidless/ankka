package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;
import java.util.Map;

/** Which tasks an instance queued, and why each other one was refused. */
public record AssignResult(List<String> accepted, Map<String, String> refused) {

  public boolean allAccepted() {
    return refused.isEmpty();
  }
}
