package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import java.time.Duration;

/** Observing a plan from outside: the engine's view of it, and a wait for its end. */
public class PlanRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // docs:start lifecycle
  /** The engine's own view: running, paused, completed or failed, and why. */
  @Get("/plans/{planId}/lifecycle")
  public String lifecycle(String planId) {
    var lifecycle =
        componentClient().forWorkflow(planId).lifecycle(PlannerWorkflow.class).invoke();
    return lifecycle.status() + lifecycle.failure().map(reason -> ": " + reason).orElse("");
  }
  // docs:end lifecycle

  // docs:start await-later
  /** A plan started earlier, by anyone: at once if it has ended, when it ends if not. */
  @Get("/plans/{planId}/wait")
  public PlannerWorkflow.Plan await(String planId) {
    return componentClient()
        .forWorkflow(planId)
        .awaitEnd(PlannerWorkflow.class, Duration.ofSeconds(10));
  }
  // docs:end await-later
}
