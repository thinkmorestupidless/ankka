package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

// docs:start naming-a-provider
/** Routes a question to a team, on a provider other than the service's default. */
@ComponentId("triage")
public class Triage extends Agent {

  @Command("route")
  public AgentEffect<String> route(String question) {
    return effects()
        .model(Providers.GPT)
        .systemMessage("Say which team should answer.")
        .userMessage(question)
        .thenReply();
  }
}
// docs:end naming-a-provider
