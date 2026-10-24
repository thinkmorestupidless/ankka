package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Source;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedChange;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect;

/**
 * A keyed view over two sources: how many carts have added each product, keyed by product, and
 * each tree node's name, keyed by node and removed with it.
 */
@ComponentId("product-carts")
public class ProductCarts extends KeyedView<ProductCarts.Row> {

  public record Row(String key, int count) {}

  @Source(ShoppingCart.class)
  public KeyedViewEffect<Row> onCart(CartEvent event, KeyedChange<Row> change) {
    if (event instanceof CartEvent.ItemAdded added) {
      String product = added.item().productId();
      int count = change.rows().get(product).map(Row::count).orElse(0);
      return effects().updateRow(product, new Row(product, count + 1));
    }
    return effects().ignore();
  }

  @Source(TreeNode.class)
  public KeyedViewEffect<Row> onNode(TreeNode.Placed placed, KeyedChange<Row> change) {
    return effects()
        .updateRow("node:" + change.subject(), new Row(placed.name(), 0))
        .and(effects().ignore());
  }

  @Source(value = TreeNode.class, deleted = true)
  public KeyedViewEffect<Row> onNodeRemoved(KeyedChange<Row> change) {
    return effects().deleteRow("node:" + change.subject());
  }
}
