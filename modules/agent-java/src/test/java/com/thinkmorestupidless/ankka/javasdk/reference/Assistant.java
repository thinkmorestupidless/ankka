package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentBuilder;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.annotations.Stream;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;

/** An agent whose tool acts through the client. */
@ComponentId("assistant")
public class Assistant extends Agent {

  /** Looks up how many things were recorded under an id, recording that it looked. */
  public static final class Lookup {
    private final ComponentClient client;

    Lookup(ComponentClient client) {
      this.client = client;
    }

    @Tool(value = "lookup", description = "Looks up how many things were recorded under an id.")
    public String lookup(@Describe("The id to look up.") String id) {
      if (id.isEmpty()) throw new IllegalArgumentException("an id is needed");
      var entity = client.forEventSourcedEntity(id);
      entity.call(ConformanceEntity::record).invoke("looked-up");
      return "count for " + id + " is " + entity.call(ConformanceEntity::count).invoke();
    }
  }

  private AgentBuilder describe(String question) {
    return effects()
        .systemMessage("You are helpful.")
        .userMessage(question)
        .tools(new Lookup(componentClient()))
        .guardrails(Guardrail.forbidding("no-secrets", "sk-"));
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return describe(question).thenReply();
  }

  @Stream("stream")
  public StreamEffect stream(String question) {
    return describe(question).thenStream();
  }

  /** Answered by the model the service named {@code second}, not its default. */
  @Command("ask-second")
  public AgentEffect<String> askSecond(String question) {
    return effects()
        .model(JavaAgentReference.second())
        .systemMessage("You are helpful.")
        .userMessage(question)
        .thenReply();
  }
}
