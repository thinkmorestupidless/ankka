package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef;
import com.thinkmorestupidless.ankka.javasdk.client.StepRef;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/**
 * The effects a workflow's command returns: a state to write, a step to transition to, or both,
 * then the reply; or a reply or an error that changes nothing.
 */
public interface WorkflowEffects<S> {

  WorkflowBuilder<S> updateState(S state);

  <W, I> WorkflowBuilder<S> transitionTo(StepRef<W, I> step, I input);

  <W> WorkflowBuilder<S> transitionTo(NoInputStepRef<W> step);

  /** Delete the workflow: a wait for its end fails, and its id may start a new one. */
  WorkflowBuilder<S> delete();

  <R> ReadOnlyEffect<R> reply(R reply);

  <R> ReadOnlyEffect<R> error(String message);

  <R> ReadOnlyEffect<R> error(Problem problem);
}
