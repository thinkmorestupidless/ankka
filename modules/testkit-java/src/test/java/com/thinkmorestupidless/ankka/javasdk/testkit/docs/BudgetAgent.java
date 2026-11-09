package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

/** Estimates a daily budget for a destination; its role names it in the shared session. */
@ComponentId("budget-agent")
public class BudgetAgent extends Agent {

  @Override
  protected AgentSettings settings() {
    return AgentSettings.defaults().role("budget");
  }

  @Command("consult")
  public AgentEffect<String> consult(String destination) {
    return effects()
        .systemMessage("You are a concise budget specialist. Answer in one sentence.")
        .userMessage("What should I budget per day in " + destination + "?")
        .thenReply();
  }
}
