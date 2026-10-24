package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Stream;

/** An agent declared wrongly in three ways, each of which a start reports. */
@ComponentId("broken")
public class Broken extends Agent {

  @Command("answer")
  public String answer(String question) {
    return question;
  }

  @Command("pair")
  public AgentEffect<String> pair(String first, String second) {
    return effects().userMessage(first + second).thenReply();
  }

  @Stream("talk")
  public StreamEffect talk() {
    return effects().userMessage("hello").thenStream();
  }
}
