package com.thinkmorestupidless.ankka.agent.javasdk;

import java.io.Serializable;

/** An agent's {@code @Command} handler that takes an input, named by method reference. */
@FunctionalInterface
public interface AgentCommandRef<A extends Agent, I, O> extends Serializable {
  AgentEffect<O> apply(A agent, I input) throws Exception;
}
