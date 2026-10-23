package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef;
import com.thinkmorestupidless.ankka.javasdk.client.StepRef;

/** A workflow command's change, waiting for its transition or its reply. */
public interface WorkflowBuilder<S> {

  <W, I> WorkflowBuilder<S> transitionTo(StepRef<W, I> step, I input);

  <W> WorkflowBuilder<S> transitionTo(NoInputStepRef<W> step);

  <R> Effect<R> thenReply(R reply);

  Effect<S> thenReplyState();

  Effect<Done> thenDone();
}
