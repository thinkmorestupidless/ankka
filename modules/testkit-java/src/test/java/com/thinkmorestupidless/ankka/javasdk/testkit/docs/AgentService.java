package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agents;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;

/** The agents page's service, registered as the page shows; the suite starts it. */
public final class AgentService {

  private AgentService() {}

  // docs:start registration
  /** The agents and the entity they read, hosted by the agents module with its default model. */
  public static ServiceBuilder service(ModelProvider model) {
    return Ankka.service("planner")
        .withModule(Agents.module().withModelProvider(model))
        .register(Preferences.class)
        .register(ActivityAgent.class)
        .register(WeatherAgent.class)
        .register(ApprovalAgent.class)
        .register(WalletAgent.class)
        .register(Triage.class)
        .withEndpoint(ApprovalRoutes.class);
  }
  // docs:end registration

  // docs:start compaction
  /** The same module with compaction on: the oldest messages become a summary past 100k chars. */
  public static ServiceBuilder compacting(ModelProvider model) {
    var agents = Agents.module().withModelProvider(model).withCompaction(100_000, 10);
    return Ankka.service("planner").withModule(agents).register(WeatherAgent.class);
  }
  // docs:end compaction
}
