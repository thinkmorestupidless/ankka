package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.function.Function;

/**
 * A kind of task: its wire name, what the model is told it is, the type of its result, and the
 * rules a result must pass before the task completes. A rejected result is told to the model, which
 * may try again within the task's budget.
 */
public interface TaskType<R> {

  String name();

  String description();

  /**
   * This type, its result the record {@code type}: the model completes a task with the record's
   * components, and a caller reads the record. Declared before any rule, which is typed by it.
   */
  <S> TaskType<S> resultConformsTo(Class<S> type);

  /** This type with one more rule, run after those before it. */
  TaskType<R> rule(String name, Function<R, RuleResult> check);
}
