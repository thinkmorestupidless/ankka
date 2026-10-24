package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent;
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition;
import com.thinkmorestupidless.ankka.agent.javasdk.RuleResult;
import com.thinkmorestupidless.ankka.agent.javasdk.Task;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An autonomous agent that answers questions with a cited answer, checked by a rule, and writes
 * summaries as text. Its lookup tool records which task called it.
 */
@ComponentId("answerer")
public class Answerer extends AutonomousAgent {

  public record Answer(@Describe("the answer") String text, List<String> sources) {}

  public static final TaskType<Answer> ANSWER =
      Task.named("answer")
          .describedAs("Answer a question, citing a source")
          .resultConformsTo(Answer.class)
          .rule(
              "cited",
              answer ->
                  answer.sources().isEmpty()
                      ? RuleResult.rejected("cite at least one source")
                      : RuleResult.accepted());

  public static final TaskType<String> SUMMARY =
      Task.named("summary").describedAs("Summarise a text");

  /** A type no answerer accepts. */
  public static final TaskType<String> POEM = Task.named("poem").describedAs("Write a poem");

  /** The tasks the lookup tool was called in. */
  public static final List<String> LOOKED_UP = new CopyOnWriteArrayList<>();

  public static final class Lookup {
    @Tool(value = "lookup", description = "Looks a question up in the guide")
    public String lookup() {
      LOOKED_UP.add(currentTask().orElse("none"));
      return "the guide says 42";
    }
  }

  @Override
  protected AutonomousDefinition definition() {
    return AutonomousDefinition.describedAs("Answers questions from the guide")
        .instructions("Look the question up before answering.")
        .accepts(ANSWER, 5)
        .accepts(SUMMARY);
  }

  @Override
  protected List<Object> tools() {
    return List.of(new Lookup());
  }
}
