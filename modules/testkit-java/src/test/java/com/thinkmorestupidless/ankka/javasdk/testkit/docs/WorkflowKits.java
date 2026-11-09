package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.testkit.StepResult;
import com.thinkmorestupidless.ankka.javasdk.testkit.WorkflowTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Checkout;

/** The workflow kit as the testing page shows it. */
public final class WorkflowKits {

  private WorkflowKits() {}

  // docs:start workflow-test
  /** A command and the steps it runs, by hand, until the workflow pauses. */
  public static StepResult<Checkout.State, Done> startCheckout() {
    var kit = WorkflowTestKit.of(Checkout.class, "c1");
    var started = kit.call(Checkout::start, new Checkout.Start("cart-1", 60_000));
    // started.steps() is [reserve, charge]: the steps that ran before the pause;
    // started.lifecycle().isPaused(), for a minute (pausedFor()), then the step "expire"
    // (onTimeout()); kit.state().status() is "awaiting", and kit.timeOut() would run "expire".
    return started;
  }
  // docs:end workflow-test
}
