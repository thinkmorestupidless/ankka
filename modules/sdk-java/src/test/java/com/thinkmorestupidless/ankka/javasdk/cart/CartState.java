package com.thinkmorestupidless.ankka.javasdk.cart;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The Scala sample's {@code ShoppingCart} state, field for field. */
@Manifest("shopping-cart")
public record CartState(String cartId, List<LineItem> items, boolean checkedOut) {

  public static CartState empty(String cartId) {
    return new CartState(cartId, List.of(), false);
  }

  public CartState addItem(LineItem item) {
    List<LineItem> next = new ArrayList<>();
    int quantity = item.quantity();
    for (LineItem existing : items) {
      if (existing.productId().equals(item.productId())) quantity += existing.quantity();
      else next.add(existing);
    }
    next.add(new LineItem(item.productId(), item.name(), quantity));
    next.sort(Comparator.comparing(LineItem::productId));
    return new CartState(cartId, List.copyOf(next), checkedOut);
  }

  public CartState removeItem(String productId) {
    return new CartState(
        cartId,
        items.stream().filter(i -> !i.productId().equals(productId)).toList(),
        checkedOut);
  }

  public CartState onCheckedOut() {
    return new CartState(cartId, items, true);
  }
}
