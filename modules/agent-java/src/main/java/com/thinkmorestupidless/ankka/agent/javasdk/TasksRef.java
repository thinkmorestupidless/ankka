package com.thinkmorestupidless.ankka.agent.javasdk;

/** Where tasks are created, from {@link AgentClients#tasks}. */
public interface TasksRef {

  /** A task of {@code type}, created once {@link TaskBuilder#create()} is called. */
  <R> TaskBuilder<R> create(TaskType<R> type, String instructions);
}
