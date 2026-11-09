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

// docs:start workflow
/**
 * Charges a customer through a payment provider. The step reads the provider's credential from
 * the secret store when it needs it, and records only that it charged.
 */
@ComponentId("charge")
public class ChargeWorkflow extends Workflow<ChargeWorkflow.ChargeState> {

  public record Charge(String provider, int amount) {}

  public record ChargeState(String provider, int credentialLength, String status) {}

  @Override
  protected ChargeState emptyState() {
    return new ChargeState("", 0, "not-started");
  }

  @Command("start")
  public Effect<Done> start(Charge charge) {
    return effects()
        .updateState(new ChargeState(charge.provider(), 0, "started"))
        .transitionTo(ChargeWorkflow::charge, charge)
        .thenDone();
  }

  @Step("charge")
  public StepEffect charge(Charge charge) {
    var credential =
        secrets()
            .get("provider/" + charge.provider())
            .orElseThrow(
                () -> new IllegalStateException("no credential for " + charge.provider()));
    // Calling the provider with the credential goes here. The state records that it charged,
    // and never the credential.
    return stepEffects()
        .updateState(new ChargeState(charge.provider(), credential.length(), "charged"))
        .thenEnd();
  }

  @Query("get")
  public ReadOnlyEffect<ChargeState> get() {
    return effects().reply(currentState());
  }
}
// docs:end workflow
