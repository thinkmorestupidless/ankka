package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.util.Optional;

/**
 * The models a Java service can be answered by, configured as a Scala service's are: two wire
 * protocols, and presets for the servers that speak them. Each throws {@code
 * IllegalArgumentException} naming what is wrong with what it was given.
 */
public final class ModelProviders {

  private ModelProviders() {}

  /**
   * Anthropic's API, named {@code anthropic}, with the key in {@code ANTHROPIC_API_KEY}. Without
   * the key the provider still exists, and every call to it fails naming the variable.
   */
  public static ModelProvider anthropic(String model) {
    return AgentBridge.anthropic(model);
  }

  /**
   * OpenAI's API, named {@code openai}, with the key in {@code OPENAI_API_KEY}. Without the key the
   * provider still exists, and every call to it fails naming the variable.
   */
  public static ModelProvider openAi(String model) {
    return AgentBridge.openAi(model);
  }

  /** A model the installation's model server serves, reached with the service's certificate. */
  public static ModelProvider installation(String model) {
    return AgentBridge.installation(model);
  }

  /**
   * A provider from a preset that needs no key ({@code "ollama"}, {@code "lmstudio"}, ...), named
   * {@code name}, answering with {@code model}. A preset that needs a key is refused, naming it.
   */
  public static ModelProvider preset(String name, String preset, String model) {
    return AgentBridge.named(
        name, Optional.empty(), Optional.empty(), Optional.of(model), Optional.empty(),
        Optional.of(preset));
  }

  /** A provider from a preset ({@code "groq"}, {@code "mistral"}, ...), with its key given. */
  public static ModelProvider preset(String name, String preset, String model, String key) {
    return AgentBridge.named(
        name, Optional.empty(), Optional.empty(), Optional.of(model), Optional.of(key),
        Optional.of(preset));
  }

  /**
   * A provider at an address: {@code protocol} is {@code "anthropic"} or {@code "openai"}, and an
   * address that needs no key is given none.
   */
  public static ModelProvider at(
      String name, String protocol, String url, String model, Optional<String> key) {
    return AgentBridge.named(
        name, Optional.of(protocol), Optional.of(url), Optional.of(model), key, Optional.empty());
  }
}
