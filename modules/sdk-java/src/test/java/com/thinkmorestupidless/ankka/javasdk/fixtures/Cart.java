package com.thinkmorestupidless.ankka.javasdk.fixtures;

import java.util.ArrayList;
import java.util.List;

/** The cart's state. */
public record Cart(List<LineItem> items, boolean checkedOut) {

  public static final Cart EMPTY = new Cart(List.of(), false);

  public Cart add(LineItem item) {
    List<LineItem> next = new ArrayList<>();
    boolean merged = false;
    for (LineItem existing : items) {
      if (existing.productId().equals(item.productId())) {
        next.add(new LineItem(item.productId(), item.name(), existing.quantity() + item.quantity()));
        merged = true;
      } else {
        next.add(existing);
      }
    }
    if (!merged) next.add(item);
    return new Cart(List.copyOf(next), checkedOut);
  }

  public Cart remove(String productId) {
    return new Cart(items.stream().filter(i -> !i.productId().equals(productId)).toList(), checkedOut);
  }
}
