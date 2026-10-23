package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.HashMap;
import java.util.Map;

/**
 * Every cart as a row, to answer what the entity cannot: which carts hold a product, which are
 * checked out. The row's JSON is the Scala cart's {@code cart-row}.
 */
// docs:start view
@ComponentId("cart-rows")
public class CartRows extends View<CartEvent, CartRows.CartRow> {

  @Manifest("cart-row")
  public record CartRow(
      String cartId,
      Map<String, Integer> quantities,
      boolean checkedOut,
      Map<String, String> names) {}

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected ViewEffect<CartRow> onChange(CartEvent event) {
    var current =
        rowState().orElse(new CartRow(updateContext().subject(), Map.of(), false, Map.of()));
    var quantities = new HashMap<>(current.quantities());
    var names = new HashMap<>(current.names());
    return switch (event) {
      case CartEvent.ItemAdded added -> {
        quantities.merge(added.item().productId(), added.item().quantity(), Integer::sum);
        names.put(added.item().productId(), added.item().name());
        yield effects()
            .updateRow(new CartRow(current.cartId(), quantities, current.checkedOut(), names));
      }
      case CartEvent.ItemRemoved removed -> {
        quantities.remove(removed.productId());
        names.remove(removed.productId());
        yield effects()
            .updateRow(new CartRow(current.cartId(), quantities, current.checkedOut(), names));
      }
      case CartEvent.CheckedOut ignored ->
          effects()
              .updateRow(
                  new CartRow(current.cartId(), current.quantities(), true, current.names()));
      // The deletion that follows removes the row: a discarded cart leaves the listing.
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
// docs:end view
