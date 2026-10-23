package com.thinkmorestupidless.ankka.agent.javasdk;

/** A task about to be created. Each method returns a new builder. */
public interface TaskBuilder<R> {

  /** Creates the task under {@code taskId} rather than a random one. */
  TaskBuilder<R> withId(String taskId);

  /** Attaches content the model reads with the instructions. */
  TaskBuilder<R> attach(String name, String contentType, String content);

  /** Attaches a reference to content held elsewhere. */
  TaskBuilder<R> attachReference(String name, String contentType, String uri);

  /**
   * Starts the task only once each of {@code taskIds} has completed; one that fails or is cancelled
   * cancels this one.
   */
  TaskBuilder<R> dependsOn(String... taskIds);

  /** Creates the task, unassigned, and answers its id. */
  String create();
}
