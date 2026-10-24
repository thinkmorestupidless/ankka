package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.Outgoing;
import java.util.List;
import java.util.Optional;

/**
 * Publishes a cart's events as lines: one per added item, keyed by the cart; one per removal keyed
 * by the product; two on checkout, each under its own key and with metadata.
 */
@ComponentId("cart-lines")
public class CartLines extends Consumer<CartEvent, CartLines.Line> {

  public record Line(String cartId, String what) {}

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("cart-lines"));
  }

  /** Published once from wherever a replicated cart's events are written, at every installation. */
  @Override
  protected boolean mirroredChanges() {
    return true;
  }

  @Override
  protected ConsumerEffect<Line> onChange(CartEvent event) {
    String cart = messageContext().subject();
    return switch (event) {
      case CartEvent.ItemAdded added ->
          effects().publish(new Line(cart, "added " + added.item().productId()));
      case CartEvent.ItemRemoved removed ->
          effects().publish(new Line(cart, "removed " + removed.productId()), removed.productId());
      case CartEvent.CheckedOut ignored ->
          effects()
              .publishMessages(
                  List.of(
                      Outgoing.of(new Line(cart, "checked out")).withKey("checkouts"),
                      Outgoing.of(new Line(cart, "thanks"))
                          .withKey("receipts")
                          .withMetadata("ce-channel", "email")));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
