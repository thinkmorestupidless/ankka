package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.time.Duration;

/**
 * An MCP server whose tools an agent offers its model, connected when the service starts. Its
 * tools are named {@code mcp__<server>__<tool>}.
 */
public interface McpServer {

  /** A server found at the variable {@code ANKKA_MCP_<NAME>_URL}. */
  static McpServer named(String name) {
    return AgentBridge.mcpNamed(name);
  }

  /** A server at {@code url}. */
  static McpServer at(String name, String url) {
    return AgentBridge.mcpAt(name, url);
  }

  /** Another service's MCP endpoint at {@code path}, called as this service. */
  static McpServer service(String name, String service, String path) {
    return AgentBridge.mcpService(name, service, path);
  }

  /** Sends the header {@code name} with the value of the variable {@code variable}. */
  McpServer header(String name, String variable);

  /** Every tool of this server waits for a person's decision before it runs. */
  McpServer requiresApproval();

  /** As {@link #requiresApproval()}, refused by the platform when {@code within} passes. */
  McpServer requiresApproval(Duration within);
}
