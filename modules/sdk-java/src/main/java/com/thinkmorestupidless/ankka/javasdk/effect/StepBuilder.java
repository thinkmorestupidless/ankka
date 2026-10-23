package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef;
import com.thinkmorestupidless.ankka.javasdk.client.StepRef;
import java.time.Duration;

/** A state a step is about to write, waiting for what comes next. */
public interface StepBuilder<S> {

  <W, I> StepEffect thenTransitionTo(StepRef<W, I> step, I input);

  <W> StepEffect thenTransitionTo(NoInputStepRef<W> step);

  StepEffect thenPause();

  <W> StepEffect thenPause(Duration after, NoInputStepRef<W> onTimeout);

  StepEffect thenEnd();

  StepEffect thenFail(String message);
}
