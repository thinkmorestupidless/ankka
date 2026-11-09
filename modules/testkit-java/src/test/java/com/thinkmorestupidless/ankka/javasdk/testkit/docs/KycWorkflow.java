package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;

/** Decides on an applicant; another workflow starts it and waits for its end. */
@ComponentId("kyc")
public class KycWorkflow extends Workflow<KycWorkflow.Kyc> {

  public record Kyc(String applicant, String decision) {}

  @Override
  protected Kyc emptyState() {
    return new Kyc("", "");
  }

  @Command("start")
  public Effect<Done> start(String applicant) {
    return effects()
        .updateState(new Kyc(applicant, ""))
        .transitionTo(KycWorkflow::check, applicant)
        .thenDone();
  }

  @Step("check")
  public StepEffect check(String applicant) {
    var decision = applicant.startsWith("bad") ? "declined" : "approved";
    return stepEffects().updateState(new Kyc(applicant, decision)).thenEnd();
  }
}
