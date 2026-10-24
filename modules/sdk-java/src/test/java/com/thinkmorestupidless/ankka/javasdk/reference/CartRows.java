package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.HashMap;
import java.util.Map;

/** One row per cart: how many of each product, and whether it is checked out. */
@ComponentId("cart-rows")
public class CartRows extends View<CartEvent, CartRows.CartRow> {

  @Manifest("cart-row")
  public record CartRow(String cartId, Map<String, Integer> quantities, boolean checkedOut) {}

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCartEntity.class);
  }

  @Override
  protected ViewEffect<CartRow> onChange(CartEvent event) {
    var current =
        rowState().orElse(new CartRow(updateContext().subject(), Map.of(), false));
    var quantities = new HashMap<>(current.quantities());
    return switch (event) {
      case CartEvent.ItemAdded added -> {
        quantities.merge(added.item().productId(), added.item().quantity(), Integer::sum);
        yield effects().updateRow(new CartRow(current.cartId(), quantities, current.checkedOut()));
      }
      case CartEvent.ItemRemoved removed -> {
        quantities.remove(removed.productId());
        yield effects().updateRow(new CartRow(current.cartId(), quantities, current.checkedOut()));
      }
      case CartEvent.CheckedOut ignored ->
          effects().updateRow(new CartRow(current.cartId(), current.quantities(), true));
      // The deletion that follows removes the row, by the view's default deletion handler.
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
