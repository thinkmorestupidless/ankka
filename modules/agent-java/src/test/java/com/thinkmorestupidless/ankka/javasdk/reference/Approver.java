package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.McpServer;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;

/** An agent whose tools wait for a person, with two MCP servers. */
@ComponentId("approver")
public class Approver extends Agent {

  // Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
  @Override
  protected AgentSettings settings() {
    return AgentSettings.defaults()
        .mcpServer(McpServer.named("tickets"))
        .mcpServer(McpServer.named("guarded").requiresApproval())
        .resultGuardrail(Guardrail.forbidding("no-instructions", "(?i)ignore what you were told"));
  }

  public static final class Tools {
    private final ComponentClient client;
    private final ServiceClients services;

    Tools(ComponentClient client, ServiceClients services) {
      this.client = client;
      this.services = services;
    }

    @Tool(
        value = "refund",
        description = "Refunds what was recorded under an id. A person approves every refund.",
        requiresApproval = true)
    public String refund(@Describe("The id to refund.") String id) {
      client.forEventSourcedEntity(id).call(ConformanceEntity::record).invoke("refunded");
      return "refunded " + id;
    }

    // A tool calls another service as this service: the called service's ACL can admit it by name.
    @Tool(value = "ask_scripted", description = "Asks the scripted service for what is at a path.")
    public String askScripted(@Describe("The path to ask for.") String path) {
      return services.of("scripted").getText(path);
    }
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You approve refunds.")
        .userMessage(question)
        .tools(new Tools(componentClient(), services()))
        .thenReply();
  }
}
