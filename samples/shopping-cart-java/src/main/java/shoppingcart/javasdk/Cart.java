package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A cart: its lines, ordered by product, and whether it has been checked out. */
@Manifest("shopping-cart")
public record Cart(String cartId, List<LineItem> items, boolean checkedOut) {

  public static Cart empty(String cartId) {
    return new Cart(cartId, List.of(), false);
  }

  /** Adds {@code item}, merging it into the line for the same product. */
  public Cart addItem(LineItem item) {
    var merged =
        items.stream()
            .filter(i -> i.productId().equals(item.productId()))
            .findFirst()
            .map(e -> new LineItem(item.productId(), item.name(), e.quantity() + item.quantity()))
            .orElse(item);
    var next = new ArrayList<>(without(item.productId()));
    next.add(merged);
    next.sort(Comparator.comparing(LineItem::productId));
    return new Cart(cartId, List.copyOf(next), checkedOut);
  }

  public Cart removeItem(String productId) {
    return new Cart(cartId, without(productId), checkedOut);
  }

  public Cart onCheckedOut() {
    return new Cart(cartId, items, true);
  }

  public boolean contains(String productId) {
    return items.stream().anyMatch(i -> i.productId().equals(productId));
  }

  public int totalQuantity() {
    return items.stream().mapToInt(LineItem::quantity).sum();
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  private List<LineItem> without(String productId) {
    return items.stream().filter(i -> !i.productId().equals(productId)).toList();
  }
}
