package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

// docs:start provider-imports
import com.thinkmorestupidless.ankka.agent.javasdk.AgentModule;
import com.thinkmorestupidless.ankka.agent.javasdk.Agents; // the module every agent is hosted by
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProviders;
// docs:end provider-imports

/** The models this service answers with, built once. */
public final class Providers {

  private Providers() {}

  // docs:start two-providers
  // Both read their key from the environment. Without it a provider still exists and every call to
  // it fails naming the variable, so the service starts with either key missing.
  public static final ModelProvider CLAUDE = ModelProviders.anthropic("claude-opus-5");
  public static final ModelProvider GPT = ModelProviders.openAi("gpt-6.1-sol");

  // Any server speaking either protocol, under a name the service chooses.
  public static final ModelProvider LOCAL = ModelProviders.preset("local", "ollama", "qwen3:8b");

  // One is the default; a handler names another with `effects().model(...)`.
  public static AgentModule module() {
    return Agents.module().withModelProvider(CLAUDE);
  }
  // docs:end two-providers

  // docs:start installation-model
  /** The installation's own model server, by the model's tag; its address and credential are the platform's. */
  public static ModelProvider installation() {
    return ModelProviders.installation("qwen3:0.6b");
  }
  // docs:end installation-model
}
