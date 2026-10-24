package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;

/**
 * Calls to agents, autonomous agents, tasks and blueprints. An agent is addressed by session, so
 * two calls with one session id are one conversation, handled one at a time: {@code
 * AgentClients.forAgent(componentClient(), sessionId).call(Assistant::ask).invoke(question)}.
 */
public final class AgentClients {

  private AgentClients() {}

  /** The agents of {@code client}'s service, in the session {@code sessionId}. */
  public static AgentRef forAgent(ComponentClient client, String sessionId) {
    return AgentBridge.forAgent(client, sessionId);
  }

  /** Where tasks are created. */
  public static TasksRef tasks(ComponentClient client) {
    return AgentBridge.tasks(client);
  }

  public static TaskRef forTask(ComponentClient client, String taskId) {
    return AgentBridge.forTask(client, taskId);
  }

  /** The instance {@code instanceId} of the autonomous agent {@code agent}. */
  public static AutonomousRef forAutonomousAgent(
      ComponentClient client, Class<? extends AutonomousAgent> agent, String instanceId) {
    return AgentBridge.forAutonomousAgent(client, agent, instanceId);
  }

  /**
   * Creates a task of {@code type} and hands it to a new instance of {@code agent}, which ends once
   * the task has; answers the task's id.
   */
  public static String runSingleTask(
      ComponentClient client,
      Class<? extends AutonomousAgent> agent,
      TaskType<?> type,
      String instructions) {
    return AgentBridge.runSingleTask(client, agent, type, instructions);
  }

  /** The service's blueprints; needs {@code withBlueprints} on the module. */
  public static BlueprintsRef blueprints(ComponentClient client) {
    return AgentBridge.blueprints(client);
  }

  /** Runs of the service's blueprints; needs {@code withBlueprints} on the module. */
  public static RunsRef runs(ComponentClient client) {
    return AgentBridge.runs(client);
  }
}
