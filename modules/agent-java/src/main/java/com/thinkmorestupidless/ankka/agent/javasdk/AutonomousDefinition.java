package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/**
 * What an autonomous agent is: what it is for, how it behaves, and the task types it accepts, each
 * with the most iterations it may spend on one task. Each method returns a new definition.
 */
public interface AutonomousDefinition {

  /** A definition that starts from what the agent is for, which the model is told. */
  static AutonomousDefinition describedAs(String description) {
    return AgentBridge.definition(description);
  }

  /** Standing instructions, beside each task's own. */
  AutonomousDefinition instructions(String text);

  AutonomousDefinition guardrails(Guardrail... guardrails);

  /** The model this agent's iterations ask, rather than the module's. */
  AutonomousDefinition model(ModelProvider provider);

  AutonomousDefinition mcpServers(McpServer... servers);

  /** Guardrails on what an MCP server's tool answered. */
  AutonomousDefinition resultGuardrails(Guardrail... guardrails);

  /** Accepts tasks of {@code type}, ten iterations at most on each. */
  AutonomousDefinition accepts(TaskType<?> type);

  /** Accepts tasks of {@code type}, {@code maxIterationsPerTask} iterations at most on each. */
  AutonomousDefinition accepts(TaskType<?> type, int maxIterationsPerTask);
}
