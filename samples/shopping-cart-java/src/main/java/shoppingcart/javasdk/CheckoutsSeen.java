package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import shoppingcart.javasdk.CheckoutNotifier.CheckoutNotice;

/**
 * The checkouts read back from the topic they are published to, and how many notices of each were
 * read: one, unless a reader started over from the beginning.
 */
@ComponentId("checkouts-seen")
public class CheckoutsSeen extends View<CheckoutNotice, CheckoutsSeen.CheckoutSeen> {

  @Manifest("checkout-seen")
  public record CheckoutSeen(String cartId, long at, int notices) {}

  @Override
  protected ChangeSource<CheckoutNotice> source() {
    return ChangeSource.ofTopic(CheckoutNotifier.TOPIC, CheckoutNotice.class);
  }

  @Override
  protected ViewEffect<CheckoutSeen> onChange(CheckoutNotice notice) {
    int seen = rowState().map(CheckoutSeen::notices).orElse(0);
    return effects().updateRow(new CheckoutSeen(notice.cartId(), notice.at(), seen + 1));
  }
}
