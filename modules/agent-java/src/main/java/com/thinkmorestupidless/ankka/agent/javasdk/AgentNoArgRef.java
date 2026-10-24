package com.thinkmorestupidless.ankka.agent.javasdk;

import java.io.Serializable;

/** An agent's {@code @Command} handler that takes nothing, named by method reference. */
@FunctionalInterface
public interface AgentNoArgRef<A extends Agent, O> extends Serializable {
  AgentEffect<O> apply(A agent) throws Exception;
}
