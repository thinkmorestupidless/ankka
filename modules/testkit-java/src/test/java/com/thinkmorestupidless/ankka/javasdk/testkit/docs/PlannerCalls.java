package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agents;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.WorkflowLifecycle;

/** The planner's service, and how a plan is observed from outside. */
public final class PlannerCalls {

  private PlannerCalls() {}

  // docs:start service
  public static ServiceBuilder service(ModelProvider model) {
    return Ankka.service("planner")
        .withModule(Agents.module().withModelProvider(model))
        .register(Preferences.class)
        .register(SelectorAgent.class)
        .register(WeatherAgent.class)
        .register(ActivityAgent.class)
        .register(BudgetAgent.class)
        .register(SummaryAgent.class)
        .register(PlannerWorkflow.class)
        .withEndpoint(PlanRoutes.class);
  }
  // docs:end service

  // docs:start observe
  /** The domain state: what the workflow itself keeps. */
  public static PlannerWorkflow.Plan plan(ComponentClient client, String planId) {
    return client.forWorkflow(planId).call(PlannerWorkflow::plan).invoke();
  }

  /** The engine's state: running, paused, completed or failed, and at which step. */
  public static WorkflowLifecycle lifecycle(ComponentClient client, String planId) {
    return client.forWorkflow(planId).lifecycle(PlannerWorkflow.class).invoke();
  }
  // docs:end observe
}
