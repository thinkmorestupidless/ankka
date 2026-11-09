package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Memory;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

// docs:start summary
/** Combines what the specialists said, reading it back from the session they all wrote to. */
@ComponentId("summary-agent")
public class SummaryAgent extends Agent {

  @Command("summarise")
  public AgentEffect<String> summarise(String destination) {
    return effects()
        .systemMessage(
            "You write short trip briefs. Combine what the specialists said into two sentences.")
        .userMessage("Write a brief for a trip to " + destination + ".")
        // The whole shared session: the specialists' contributions are in it, and the selector's
        // routing is not, because the selector kept itself out with Memory.none(). The Java
        // Memory has no filter by the agent that wrote a message.
        .memory(Memory.session())
        .thenReply();
  }
}
// docs:end summary
