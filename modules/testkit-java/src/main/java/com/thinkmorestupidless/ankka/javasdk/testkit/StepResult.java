package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.WorkflowLifecycle;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What one command to a workflow, or one pause timing out, came to in the workflow test kit: the
 * reply or the refusal, each step the workflow moved to, and where it stands now.
 */
public interface StepResult<S, O> {

  /** The reply; throws {@link AssertionError} when the command was refused or replied nothing. */
  O reply();

  boolean isError();

  /** The refusal's message; throws {@link AssertionError} when the command succeeded. */
  String error();

  /** The refusal as the caller would see it; throws {@link AssertionError} when it succeeded. */
  Problem problem();

  /** The steps that ran after the command, in order; a retried step once per attempt. */
  List<String> steps();

  /** The workflow's state after the steps ran. */
  S state();

  /** Where the workflow stands: running, paused, completed or failed, and why. */
  WorkflowLifecycle lifecycle();

  /** How long the workflow paused for, when it paused with a timeout. */
  Optional<Duration> pausedFor();

  /** The step the pause's timeout runs, when it paused with one. */
  Optional<String> onTimeout();
}
