package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef;
import com.thinkmorestupidless.ankka.javasdk.client.StepRef;
import java.time.Duration;

/** The effects a workflow step returns. */
public interface StepEffects<S> {

  /** Replace the state, then say what comes next. */
  StepBuilder<S> updateState(S state);

  <W, I> StepEffect thenTransitionTo(StepRef<W, I> step, I input);

  <W> StepEffect thenTransitionTo(NoInputStepRef<W> step);

  /** Wait for a command to transition the workflow on. */
  StepEffect thenPause();

  /** Wait for a command, and transition to {@code onTimeout} when none comes within {@code after}. */
  <W> StepEffect thenPause(Duration after, NoInputStepRef<W> onTimeout);

  StepEffect thenEnd();

  /** Fail the workflow; a wait for its end is told {@code message}. */
  StepEffect thenFail(String message);
}
