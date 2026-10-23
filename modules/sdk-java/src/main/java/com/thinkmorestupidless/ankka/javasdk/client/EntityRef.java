package com.thinkmorestupidless.ankka.javasdk.client;

import java.time.Duration;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;

/** One entity instance, to call. */
public interface EntityRef {

  /** The handler {@code handler} names, which takes one argument. */
  <C, I, O> Invocation<I, O> call(CommandRef<C, I, O> handler);

  /** The handler {@code handler} names, which takes none; invoke it with {@code invoke()}. */
  <C, O> Invocation<Void, O> call(NoArgRef<C, O> handler);

  /**
   * A handler named by its component id and wire name, for a call the compiler cannot check: the
   * escape hatch when the component's class is not on this classpath.
   */
  <I, O> Invocation<I, O> call(
      String componentId, String wireName, Class<I> input, Class<O> output);

  /** How the copy of the replicated entity {@code entity} at this installation stands. */
  ReplicationStatus replication(Class<? extends EventSourcedEntity<?, ?>> entity);

  /**
   * Waits until a handover of this replicated entity's home to {@code installation} has
   * completed, as the copy at this installation records it, for at most {@code timeout}. Throws
   * {@link CommandException} with 503 when the handover was abandoned or never asked for, and 504
   * when it is still waiting when {@code timeout} passes.
   */
  void awaitHandover(
      Class<? extends EventSourcedEntity<?, ?>> entity, String installation, Duration timeout);
}
