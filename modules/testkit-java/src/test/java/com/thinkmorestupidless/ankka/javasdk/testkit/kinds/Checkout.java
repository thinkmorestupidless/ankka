package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A checkout: reserve, then charge and wait for a confirmation that times out, or, for a cart whose
 * id starts "big", a charge that always fails and is compensated by a refund.
 */
@ComponentId("checkout")
public class Checkout extends Workflow<Checkout.State> {

  public record Start(String cartId, long confirmWithinMillis) {}

  public record Charge(int amount) {}

  public record State(String cartId, String status, long confirmWithinMillis, List<String> log) {
    State then(String status) {
      var more = new ArrayList<>(log);
      more.add(status);
      return new State(cartId, status, confirmWithinMillis, more);
    }
  }

  static volatile int flakyRuns = 0;

  @Override
  protected WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .stepTimeout(Checkout::reserve, Duration.ofSeconds(5))
        .stepRecovery(Checkout::flaky, RecoverStrategy.maxRetries(1).failoverTo(Checkout::refund))
        .build();
  }

  @Command("start")
  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().error("already started");
    return effects()
        .updateState(
            new State(start.cartId(), "started", start.confirmWithinMillis(), List.of("started")))
        .transitionTo(Checkout::reserve)
        .thenDone();
  }

  @Command("confirm")
  public Effect<Done> confirm() {
    if (currentState() == null || !currentState().status().equals("awaiting"))
      return effects().error("nothing to confirm");
    return effects()
        .updateState(currentState().then("confirmed"))
        .transitionTo(Checkout::finish)
        .thenDone();
  }

  @Command("cancel")
  public Effect<Done> cancel() {
    return effects().delete().thenDone();
  }

  @Query("get")
  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  @Step("reserve")
  public StepEffect reserve() {
    var state = currentState().then("reserved");
    if (state.cartId().startsWith("big"))
      return stepEffects().updateState(state).thenTransitionTo(Checkout::flaky);
    return stepEffects()
        .updateState(state)
        .thenTransitionTo(Checkout::charge, new Charge(state.cartId().length()));
  }

  @Step("charge")
  public StepEffect charge(Charge charge) {
    long within = currentState().confirmWithinMillis();
    return stepEffects()
        .updateState(currentState().then("awaiting"))
        .thenPause(Duration.ofMillis(within), Checkout::expire);
  }

  @Step("flaky")
  public StepEffect flaky() {
    flakyRuns++;
    throw new IllegalStateException("the card was declined");
  }

  @Step("refund")
  public StepEffect refund() {
    return stepEffects().updateState(currentState().then("refunded")).thenEnd();
  }

  @Step("expire")
  public StepEffect expire() {
    return stepEffects().updateState(currentState().then("expired")).thenEnd();
  }

  @Step("finish")
  public StepEffect finish() {
    return stepEffects().updateState(currentState().then("done")).thenEnd();
  }
}
