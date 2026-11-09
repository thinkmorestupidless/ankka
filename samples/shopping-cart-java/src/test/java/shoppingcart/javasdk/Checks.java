package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.Published;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** What a test of the running service checks, as a Java test writes it. */
public final class Checks {

  private Checks() {}

  // docs:start published
  /** The keys of the notices the notifier published, once one has: the cart's id. */
  public static List<Optional<String>> noticeKeys() {
    try (var kit = AnkkaTestKit.start(Main.service())) {
      var cart = kit.componentClient().forEventSourcedEntity("c-7");
      cart.call(ShoppingCart::addItem).invoke(new LineItem("p1", "Pen", 1));
      cart.call(ShoppingCart::checkout).invoke();
      // Poll until the notice arrives: the consumer publishes after the entity has replied.
      kit.eventually(
          Duration.ofSeconds(20),
          () -> {
            if (kit.published(CheckoutNotifier.TOPIC).isEmpty()) throw new AssertionError("no notice");
          });
      return kit.published(CheckoutNotifier.TOPIC).stream().map(Published::key).toList();
    }
  }
  // docs:end published
}
