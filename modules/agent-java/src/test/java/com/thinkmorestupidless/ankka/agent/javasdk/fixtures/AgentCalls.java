package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome;
import com.thinkmorestupidless.ankka.agent.javasdk.Decision;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.concurrent.Flow;

/** The calls the suite makes, written in Java so each names its handler by method reference. */
public final class AgentCalls {

  private AgentCalls() {}

  public static String ask(ComponentClient client, String session, String question) {
    return AgentClients.forAgent(client, session).call(Assistant::ask).invoke(question);
  }

  public static AgentOutcome<String> book(ComponentClient client, String session, String request) {
    return AgentClients.forAgent(client, session).ask(Assistant::book).invoke(request);
  }

  public static AgentOutcome<String> approveBooking(
      ComponentClient client, String session, String approvalId) {
    return AgentClients.forAgent(client, session)
        .decide(Assistant::book, Decision.approved(approvalId, "a manager"));
  }

  public static Assistant.Plan plan(ComponentClient client, String session, String city) {
    return AgentClients.forAgent(client, session).call(Assistant::plan).invoke(city);
  }

  public static String refuse(ComponentClient client, String session, String why) {
    return AgentClients.forAgent(client, session).call(Assistant::refuse).invoke(why);
  }

  public static Flow.Publisher<String> chat(ComponentClient client, String session, String text) {
    return AgentClients.forAgent(client, session).stream(Assistant::chat, text);
  }

  public static String research(ComponentClient client, String session, String question) {
    return AgentClients.forAgent(client, session).call(Researcher::research).invoke(question);
  }
}
