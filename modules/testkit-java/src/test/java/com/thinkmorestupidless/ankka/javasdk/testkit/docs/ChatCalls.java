package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.concurrent.Flow;

/** Consuming an agent's stream from Java. */
public final class ChatCalls {

  private ChatCalls() {}

  // docs:start consume
  /** The reply as it is written: a publisher of tokens, read at the caller's pace. */
  static Flow.Publisher<String> tokens(ComponentClient client, String session, String question) {
    return AgentClients.forAgent(client, session).stream(WeatherAgent::chat, question);
  }
  // docs:end consume
}
