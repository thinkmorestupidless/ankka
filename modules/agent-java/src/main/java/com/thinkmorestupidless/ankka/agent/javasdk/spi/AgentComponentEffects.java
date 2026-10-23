package com.thinkmorestupidless.ankka.agent.javasdk.spi;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffects;
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentCell;
import java.lang.reflect.Field;

/**
 * The effects an agent's own {@code effects()} answers, for a builder written outside the class:
 * the Kotlin SDK's {@code effects { ... }}, an extension, cannot call a protected method. It answers
 * exactly what the agent's {@code effects()} would.
 */
public final class AgentComponentEffects {

  // An agent's effects are its cell's, which only the class holds.
  private static final Field CELL;

  static {
    try {
      CELL = Agent.class.getDeclaredField("cell");
      CELL.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private AgentComponentEffects() {}

  public static AgentEffects of(Agent agent) {
    try {
      return ((AgentCell) CELL.get(agent)).effects();
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
