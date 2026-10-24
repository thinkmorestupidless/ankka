package com.thinkmorestupidless.ankka.javasdk.fixtures;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** The cart: the same rules the Scala sample's cart has. */
@ComponentId("cart")
public class ShoppingCart extends EventSourcedEntity<Cart, CartEvent> {

  @Override
  protected Cart emptyState() {
    return Cart.EMPTY;
  }

  /** The method's name is not its wire name: renaming it changes nothing on the wire. */
  @Command("add-item")
  public Effect<Done> putInCart(LineItem item) {
    if (currentState().checkedOut()) return effects().error("the cart is checked out");
    if (item.quantity() <= 0) return effects().error("a quantity must be positive");
    return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
  }

  @Command("remove-item")
  public Effect<Done> removeItem(String productId) {
    if (currentState().checkedOut()) return effects().error("the cart is checked out");
    return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
  }

  @Command("checkout")
  public Effect<Cart> checkout() {
    if (currentState().checkedOut()) return effects().error("the cart is checked out");
    return effects().persist(new CartEvent.CheckedOut()).thenReply(state -> state);
  }

  @Query("get-cart")
  public ReadOnlyEffect<Cart> getCart() {
    return effects().reply(currentState());
  }

  @Query("whoami")
  public ReadOnlyEffect<String> whoami() {
    return effects().reply(context().entityId());
  }

  @Override
  protected Cart applyEvent(Cart state, CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded added -> state.add(added.item());
      case CartEvent.ItemRemoved removed -> state.remove(removed.productId());
      case CartEvent.CheckedOut ignored -> new Cart(state.items(), true);
    };
  }
}
