package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;

/** One row per checkout, from the workflow's state after each transition. */
@ComponentId("checkout-rows")
public class CheckoutRows extends View<Checkout.State, CheckoutRows.Row> {

  public record Row(String cartId, String status) {}

  @Override
  protected ChangeSource<Checkout.State> source() {
    return ChangeSource.ofWorkflow(Checkout.class);
  }

  @Override
  protected ViewEffect<Row> onChange(Checkout.State state) {
    return effects().updateRow(new Row(state.cartId(), state.status()));
  }
}
