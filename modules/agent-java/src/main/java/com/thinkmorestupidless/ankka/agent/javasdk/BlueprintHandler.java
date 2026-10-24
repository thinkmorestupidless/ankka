package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.util.function.BiFunction;

/**
 * Code a call step runs: given the run it serves and what the step reads as JSON, it answers the
 * step's result as JSON, which is checked against the step's shape. It may run again after a crash.
 */
public interface BlueprintHandler {

  static BlueprintHandler of(String name, BiFunction<RunRef, String, String> run) {
    return AgentBridge.handler(name, run);
  }

  String name();
}
