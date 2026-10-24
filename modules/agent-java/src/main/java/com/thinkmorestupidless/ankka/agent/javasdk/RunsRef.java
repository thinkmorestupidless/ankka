package com.thinkmorestupidless.ankka.agent.javasdk;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Runs of the service's blueprints, from {@link AgentClients#runs}. */
public interface RunsRef {

  /** Starts the current version of {@code blueprint} on {@code inputJson}, under {@code runId}. */
  RunSnapshot start(String blueprint, String inputJson, String runId);

  RunSnapshot get(String runId);

  /** Waits until the run has ended, for {@code timeout} at most. */
  RunSnapshot await(String runId, Duration timeout);

  RunSnapshot cancel(String runId);

  /**
   * A run as it was read: its status as a wire word ({@code running}, {@code waiting-for-decision},
   * {@code completed}, {@code failed} or {@code cancelled}), the step it is on, each step so far,
   * the model calls it made, and why it failed or was cancelled.
   */
  record RunSnapshot(
      String runId,
      String blueprint,
      int version,
      String input,
      String status,
      Optional<String> step,
      List<StepSnapshot> steps,
      int modelCalls,
      Optional<String> reason) {

    public boolean ended() {
      return status.equals("completed") || status.equals("failed") || status.equals("cancelled");
    }

    public Optional<StepSnapshot> stepNamed(String name) {
      return steps.stream().filter(s -> s.name().equals(name)).findFirst();
    }
  }

  /** One step of a run: whether it ended, its result as JSON, and the model calls it made. */
  record StepSnapshot(String name, boolean ended, Optional<String> result, int modelCalls) {}
}
