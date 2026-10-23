package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/**
 * One of a blueprint's workers: a model with instructions, tools and guardrails, each named as the
 * registry names it. Its model is the module's unless it names another. Each method returns a new
 * worker.
 */
public interface Worker {

  static Worker named(String name) {
    return AgentBridge.worker(name);
  }

  String name();

  Worker instructions(String text);

  /** The registered model this worker asks. */
  Worker model(String name);

  Worker tools(String... names);

  Worker guardrails(String... names);

  /** The model calls one turn of this worker may make. */
  Worker budget(int modelCalls);
}
