package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.time.Duration;
import java.util.Set;

/**
 * A checkout with a failing step and a pause. {@code mode}: {@code ok}, {@code fail} (charge is
 * declined and compensated), {@code pause} (a pause before charging), {@code abort} (declined; the
 * compensation records it and fails the workflow), {@code drop} (declined; the compensation fails
 * the workflow and records nothing) or {@code doomed} (declined, and the compensation fails too).
 */
// docs:start workflow
@ComponentId("checkout")
public class Checkout extends Workflow<Checkout.CheckoutState> {

  @Manifest("checkout")
  public record CheckoutState(String id, String mode, String status) {
    CheckoutState withStatus(String next) {
      return new CheckoutState(id, mode, next);
    }
  }

  private static final Set<String> DECLINED = Set.of("fail", "abort", "drop", "doomed");

  @Override
  protected CheckoutState emptyState() {
    return new CheckoutState(context().entityId(), "", "new");
  }

  @Override
  protected WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(Duration.ofSeconds(10))
        .stepRecovery(Checkout::charge, RecoverStrategy.maxRetries(1).failoverTo(Checkout::compensate))
        .build();
  }

  @Command("start")
  public Effect<String> start(String mode) {
    if (!currentState().status().equals("new"))
      return effects().error(Problem.conflict("already started"));
    return effects()
        .updateState(new CheckoutState(currentState().id(), mode, "reserving"))
        .transitionTo(Checkout::reserve)
        .thenReply("started");
  }

  @Step("reserve")
  public StepEffect reserve() {
    // A client call from a step: what the cart holds.
    context()
        .componentClient()
        .forEventSourcedEntity(currentState().id())
        .call(ShoppingCartEntity::totalQuantity)
        .invoke();
    var reserved = stepEffects().updateState(currentState().withStatus("reserved"));
    return currentState().mode().equals("pause")
        ? reserved.thenTransitionTo(Checkout::waitStep)
        : reserved.thenTransitionTo(Checkout::charge);
  }

  @Step("wait")
  public StepEffect waitStep() {
    return stepEffects()
        .updateState(currentState().withStatus("waiting"))
        .thenPause(Duration.ofMillis(1500), Checkout::charge);
  }

  @Step("charge")
  public StepEffect charge() {
    if (DECLINED.contains(currentState().mode())) throw new RuntimeException("payment declined");
    return stepEffects().updateState(currentState().withStatus("charged")).thenEnd();
  }

  @Step("compensate")
  public StepEffect compensate() {
    return switch (currentState().mode()) {
      case "abort" ->
          stepEffects()
              .updateState(currentState().withStatus("aborted"))
              .thenFail("payment declined");
      case "drop" -> stepEffects().thenFail("payment declined");
      case "doomed" -> throw new RuntimeException("compensation failed too");
      default -> stepEffects().updateState(currentState().withStatus("compensated")).thenEnd();
    };
  }

  @Query("status")
  public ReadOnlyEffect<String> status() {
    return effects().reply(currentState().status());
  }
}
// docs:end workflow
