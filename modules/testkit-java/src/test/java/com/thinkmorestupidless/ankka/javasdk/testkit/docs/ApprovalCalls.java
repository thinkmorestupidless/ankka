package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome;
import com.thinkmorestupidless.ankka.agent.javasdk.Decision;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;

/** A caller that may meet an approval, as the agents page shows it. */
public final class ApprovalCalls {

  private ApprovalCalls() {}

  // docs:start ask-and-decide
  public static String refund(ComponentClient client) {
    var session = AgentClients.forAgent(client, "s-42");
    return switch (session.ask(ApprovalAgent::ask).invoke("refund order o-7")) {
      case AgentOutcome.Answered<String> answered -> answered.value();
      case AgentOutcome.AwaitingApproval<String> waiting -> {
        // In a real service the requests are shown to a person, and the decision arrives later,
        // from whatever route they make it through; that route's ACL is who may decide.
        var decision = Decision.approved(waiting.requests().get(0).id(), "dana");
        yield switch (session.decide(ApprovalAgent::ask, decision)) {
          case AgentOutcome.Answered<String> answered -> answered.value();
          case AgentOutcome.AwaitingApproval<String> more ->
              "still waiting on " + more.requests().size();
        };
      }
    };
  }
  // docs:end ask-and-decide
}
