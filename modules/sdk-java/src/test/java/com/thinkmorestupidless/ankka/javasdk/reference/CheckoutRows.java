package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.client.WorkflowLifecycle;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.Map;
import java.util.Optional;

/** A checkout's row: its state's status, and where the workflow stood when it recorded it. */
@ComponentId("checkout-rows")
public class CheckoutRows extends View<Checkout.CheckoutState, CheckoutRows.CheckoutRow> {

  @Manifest("checkout-row")
  public record CheckoutRow(
      String id,
      String status,
      String standing,
      Optional<String> step,
      Optional<String> failure) {}

  public static final ViewQuery BY_STANDING =
      ViewQuery.declare(
          "by-standing",
          "SELECT payload FROM "
              + ViewQuery.tableOf(CheckoutRows.class)
              + " WHERE payload::jsonb->>'standing' = :standing");

  private static final WorkflowLifecycle UNKNOWN =
      new WorkflowLifecycle("Unknown", Optional.empty(), Map.of(), Optional.empty());

  @Override
  protected ChangeSource<Checkout.CheckoutState> source() {
    return ChangeSource.ofWorkflow(Checkout.class);
  }

  @Override
  protected ViewEffect<CheckoutRow> onChange(Checkout.CheckoutState state) {
    // A change from a workflow carries its standing: where it stood once the effect that
    // recorded `state` was applied.
    var standing = updateContext().standing().orElse(UNKNOWN);
    return effects()
        .updateRow(
            new CheckoutRow(
                updateContext().subject(),
                state.status(),
                standing.status(),
                standing.pendingStep(),
                standing.failure()));
  }
}
