package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.McpServer;

/** MCP server declarations as a deployed service writes them. */
public final class TicketServers {

  private TicketServers() {}

  // docs:start credential
  /** Found at ANKKA_MCP_TICKETS_URL, and sent the credential in ANKKA_MCP_TICKETS_TOKEN. */
  public static McpServer tickets() {
    return McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN");
  }
  // docs:end credential

  /** Every tool of this one waits for a person; its variable replaces the address given here. */
  public static McpServer guarded() {
    return McpServer.at("guarded", "https://guarded.example.com/mcp").requiresApproval();
  }
}
