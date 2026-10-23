package com.thinkmorestupidless.ankka.javasdk.cart;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.io.IOException;

@ComponentId("shopping-cart")
public class ShoppingCart extends EventSourcedEntity<CartState, CartEvent> {

  @Override
  protected CartState emptyState() {
    return CartState.empty(context().entityId());
  }

  @Command("add-item")
  public Effect<Done> addItem(LineItem item) {
    if (currentState().checkedOut()) return effects().error(Problem.badRequest("the cart is checked out"));
    if (item.quantity() <= 0) return effects().error("a quantity must be positive");
    return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
  }

  @Command("remove-item")
  public Effect<Done> removeItem(String productId) {
    if (currentState().items().stream().noneMatch(i -> i.productId().equals(productId)))
      return effects().error(Problem.notFound("no item " + productId));
    return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
  }

  @Command("checkout")
  public Effect<Done> checkout() {
    if (currentState().checkedOut()) return effects().reply(Done.DONE);
    return effects().persist(new CartEvent.CheckedOut()).thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<CartState> getCart() {
    return effects().reply(currentState());
  }

  /** Fails as asked: {@code unchecked} or {@code checked}, after persisting nothing. */
  @Command("fail")
  public Effect<Done> fail(String kind) throws IOException {
    if (kind.equals("checked")) throw new IOException("a checked failure");
    throw new IllegalStateException("an unchecked failure");
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
