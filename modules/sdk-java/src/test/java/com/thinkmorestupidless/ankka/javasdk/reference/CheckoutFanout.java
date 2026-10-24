package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.Outgoing;
import java.util.List;
import java.util.Optional;

/**
 * Several messages for one change: three for a checkout (the second under a key of its own, the
 * third with a header), none for an item added, and a single one for an item removed.
 */
// docs:start consumer
@ComponentId("checkout-fanout")
public class CheckoutFanout extends Consumer<CartEvent, Fanned> {

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCartEntity.class);
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("conformance-fanout"));
  }

  @Override
  protected ConsumerEffect<Fanned> onChange(CartEvent event) {
    return switch (event) {
      case CartEvent.ItemAdded ignored -> effects().publishAll(List.of());
      case CartEvent.ItemRemoved ignored -> effects().publish(new Fanned(0));
      case CartEvent.CheckedOut ignored ->
          effects()
              .publishMessages(
                  List.of(
                      Outgoing.of(new Fanned(1)),
                      Outgoing.of(new Fanned(2)).withKey("second:" + messageContext().subject()),
                      Outgoing.of(new Fanned(3)).withMetadata("x-n", "3")));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
// docs:end consumer
