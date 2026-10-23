package com.thinkmorestupidless.ankka.agent.javasdk;

import java.io.Serializable;

/** An agent's {@code @Stream} handler, named by method reference. */
@FunctionalInterface
public interface AgentStreamRef<A extends Agent, I> extends Serializable {
  StreamEffect apply(A agent, I input) throws Exception;
}
