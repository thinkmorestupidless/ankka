package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

// docs:start activity
/** Suggests activities for a destination, reading the traveller's preferences itself. */
@ComponentId("activity-agent")
public class ActivityAgent extends Agent {

  public record Request(String userId, String destination) {}

  @Command("consult")
  public AgentEffect<String> consult(Request request) {
    // componentClient() is inherited from Agent; no constructor plumbing needed.
    var preferences =
        componentClient()
            .forKeyValueEntity(request.userId())
            .call(Preferences::get)
            .invoke();

    return effects()
        .systemMessage(
            "You are a concise activity specialist. Suggest two activities, in one sentence.")
        .userMessage("What should I do in " + request.destination() + "?")
        // Preferences are context, not something the user said, so memory records the
        // question, not the whole assembled prompt.
        .withContext("Traveller preferences: " + preferences.summary())
        .thenReply();
  }
}
// docs:end activity
