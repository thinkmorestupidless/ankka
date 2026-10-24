package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/**
 * What a service's blueprints may name, each by a name of its own: tools, MCP servers, models,
 * guardrails, judgment questions and handlers, and the blueprints the service registers at start.
 * A name registered twice is refused. Each method returns a new registry.
 */
public interface BlueprintRegistry {

  static BlueprintRegistry empty() {
    return AgentBridge.registry();
  }

  /** {@link FunctionTool}s, objects whose {@link Tool} methods are tools, or classes built to be. */
  BlueprintRegistry tools(Object... tools);

  BlueprintRegistry mcpServers(McpServer... servers);

  /** A model a worker names; {@code default} is the module's and cannot be registered. */
  BlueprintRegistry model(String name, ModelProvider provider);

  BlueprintRegistry guardrail(String name, Guardrail guardrail);

  /** Questions a judge step or a verdict names, by their ids. */
  BlueprintRegistry questions(Question<?>... questions);

  BlueprintRegistry handlers(BlueprintHandler... handlers);

  /** Blueprints registered when the service starts. */
  BlueprintRegistry carrying(Blueprint... blueprints);
}
