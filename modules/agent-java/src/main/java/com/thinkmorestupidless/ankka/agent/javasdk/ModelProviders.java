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

  /** A model the installation's model server serves, reached with the service's certificate. */
  public static ModelProvider installation(String model) {
    return AgentBridge.installation(model);
  }

  /**
   * A provider from a preset ({@code "anthropic"}, {@code "openai"}, {@code "ollama"}, ...), named
   * {@code name}, answering with {@code model}; its key is read as a Scala service's is.
   */
  public static ModelProvider preset(String name, String preset, String model) {
    return AgentBridge.named(
        name, Optional.empty(), Optional.empty(), Optional.of(model), Optional.empty(),
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
