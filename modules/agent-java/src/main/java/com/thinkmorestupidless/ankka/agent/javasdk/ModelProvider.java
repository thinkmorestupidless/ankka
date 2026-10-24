package com.thinkmorestupidless.ankka.agent.javasdk;

/**
 * A model an agent is answered by, built by {@link ModelProviders}, or scripted by the test kit's
 * {@code TestModelProvider}.
 */
public interface ModelProvider {

  /** The name the service knows the provider by, in its variables and its traces. */
  String name();
}
