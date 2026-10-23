package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AssignResult;
import com.thinkmorestupidless.ankka.agent.javasdk.InstanceState;
import com.thinkmorestupidless.ankka.agent.javasdk.Judgment;
import com.thinkmorestupidless.ankka.agent.javasdk.Notification;
import com.thinkmorestupidless.ankka.agent.javasdk.RunsRef;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskSnapshot;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.time.Duration;
import java.util.concurrent.Flow;

/** The calls the autonomous suite makes, written in Java as a service would write them. */
public final class AutonomousCalls {

  private AutonomousCalls() {}

  public static String create(ComponentClient client, TaskType<?> type, String id, String text) {
    return AgentClients.tasks(client).create(type, text).withId(id).create();
  }

  public static AssignResult assign(ComponentClient client, String instance, String... taskIds) {
    return AgentClients.forAutonomousAgent(client, Answerer.class, instance).assign(taskIds);
  }

  public static <R> TaskSnapshot<R> await(ComponentClient client, TaskType<R> type, String id) {
    return AgentClients.forTask(client, id).await(type, Duration.ofMinutes(1));
  }

  public static TaskSnapshot<String> get(ComponentClient client, String id) {
    return AgentClients.forTask(client, id).get();
  }

  public static InstanceState state(ComponentClient client, String instance) {
    return AgentClients.forAutonomousAgent(client, Answerer.class, instance).state();
  }

  public static Flow.Publisher<Notification> notifications(
      ComponentClient client, String instance) {
    return AgentClients.forAutonomousAgent(client, Answerer.class, instance).notifications();
  }

  public static String summarise(ComponentClient client, String text) {
    return AgentClients.runSingleTask(client, Answerer.class, Answerer.SUMMARY, text);
  }

  public static Judgment judge(ComponentClient client, String session, String text) {
    return AgentClients.forAgent(client, session).call(Judge::judge).invoke(text);
  }

  public static Judge.Verdict verdict(ComponentClient client, String session, String text) {
    return AgentClients.forAgent(client, session).call(Judge::verdict).invoke(text);
  }

  public static String guarded(ComponentClient client, String session, String text) {
    return AgentClients.forAgent(client, session).call(Judge::guarded).invoke(text);
  }

  // docs:start run
  public static RunsRef.RunSnapshot keep(ComponentClient client, String runId, String topic) {
    var runs = AgentClients.runs(client);
    runs.start("keeper", "{\"topic\":\"" + topic + "\"}", runId);
    return runs.await(runId, Duration.ofMinutes(1));
  }
  // docs:end run

  public static int keeperVersions(ComponentClient client) {
    return AgentClients.blueprints(client).versions("keeper").size();
  }
}
