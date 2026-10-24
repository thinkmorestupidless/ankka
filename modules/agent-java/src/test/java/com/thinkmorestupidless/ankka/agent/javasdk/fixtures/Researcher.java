package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings;
import com.thinkmorestupidless.ankka.agent.javasdk.McpServer;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

/** An agent offering an MCP server's tools, whose results must not leak a password. */
@ComponentId("researcher")
public class Researcher extends Agent {

  /** The server's address, which the suite knows only once the server is listening. */
  public static volatile String serverUrl = "http://127.0.0.1:1/mcp";

  @Override
  protected AgentSettings settings() {
    return AgentSettings.defaults()
        .role("researcher")
        .mcpServer(McpServer.at("docs", serverUrl))
        .resultGuardrail(new NoPasswords());
  }

  @Command("research")
  public AgentEffect<String> research(String question) {
    return effects().userMessage(question).thenReply();
  }
}
