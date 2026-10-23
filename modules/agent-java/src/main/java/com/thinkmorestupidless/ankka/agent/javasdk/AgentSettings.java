package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.ArrayList;
import java.util.List;

/**
 * What an agent is beside its handlers: its role in a session other agents share, how many tool
 * round trips one request may take, the MCP servers whose tools it offers its model, and the checks
 * an MCP server's results must pass. Immutable; each method returns a new one.
 */
public final class AgentSettings {

  private final String role;
  private final int maxToolCallSteps;
  private final List<McpServer> mcpServers;
  private final List<Guardrail> resultGuardrails;

  private AgentSettings(
      String role, int maxToolCallSteps, List<McpServer> mcpServers, List<Guardrail> results) {
    this.role = role;
    this.maxToolCallSteps = maxToolCallSteps;
    this.mcpServers = List.copyOf(mcpServers);
    this.resultGuardrails = List.copyOf(results);
  }

  /** The component id as the role, a hundred tool steps, no MCP server. */
  public static AgentSettings defaults() {
    return new AgentSettings("", 100, List.of(), List.of());
  }

  /** The role messages are recorded under; the component id when empty. */
  public AgentSettings role(String role) {
    return new AgentSettings(role, maxToolCallSteps, mcpServers, resultGuardrails);
  }

  public AgentSettings maxToolCallSteps(int steps) {
    return new AgentSettings(role, steps, mcpServers, resultGuardrails);
  }

  /** An MCP server whose tools the model is offered; one that cannot be reached fails the start. */
  public AgentSettings mcpServer(McpServer server) {
    List<McpServer> servers = new ArrayList<>(mcpServers);
    servers.add(server);
    return new AgentSettings(role, maxToolCallSteps, servers, resultGuardrails);
  }

  /**
   * A check every MCP server's result passes before the model is told it; a refused result never
   * reaches the model or the session. The agent's own tools' results are not checked.
   */
  public AgentSettings resultGuardrail(Guardrail guardrail) {
    List<Guardrail> guardrails = new ArrayList<>(resultGuardrails);
    guardrails.add(guardrail);
    return new AgentSettings(role, maxToolCallSteps, mcpServers, guardrails);
  }

  public String role() {
    return role;
  }

  public int maxToolCallSteps() {
    return maxToolCallSteps;
  }

  public List<McpServer> mcpServers() {
    return mcpServers;
  }

  public List<Guardrail> resultGuardrails() {
    return resultGuardrails;
  }
}
