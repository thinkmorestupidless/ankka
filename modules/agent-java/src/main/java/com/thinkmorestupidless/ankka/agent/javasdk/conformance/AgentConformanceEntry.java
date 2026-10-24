package com.thinkmorestupidless.ankka.agent.javasdk.conformance;

import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.javasdk.conformance.ConformanceEntry;

/**
 * A reference service with agents, as ankka's conformance suite hosts it: a {@link
 * ConformanceEntry} whose agents may name the model the suite configures beside its default. Not
 * for services.
 */
public interface AgentConformanceEntry extends ConformanceEntry {

  /**
   * The model the suite configures under the name {@code second}, handed over before any component
   * is built: the agent that names a model other than the default names this one.
   */
  void secondModel(ModelProvider second);
}
