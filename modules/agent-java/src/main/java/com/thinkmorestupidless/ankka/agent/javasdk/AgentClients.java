package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;

/**
 * Calls to agents. An agent is addressed by session, so two calls with one session id are one
 * conversation, handled one at a time: {@code
 * AgentClients.forAgent(componentClient(), sessionId).call(Assistant::ask).invoke(question)}.
 */
public final class AgentClients {

  private AgentClients() {}

  /** The agents of {@code client}'s service, in the session {@code sessionId}. */
  public static AgentRef forAgent(ComponentClient client, String sessionId) {
    return AgentBridge.forAgent(client, sessionId);
  }
}
