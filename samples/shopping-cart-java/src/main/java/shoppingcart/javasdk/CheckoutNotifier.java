package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import java.util.Optional;

/**
 * Turns the cart's checkout into a notice on the {@code cart-checkouts} topic. The cart's own
 * events stay an implementation detail; the notice is what the outside world is told.
 */
@ComponentId("checkout-notifier")
public class CheckoutNotifier extends Consumer<CartEvent, CheckoutNotifier.CheckoutNotice> {

  /** The topic checkout notices are published to, and read from. */
  public static final String TOPIC = "cart-checkouts";

  /** What leaves the service when a cart is checked out. */
  @Manifest("checkout-notice")
  public record CheckoutNotice(String cartId, long at) {}

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to(TOPIC));
  }

  @Override
  protected ConsumerEffect<CheckoutNotice> onChange(CartEvent event) {
    return switch (event) {
      case CartEvent.CheckedOut ignored ->
          effects()
              .publish(new CheckoutNotice(messageContext().subject(), System.currentTimeMillis()));
      case CartEvent.ItemAdded ignored -> effects().ignore();
      case CartEvent.ItemRemoved ignored -> effects().ignore();
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
