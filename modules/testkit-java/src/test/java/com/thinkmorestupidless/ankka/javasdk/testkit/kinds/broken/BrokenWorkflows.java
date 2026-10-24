package com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Checkout;

/** Workflows declared wrongly, each refused at start. */
public final class BrokenWorkflows {
  private BrokenWorkflows() {}

  /** Two steps with one name, a step of two parameters, and a step not marked. */
  @ComponentId("misshapen-steps")
  public static class MisshapenSteps extends Workflow<String> {
    @Step("same")
    public StepEffect one() {
      return stepEffects().thenEnd();
    }

    @Step("same")
    public StepEffect two() {
      return stepEffects().thenEnd();
    }

    @Step("pair")
    public StepEffect pair(String a, String b) {
      return stepEffects().thenEnd();
    }

    public StepEffect unmarked() {
      return stepEffects().thenEnd();
    }
  }

  /** Settings naming another workflow's step, and a failover to a step that takes an input. */
  @ComponentId("wrong-settings")
  public static class WrongSettings extends Workflow<String> {
    @Override
    protected WorkflowSettings settings() {
      return WorkflowSettings.builder()
          .stepTimeout(Checkout::finish, java.time.Duration.ofSeconds(1))
          .defaultRecovery(RecoverStrategy.maxRetries(1).failoverTo(WrongSettings::own))
          .build();
    }

    @Step("own")
    public StepEffect own() {
      return stepEffects().thenEnd();
    }
  }
}
