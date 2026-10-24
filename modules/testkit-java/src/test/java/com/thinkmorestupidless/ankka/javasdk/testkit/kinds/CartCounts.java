package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Source;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedChange;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect;
import java.util.Map;

/** How many lines name each product, counted by asking the view's own declared query. */
@ComponentId("cart-counts")
public class CartCounts extends KeyedView<CartCounts.Row> {

  public record Row(String product, int lines) {}

  public static final ViewQuery OF_PRODUCT =
      ViewQuery.declare(
          "of-product",
          "SELECT payload FROM "
              + ViewQuery.tableOf(CartCounts.class)
              + " WHERE payload::jsonb->>'product' = :product");

  @Source(ShoppingCart.class)
  public KeyedViewEffect<Row> onCart(CartEvent event, KeyedChange<Row> change) {
    if (event instanceof CartEvent.ItemAdded added) {
      String product = added.item().productId();
      int known =
          change.rows().ask(OF_PRODUCT, Map.of("product", product)).stream()
              .mapToInt(Row::lines)
              .sum();
      return effects().updateRow(product, new Row(product, known + 1));
    }
    return effects().ignore();
  }
}
