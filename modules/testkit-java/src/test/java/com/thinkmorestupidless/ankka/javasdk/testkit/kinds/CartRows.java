package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.OptionalInt;

/** One row per cart from the cart's events, at version 2. */
@ComponentId("cart-rows")
public class CartRows extends View<CartEvent, CartRows.Row> {

  public record Row(String cartId, int lines, boolean checkedOut) {}

  /** The carts not checked out. */
  public static final ViewQuery OPEN =
      ViewQuery.declare(
          "open",
          "SELECT payload FROM "
              + ViewQuery.tableOf(CartRows.class)
              + " WHERE (payload::jsonb->>'checkedOut')::boolean = false");

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected OptionalInt version() {
    return OptionalInt.of(2);
  }

  @Override
  protected ViewEffect<Row> onChange(CartEvent event) {
    Row row = rowState().orElse(new Row(updateContext().subject(), 0, false));
    return switch (event) {
      case CartEvent.ItemAdded added ->
          effects().updateRow(new Row(row.cartId(), row.lines() + 1, row.checkedOut()));
      case CartEvent.ItemRemoved removed ->
          effects().updateRow(new Row(row.cartId(), row.lines() - 1, row.checkedOut()));
      case CartEvent.CheckedOut ignored ->
          effects().updateRow(new Row(row.cartId(), row.lines(), true));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
