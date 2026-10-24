package com.thinkmorestupidless.ankka.agent.javasdk;

import java.time.Duration;

/** One task, from {@link AgentClients#forTask}. */
public interface TaskRef {

  /** The task as it is now, its result as it was stored: text, or a record's JSON. */
  TaskSnapshot<String> get();

  /** The task as it is now, its result read as {@code type}'s; refused for a task of another type. */
  <R> TaskSnapshot<R> get(TaskType<R> type);

  /** Waits until the task has completed, failed or been cancelled, for {@code timeout} at most. */
  <R> TaskSnapshot<R> await(TaskType<R> type, Duration timeout);

  /** Cancels the task, telling the instance working on it. */
  void cancel(String reason);
}
