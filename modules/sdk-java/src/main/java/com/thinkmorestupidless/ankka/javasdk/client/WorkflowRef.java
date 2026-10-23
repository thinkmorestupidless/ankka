package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** One workflow instance: its commands, called as an entity's are, and a wait for its end. */
public interface WorkflowRef extends EntityRef {

  /**
   * Waits at most {@code timeout} for the workflow to end, and answers its final state. A workflow
   * that fails, or is deleted, is a {@link CommandException} with status 500; one still running when
   * the time is up is a {@link CommandException} with status 504, and runs on.
   */
  <S> S awaitEnd(Class<? extends Workflow<S>> workflow, Duration timeout);

  <S> CompletionStage<S> awaitEndAsync(Class<? extends Workflow<S>> workflow, Duration timeout);

  /**
   * A wait for this workflow's end as server-sent events, for an {@code @Sse} route to return as it
   * is: a {@code heartbeat} event while the wait goes on, then exactly one {@code ended} (the state,
   * as JSON), {@code failed} (the code, the message, and a failed workflow's step, reason and whether
   * it was deleted) or {@code timed-out}, and the stream ends.
   */
  Flow.Publisher<SseEvent> awaitEndEvents(Class<? extends Workflow<?>> workflow, Duration timeout);

  /** Where this workflow has got to, answered by its engine: {@code lifecycle(W.class).invoke()}. */
  Invocation<Void, WorkflowLifecycle> lifecycle(Class<? extends Workflow<?>> workflow);

  /**
   * Sends the command, then waits for the workflow's end, as one call answered with the state it
   * ended with, within {@code timeout} of the command being sent. A refusal of the command is
   * thrown at once and no wait begins; the command's own reply is not kept.
   */
  <W extends Workflow<S>, S, I, O> Invocation<I, S> callThenAwaitEnd(
      CommandRef<W, I, O> handler, Duration timeout);

  /** {@link #callThenAwaitEnd(CommandRef, Duration)}, for a handler that takes nothing. */
  <W extends Workflow<S>, S, O> Invocation<Void, S> callThenAwaitEnd(
      NoArgRef<W, O> handler, Duration timeout);
}
