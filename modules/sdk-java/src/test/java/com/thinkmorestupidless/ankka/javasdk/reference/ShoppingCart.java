package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A cart's state, field for field the Scala reference's: what makes the journal shared. */
@Manifest("shopping-cart")
public record ShoppingCart(String cartId, List<LineItem> items, boolean checkedOut) {

  static ShoppingCart empty(String cartId) {
    return new ShoppingCart(cartId, List.of(), false);
  }

  /** The item added, merged with a line for the same product, the lines kept by product. */
  ShoppingCart addItem(LineItem item) {
    var merged = item;
    for (var existing : items)
      if (existing.productId().equals(item.productId()))
        merged = new LineItem(item.productId(), item.name(), existing.quantity() + item.quantity());
    var lines = new ArrayList<LineItem>(withoutProduct(item.productId()));
    lines.add(merged);
    lines.sort(Comparator.comparing(LineItem::productId));
    return new ShoppingCart(cartId, List.copyOf(lines), checkedOut);
  }

  ShoppingCart removeItem(String productId) {
    return new ShoppingCart(cartId, withoutProduct(productId), checkedOut);
  }

  ShoppingCart checkOut() {
    return new ShoppingCart(cartId, items, true);
  }

  boolean holds(String productId) {
    return items.stream().anyMatch(i -> i.productId().equals(productId));
  }

  int totalQuantity() {
    return items.stream().mapToInt(LineItem::quantity).sum();
  }

  private List<LineItem> withoutProduct(String productId) {
    return items.stream().filter(i -> !i.productId().equals(productId)).toList();
  }
}
