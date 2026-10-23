package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.SnapshotEvery;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/** The cart: the Scala reference's {@code ShoppingCartEntity}, handler for handler. */
@ComponentId("shopping-cart")
@SnapshotEvery(3)
public class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, CartEvent> {

  private static final Problem CHECKED_OUT = Problem.conflict("cart is already checked out");

  @Override
  protected ShoppingCart emptyState() {
    return ShoppingCart.empty(context().entityId());
  }

  @Override
  protected ShoppingCart applyEvent(ShoppingCart state, CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded added -> state.addItem(added.item());
      case CartEvent.ItemRemoved removed -> state.removeItem(removed.productId());
      case CartEvent.CheckedOut ignored -> state.checkOut();
      case CartEvent.Discarded ignored -> state;
    };
  }

  @Command("add-item")
  public Effect<Done> addItem(LineItem item) {
    if (currentState().checkedOut()) return effects().error(CHECKED_OUT);
    if (item.quantity() <= 0) return effects().error("quantity must be greater than zero");
    return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
  }

  @Command("remove-item")
  public Effect<Done> removeItem(String productId) {
    if (currentState().checkedOut()) return effects().error(CHECKED_OUT);
    if (!currentState().holds(productId))
      return effects().error(Problem.notFound("cart does not contain '" + productId + "'"));
    return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
  }

  @Command("checkout")
  public Effect<ShoppingCart> checkout() {
    if (currentState().checkedOut()) return effects().error(CHECKED_OUT);
    if (currentState().items().isEmpty()) return effects().error("cannot check out an empty cart");
    return effects().persist(new CartEvent.CheckedOut()).thenReplyState();
  }

  @Command("discard")
  public Effect<Done> discard() {
    if (currentState().checkedOut()) return effects().error(CHECKED_OUT);
    return effects().persist(new CartEvent.Discarded()).thenDelete().thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<ShoppingCart> getCart() {
    return effects().reply(currentState());
  }

  @Query("total-quantity")
  public ReadOnlyEffect<Integer> totalQuantity() {
    return effects().reply(currentState().totalQuantity());
  }
}
