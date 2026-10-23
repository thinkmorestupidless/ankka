package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphElement;
import java.util.List;
import java.util.Map;

/**
 * The carts as a graph: the cart's node for an item added or removed, the cart checked out with
 * its checkout and the edge between them for a checkout, the cart's tombstone when it is deleted.
 */
@ComponentId("cart-graph")
public class CartGraph extends GraphConsumer<CartEvent> {

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCartEntity.class);
  }

  @Override
  protected String topic() {
    return "conformance-graph";
  }

  private GraphElement cart(String id, boolean checkedOut) {
    return graph()
        .node("cart:" + id, List.of("Cart"), Map.of("cartId", id, "checkedOut", checkedOut));
  }

  @Override
  protected GraphEffect onChange(CartEvent event) {
    var id = messageContext().subject();
    return switch (event) {
      case CartEvent.ItemAdded ignored -> effects().publish(cart(id, false));
      case CartEvent.ItemRemoved ignored -> effects().publish(cart(id, false));
      case CartEvent.CheckedOut ignored ->
          effects()
              .publish(
                  cart(id, true),
                  graph().node("checkout:" + id, List.of("Checkout"), Map.of("cartId", id)),
                  graph()
                      .edge("checked-out:" + id, "CHECKED_OUT", "cart:" + id, "checkout:" + id,
                          Map.of()));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }

  @Override
  protected GraphEffect onDelete() {
    return effects().publish(graph().tombstoneNode("cart:" + messageContext().subject()));
  }
}
