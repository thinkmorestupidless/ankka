package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentRef;
import com.thinkmorestupidless.ankka.agent.javasdk.Decision;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/** The Scala reference's agent routes under {@code /conformance}, route for route. */
public class AgentsEndpoint extends HttpEndpoint {

  /** A decision as every reference reads it: {@code {"approved": bool, "by": name, "note": text}}. */
  public record DecisionBody(boolean approved, String by, Optional<String> note) {
    Decision on(String id) {
      return approved ? Decision.approved(id, by) : Decision.refused(id, by, note.orElse(""));
    }
  }

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private AgentRef agent(String session) {
    return AgentClients.forAgent(componentClient(), session);
  }

  /**
   * An outcome as every reference renders it: {@code {"answered": text}}, or {@code {"awaiting":
   * [{"id", "tool", "arguments"}]}}.
   */
  static String render(AgentOutcome<String> outcome) {
    return switch (outcome) {
      case AgentOutcome.Answered<String> a -> "{\"answered\":" + Wire.quoted(a.value()) + "}";
      case AgentOutcome.AwaitingApproval<String> w ->
          w.requests().stream()
              .map(
                  r ->
                      "{\"id\":"
                          + Wire.quoted(r.id())
                          + ",\"tool\":"
                          + Wire.quoted(r.tool())
                          + ",\"arguments\":"
                          + r.arguments()
                          + "}")
              .collect(Collectors.joining(",", "{\"awaiting\":[", "]}"));
    };
  }

  @Post("/conformance/ask/{session}")
  public String ask(String session, @Body String question) {
    return agent(session).call(Assistant::ask).invoke(question);
  }

  @Post("/conformance/ask-second/{session}")
  public String askSecond(String session, @Body String question) {
    return agent(session).call(Assistant::askSecond).invoke(question);
  }

  // A turn that may wait: the model's answer, or the approval requests it waits on.
  @Post("/conformance/approver/{session}")
  public String approver(String session, @Body String question) {
    return render(agent(session).ask(Approver::ask).invoke(question));
  }

  // A person's decision, answered as the turn's caller would have been once the turn goes on.
  @Post("/conformance/approver/{session}/decide/{id}")
  public String decide(String session, String id, @Body DecisionBody body) {
    return render(agent(session).decide(Approver::ask, body.on(id)));
  }

  @Sse("/conformance/stream-ask/{session}")
  public Flow.Publisher<SseEvent> streamAsk(String session) {
    var question = request().query("q").orElse("");
    return Wire.map(agent(session).stream(Assistant::stream, question), SseEvent::text);
  }
}
