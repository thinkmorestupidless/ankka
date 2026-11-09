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
import java.time.Duration;

/** Onboards an applicant: a step starts the KYC workflow and waits for its decision. */
@ComponentId("onboarding")
public class OnboardingWorkflow extends Workflow<OnboardingWorkflow.Onboarding> {

  public record Onboarding(String applicant, String status) {}

  static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(20);

  @Override
  protected Onboarding emptyState() {
    return new Onboarding("", "new");
  }

  @Command("start")
  public Effect<Done> start(String applicant) {
    return effects()
        .updateState(new Onboarding(applicant, "verifying"))
        .transitionTo(OnboardingWorkflow::verify)
        .thenDone();
  }

  // docs:start step-awaits
  @Step("verify")
  public StepEffect verify() {
    var kyc =
        context()
            .componentClient()
            .forWorkflow("kyc-" + context().entityId())
            .callThenAwaitEnd(KycWorkflow::start, VERIFY_TIMEOUT)
            .invoke(currentState().applicant());
    return kyc.decision().equals("approved")
        ? stepEffects().thenTransitionTo(OnboardingWorkflow::welcome)
        : stepEffects().thenTransitionTo(OnboardingWorkflow::decline);
  }
  // docs:end step-awaits

  @Step("welcome")
  public StepEffect welcome() {
    return stepEffects()
        .updateState(new Onboarding(currentState().applicant(), "welcomed"))
        .thenEnd();
  }

  @Step("decline")
  public StepEffect decline() {
    return stepEffects()
        .updateState(new Onboarding(currentState().applicant(), "declined"))
        .thenEnd();
  }

  @Query("get")
  public ReadOnlyEffect<Onboarding> get() {
    return effects().reply(currentState());
  }
}
