package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.agent.javasdk.RuleResult;
import com.thinkmorestupidless.ankka.agent.javasdk.Task;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType;
import java.util.List;

// docs:start task-type
/** The kinds of work the cart answerer accepts. */
public final class CartTasks {

  private CartTasks() {}

  /** An answer, and what the agent looked at to reach it. */
  public record Answer(String answer, List<String> sources) {}

  /**
   * {@code "answer"} is the wire name, written into every task of this type: renaming the field
   * changes nothing stored.
   */
  public static final TaskType<Answer> ANSWER =
      Task.named("answer")
          .describedAs("Answer a question about a shopping cart, citing what you looked at")
          .resultConformsTo(Answer.class)
          .rule(
              "cites-sources",
              answer ->
                  answer.sources().isEmpty()
                      ? RuleResult.rejected("say which tools you used in sources")
                      : RuleResult.accepted());
}
// docs:end task-type
