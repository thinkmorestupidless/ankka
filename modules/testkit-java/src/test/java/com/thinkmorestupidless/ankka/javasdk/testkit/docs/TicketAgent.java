package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.McpServer;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

// docs:start mcp-servers
/** An agent offering the tools of a ticketing system's MCP server. */
@ComponentId("ticket-agent")
public class TicketAgent extends Agent {

  /** Where the ticketing server is; a test gives the address of a server it started. */
  public static volatile String ticketsUrl = "https://tickets.example.com/mcp";

  @Override
  protected AgentSettings settings() {
    return AgentSettings.defaults()
        // Given by address here; the variable ANKKA_MCP_TICKETS_URL replaces it when set.
        .mcpServer(McpServer.at("tickets", ticketsUrl))
        // What an MCP server answers is checked before the model is told it.
        .resultGuardrail(Guardrail.forbidding("no-instructions", "(?i)ignore what you were told"));
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects().systemMessage("You handle support tickets.").userMessage(question).thenReply();
  }
}
// docs:end mcp-servers
