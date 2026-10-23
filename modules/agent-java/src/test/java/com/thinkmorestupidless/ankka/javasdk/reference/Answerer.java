package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent;
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.RuleResult;
import com.thinkmorestupidless.ankka.agent.javasdk.Task;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** An autonomous agent, whose tools act through the client. */
@ComponentId("answerer")
public class Answerer extends AutonomousAgent {

  public record Answer(String answer, List<String> sources) {}

  /** The answers the steady rule has thrown on once. */
  static final Set<String> FLAKY_SEEN = ConcurrentHashMap.newKeySet();

  public static final TaskType<Answer> ANSWER =
      Task.named("answer")
          .describedAs("Answer a question, citing what you looked up")
          .resultConformsTo(Answer.class)
          .rule(
              "cites-sources",
              a ->
                  a.sources().isEmpty()
                      ? RuleResult.rejected("sources must not be empty")
                      : RuleResult.accepted())
          // Throws the first time it sees "flaky-once": a rule that fails once, then decides.
          .rule(
              "steady",
              a -> {
                if (a.answer().equals("flaky-once") && FLAKY_SEEN.add(a.answer()))
                  throw new IllegalStateException("the rule threw");
                return RuleResult.accepted();
              });

  public static final class Tools {
    private final ComponentClient client;

    Tools(ComponentClient client) {
      this.client = client;
    }

    @Tool(value = "lookup", description = "Looks up how many things were recorded under an id.")
    public String lookup(@Describe("The id to look up.") String id) {
      if (id.isEmpty()) throw new IllegalArgumentException("an id is needed");
      var entity = client.forEventSourcedEntity(id);
      entity.call(ConformanceEntity::record).invoke("looked-up");
      return "count for " + id + " is " + entity.call(ConformanceEntity::count).invoke();
    }

    // Waits for a person before it runs; what it records is counted as a run.
    @Tool(
        value = "sensitive_lookup",
        description = "Looks up what was recorded under an id. A person approves every one.",
        requiresApproval = true)
    public String sensitiveLookup(@Describe("The id to look up.") String id) {
      var entity = client.forEventSourcedEntity(id);
      entity.call(ConformanceEntity::record).invoke("sensitive");
      return "sensitive count for " + id + " is " + entity.call(ConformanceEntity::count).invoke();
    }
  }

  @Override
  protected AutonomousDefinition definition() {
    return AutonomousDefinition.describedAs("Answers questions")
        .guardrails(Guardrail.forbidding("no-secrets", "sk-"))
        .accepts(ANSWER, 4);
  }

  @Override
  protected List<Object> tools() {
    return List.of(new Tools(componentClient()));
  }
}
