package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent;
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition;
import com.thinkmorestupidless.ankka.agent.javasdk.Task;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An operator that restarts services and drains nodes, each only once a person approves. */
@ComponentId("operator")
public class Operator extends AutonomousAgent {

  /** What the tools did once approved, read by the suite. */
  public static final List<String> RUNS = new CopyOnWriteArrayList<>();

  public static final TaskType<String> OPERATE =
      Task.named("operate").describedAs("Keep a service healthy with the tools given");

  // docs:start autonomous-requires-approval
  public static final class Tools {

    @Tool(
        value = "restart_service",
        description = "Restarts a service. An operator approves every restart.",
        requiresApproval = true)
    public String restartService(@Describe("the service to restart") String service) {
      RUNS.add("restart_service(" + service + ")");
      return "restarted " + service;
    }

    @Tool(
        value = "drain_node",
        description = "Drains a node of its work.",
        requiresApproval = true,
        approvalWithin = "PT2S")
    public String drainNode(@Describe("the node to drain") String node) {
      RUNS.add("drain_node(" + node + ")");
      return "drained " + node;
    }

    @Tool(value = "read_metrics", description = "Reads a service's metrics.")
    public String readMetrics(@Describe("the service to read") String service) {
      RUNS.add("read_metrics(" + service + ")");
      return service + " is healthy";
    }
  }

  @Override
  protected List<Object> tools() {
    return List.of(new Tools());
  }
  // docs:end autonomous-requires-approval

  @Override
  protected AutonomousDefinition definition() {
    return AutonomousDefinition.describedAs("Keeps services healthy")
        .instructions("Read a service's metrics before acting on it.")
        .accepts(OPERATE, 5);
  }
}
