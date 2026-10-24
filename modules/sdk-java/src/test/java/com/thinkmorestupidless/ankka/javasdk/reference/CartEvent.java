package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

/** The Scala reference's {@code ShoppingCartEvent}, case for case. */
@Manifest("shopping-cart-event")
public sealed interface CartEvent {
  record ItemAdded(LineItem item) implements CartEvent {}

  record ItemRemoved(String productId) implements CartEvent {}

  record CheckedOut() implements CartEvent {}

  record Discarded() implements CartEvent {}
}
