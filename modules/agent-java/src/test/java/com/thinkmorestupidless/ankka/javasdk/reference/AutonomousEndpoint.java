package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousRef;
import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.List;
import java.util.concurrent.Flow;

/** Autonomous agents: tasks run, read and cancelled; instances driven and watched. */
public class AutonomousEndpoint extends HttpEndpoint {

  /** What {@code create} is given: the task's instructions, and the tasks it waits on. */
  public record CreateBody(String instructions, List<String> dependsOn) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private AutonomousRef instance(String instance) {
    return AgentClients.forAutonomousAgent(componentClient(), Answerer.class, instance);
  }

  private static void answerType(String taskType) {
    if (!taskType.equals(Answerer.ANSWER.name()))
      throw new ProblemException(Problem.badRequest("no task type '" + taskType + "'"));
  }

  @Post("/autonomous/tasks/{type}")
  public String run(String type, @Body String instructions) {
    answerType(type);
    var id =
        AgentClients.runSingleTask(componentClient(), Answerer.class, Answerer.ANSWER, instructions);
    var assignee = AgentClients.forTask(componentClient(), id).get().instanceId().orElse("");
    return "{\"taskId\":" + Wire.quoted(id) + ",\"instanceId\":" + Wire.quoted(assignee) + "}";
  }

  @Get("/autonomous/tasks/{id}")
  public String task(String id) {
    return AgentClients.forTask(componentClient(), id).get().json();
  }

  @Post("/autonomous/tasks/{id}/cancel")
  public Done cancel(String id) {
    AgentClients.forTask(componentClient(), id).cancel("cancelled by caller");
    return Done.done();
  }

  // Creates without running.
  @Post("/autonomous/tasks/{type}/create")
  public String create(String type, @Body CreateBody body) {
    answerType(type);
    var id =
        AgentClients.tasks(componentClient())
            .create(Answerer.ANSWER, body.instructions())
            .dependsOn(body.dependsOn().toArray(String[]::new))
            .create();
    return "{\"taskId\":" + Wire.quoted(id) + "}";
  }

  @Post("/autonomous/instances/{instance}/assign")
  public String assign(String instance, @Body List<String> ids) {
    var result = instance(instance).assign(ids.toArray(String[]::new));
    return "{\"accepted\":"
        + result.accepted().stream()
            .map(Wire::quoted)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"))
        + "}";
  }

  @Post("/autonomous/instances/{instance}/{op}")
  public Done operate(String instance, String op) {
    var calls = instance(instance);
    switch (op) {
      case "suspend" -> calls.suspend();
      case "resume" -> calls.resume();
      case "terminate" -> calls.terminate();
      default -> throw new ProblemException(Problem.notFound("no operation '" + op + "'"));
    }
    return Done.done();
  }

  @Sse("/autonomous/instances/{instance}/notifications")
  public Flow.Publisher<SseEvent> notifications(String instance) {
    return Wire.map(instance(instance).notifications(), n -> SseEvent.text(n.json()));
  }

  @Post("/autonomous/instances/{instance}/decide/{id}")
  public Done decide(String instance, String id, @Body AgentsEndpoint.DecisionBody body) {
    instance(instance).decide(body.on(id));
    return Done.done();
  }

  @Get("/autonomous/instances/{instance}/state")
  public String state(String instance) {
    return instance(instance).state().json();
  }
}
