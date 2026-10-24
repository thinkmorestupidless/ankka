package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A tool declared in code rather than by {@link Tool}: its name, what it does, the record its
 * arguments are read into, and what it answers. The schema the model is offered and the decoder of
 * what it sends come from the same record, so the two cannot disagree.
 */
public interface FunctionTool {

  String name();

  /** This tool, decided by a person before each call runs, with no limit on the wait. */
  FunctionTool requiresApproval();

  /** As {@link #requiresApproval()}, refused by the platform when {@code within} passes. */
  FunctionTool requiresApproval(Duration within);

  /** A tool that takes no argument. */
  static FunctionTool of(String name, String description, Supplier<String> run) {
    return AgentBridge.tool(name, description, run);
  }

  /** A tool whose arguments are the components of the record {@code arguments}. */
  static <A> FunctionTool of(
      String name, String description, Class<A> arguments, Function<A, String> run) {
    return AgentBridge.tool(name, description, arguments, run);
  }
}
