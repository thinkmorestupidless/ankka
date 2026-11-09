package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;

// docs:start step-calls-service
/**
 * Asks the PSP gateway service to start a payout, from a step: a step may call another service, as
 * this service, and goes on with the answer.
 */
@ComponentId("payout")
public class PayoutWorkflow extends Workflow<PayoutWorkflow.State> {

  public record Payout(int amount) {}

  public record State(String status, String answer) {}

  @Override
  protected State emptyState() {
    return new State("", "");
  }

  @Command("start")
  public Effect<Done> start(Payout payout) {
    return effects()
        .updateState(emptyState())
        .transitionTo(PayoutWorkflow::initiatePayout, payout)
        .thenDone();
  }

  @Step("initiate-payout")
  public StepEffect initiatePayout(Payout payout) {
    var answer = services().of("psp-gateway").getText("/payouts?amount=" + payout.amount());
    return stepEffects().updateState(new State("initiated", answer)).thenEnd();
  }

  @Query("get")
  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}
// docs:end step-calls-service
