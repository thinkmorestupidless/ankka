package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.Agents;
import com.thinkmorestupidless.ankka.agent.javasdk.JudgmentProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;

/** The triage agent's service, and a caller reading a judgment's answers. */
public final class TriageCalls {

  private TriageCalls() {}

  // docs:start provider
  /**
   * The judgment provider is configured on the agent module, beside the text model; a deployed
   * service gives {@code JudgmentProviders.jev()}, which reads its key from {@code
   * TYPESAFE_API_KEY}. A service that only judges needs no text model.
   */
  public static ServiceBuilder service(ModelProvider model, JudgmentProvider judge) {
    return Ankka.service("support")
        .register(TriageAgent.class)
        .withModule(Agents.module().withModelProvider(model).withJudgments(judge));
  }
  // docs:end provider

  // docs:start reading
  /** Where a ticket goes: each answer is read through the question that asked it, typed. */
  public static String dispatch(ComponentClient client, TriageAgent.Ticket ticket) {
    var judgment =
        AgentClients.forAgent(client, ticket.id()).call(TriageAgent::triage).invoke(ticket);
    var team = judgment.answer(TriageAgent.ROUTE); // ChoiceAnswer<Team>
    if (team.confidence() < 0.5) return "a person";
    boolean refundAsked = judgment.answer(TriageAgent.REFUND).probability() >= 0.7;
    return team.choice().name().toLowerCase() + (refundAsked ? ", refund asked" : "");
  }
  // docs:end reading
}
