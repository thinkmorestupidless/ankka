package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/**
 * Where a task type begins: {@code Task.named("answer").describedAs("Answer a question")}, a type
 * whose result is text, then {@code .resultConformsTo(Answer.class)} for a record and {@code
 * .rule(...)} for each check its result must pass. The name is a wire name: tasks are stored by it.
 */
public final class Task {

  private Task() {}

  public static Named named(String name) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("a task type needs a name");
    }
    return new Named(name);
  }

  /** A task type with a name and nothing else yet. */
  public static final class Named {

    private final String name;

    private Named(String name) {
      this.name = name;
    }

    /** The type, whose result is text, with what the model is told a task of it is. */
    public TaskType<String> describedAs(String description) {
      return AgentBridge.taskType(name, description);
    }
  }
}
