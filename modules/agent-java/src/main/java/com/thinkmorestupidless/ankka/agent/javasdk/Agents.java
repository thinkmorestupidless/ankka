package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/**
 * Where a service's agents begin: {@code Ankka.service("shop").withModule(Agents.module()
 * .withModelProvider(model)).register(Assistant.class)}. A service that registers an agent without
 * the module refuses to start, naming it.
 */
public final class Agents {

  private Agents() {}

  /** The module that reads a service's agents, with no default model. */
  public static AgentModule module() {
    return AgentBridge.module();
  }
}
