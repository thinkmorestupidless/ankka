package com.thinkmorestupidless.ankka.javasdk.client;

import java.util.Map;
import java.util.Optional;

/**
 * Where a workflow has got to, as its engine records it: answered by the runtime for every
 * workflow, whether or not its author exposed a query.
 */
public record WorkflowLifecycle(
    String status,
    Optional<String> pendingStep,
    Map<String, Integer> retries,
    Optional<String> failure) {

  public boolean isRunning() {
    return status.equals("Running");
  }

  public boolean isPaused() {
    return status.equals("Paused");
  }

  public boolean isCompleted() {
    return status.equals("Completed");
  }

  public boolean isFailed() {
    return status.equals("Failed");
  }

  public boolean isTerminal() {
    return isCompleted() || isFailed();
  }
}
