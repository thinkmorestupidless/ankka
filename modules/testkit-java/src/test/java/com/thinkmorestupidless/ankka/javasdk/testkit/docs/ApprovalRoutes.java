package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentRef;
import com.thinkmorestupidless.ankka.agent.javasdk.Decision;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import java.util.List;
import java.util.Optional;

/** Routes over a session whose tools may wait for a person. */
public class ApprovalRoutes extends HttpEndpoint {

  /** A turn's outcome as a browser reads it: the answer, or the requests a person must decide. */
  public record Outcome(Optional<String> answered, List<Pending> awaiting) {}

  public record Pending(String id, String tool, String arguments) {}

  /** A decision as a person sends it. */
  public record DecisionBody(boolean approved, String by, Optional<String> note) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private AgentRef session(String session) {
    return AgentClients.forAgent(componentClient(), session);
  }

  static Outcome render(AgentOutcome<String> outcome) {
    return switch (outcome) {
      case AgentOutcome.Answered<String> a -> new Outcome(Optional.of(a.value()), List.of());
      case AgentOutcome.AwaitingApproval<String> w ->
          new Outcome(
              Optional.empty(),
              w.requests().stream()
                  .map(r -> new Pending(r.id(), r.tool(), r.arguments()))
                  .toList());
    };
  }

  // docs:start approver-routes
  /** A turn that may wait: the model's answer, or the approval requests it waits on. */
  @Post("/approver/{session}")
  public Outcome ask(String session, @Body String question) {
    return render(session(session).ask(ApprovalAgent::ask).invoke(question));
  }

  /** A person's decision, answered as the turn's caller would have been once it goes on. */
  @Post("/approver/{session}/decide/{id}")
  public Outcome decide(String session, String id, @Body DecisionBody body) {
    var decision =
        body.approved()
            ? Decision.approved(id, body.by())
            : Decision.refused(id, body.by(), body.note().orElse(""));
    return render(session(session).decide(ApprovalAgent::ask, decision));
  }
  // docs:end approver-routes
}
