package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** One workflow instance: its commands, called as an entity's are, and a wait for its end. */
public interface WorkflowRef extends EntityRef {

  /**
   * Waits at most {@code timeout} for the workflow to end, and answers its final state. A workflow
   * that fails, or is deleted, is a {@link CommandException} with status 500; one still running when
   * the time is up is a {@link CommandException} with status 504, and runs on.
   */
  <S> S awaitEnd(Class<? extends Workflow<S>> workflow, Duration timeout);

  <S> CompletionStage<S> awaitEndAsync(Class<? extends Workflow<S>> workflow, Duration timeout);
}
