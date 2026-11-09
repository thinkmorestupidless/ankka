package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import java.util.List;
import java.util.Map;

// docs:start graph-from-state
/**
 * Publishes what each cart holds, as a node of its own beside the cart's.
 *
 * <p>An event says what changed, one item added, and an element is its whole state: how many
 * lines the cart has now. So this consumer does not build the element from the event. It reads
 * the cart through the component client and publishes what it finds, at the version of the event
 * it is handling.
 *
 * <p>The cart it reads may already be ahead of that event. The delta then carries a later state
 * at an earlier version, and the events still to come publish it again at their own: the graph is
 * never behind for longer than the consumer is, and ends where the cart is.
 */
@ComponentId("cart-contents-graph")
public class CartContentsGraph extends GraphConsumer<CartEvent> {

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected String topic() {
    return "cart-contents";
  }

  @Override
  protected GraphEffect onChange(CartEvent event) {
    if (event instanceof CartEvent.Discarded) return effects().ignore();
    var cartId = messageContext().subject();
    var cart =
        componentClient().forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke();
    int quantity = cart.items().stream().mapToInt(LineItem::quantity).sum();
    return effects()
        .publish(
            graph()
                .node(
                    "cart-contents:" + cartId,
                    List.of("CartContents"),
                    Map.of("cartId", cartId, "lines", cart.items().size(), "quantity", quantity)));
  }

  @Override
  protected GraphEffect onDelete() {
    return effects().publish(graph().tombstoneNode("cart-contents:" + messageContext().subject()));
  }
}
// docs:end graph-from-state
