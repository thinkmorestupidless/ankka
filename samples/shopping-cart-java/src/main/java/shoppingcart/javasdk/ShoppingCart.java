package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;

// docs:start entity
/** A customer's cart, one per cart id. Its handlers' wire names are the Scala cart's. */
@ComponentId("shopping-cart")
public class ShoppingCart extends EventSourcedEntity<Cart, CartEvent> {

  @Override
  protected Cart emptyState() {
    return Cart.empty(context().entityId());
  }

  @Command("add-item")
  public Effect<Done> addItem(LineItem item) {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (item.quantity() <= 0)
      return effects().error("quantity must be greater than zero, was " + item.quantity());
    return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
  }

  @Command("remove-item")
  public Effect<Done> removeItem(String productId) {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (!currentState().contains(productId))
      return effects().error(Problem.notFound("cart does not contain '" + productId + "'"));
    return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
  }

  @Command("checkout")
  public Effect<Cart> checkout() {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    if (currentState().isEmpty()) return effects().error("cannot check out an empty cart");
    return effects().persist(new CartEvent.CheckedOut()).thenReplyState();
  }

  @Command("discard")
  public Effect<Done> discard() {
    if (currentState().checkedOut()) return effects().error(checkedOut());
    return effects().persist(new CartEvent.Discarded()).thenDelete().thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<Cart> getCart() {
    return effects().reply(currentState());
  }

  @Query("total-quantity")
  public ReadOnlyEffect<Integer> totalQuantity() {
    return effects().reply(currentState().totalQuantity());
  }

  @Override
  protected Cart applyEvent(Cart state, CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded added -> state.addItem(added.item());
      case CartEvent.ItemRemoved removed -> state.removeItem(removed.productId());
      case CartEvent.CheckedOut ignored -> state.onCheckedOut();
      case CartEvent.Discarded ignored -> state;
    };
  }

  private static Problem checkedOut() {
    return Problem.conflict("cart is already checked out");
  }
}
// docs:end entity
