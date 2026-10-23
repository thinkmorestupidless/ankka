package com.thinkmorestupidless.ankka.javasdk.rename;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** The cart after its developer renamed {@code addItem} to {@code putItem}; the wire name stays. */
@ComponentId("shopping-cart")
public class RenamedCart extends EventSourcedEntity<CartState, CartEvent> {

  @Override
  protected CartState emptyState() {
    return CartState.empty(context().entityId());
  }

  @Command("add-item")
  public Effect<Done> putItem(LineItem item) {
    return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<CartState> getCart() {
    return effects().reply(currentState());
  }

  @Override
  protected CartState applyEvent(CartState state, CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded added -> state.addItem(added.item());
      case CartEvent.ItemRemoved removed -> state.removeItem(removed.productId());
      case CartEvent.CheckedOut ignored -> state.onCheckedOut();
      case CartEvent.Discarded ignored -> state;
    };
  }
}
