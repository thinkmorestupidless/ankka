package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Memory;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.util.List;

// docs:start selector
/** Routes a planning request to the specialists it needs. */
@ComponentId("selector-agent")
public class SelectorAgent extends Agent {

  public record Selection(List<String> specialists, String reason) {}

  @Command("select")
  public AgentEffect<Selection> select(String request) {
    if (request.isBlank()) return effects().error(Problem.badRequest("nothing to plan"));
    return effects()
        .systemMessage(
            "You route planning requests to specialists. Available specialists: "
                + String.join(", ", PlannerWorkflow.SPECIALISTS)
                + ". Reply with JSON: {\"specialists\":[\"...\"],\"reason\":\"...\"}."
                + " Choose only the specialists the request genuinely needs.")
        .userMessage(request)
        // The selector's own deliberation is not part of the conversation the specialists and
        // the summariser share.
        .memory(Memory.none())
        .thenReply(Selection.class);
  }
}
// docs:end selector
