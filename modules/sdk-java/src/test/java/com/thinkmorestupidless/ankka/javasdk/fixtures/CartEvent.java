package com.thinkmorestupidless.ankka.javasdk.fixtures;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

/** What happened to a cart. */
@Manifest("shopping-cart-event")
public sealed interface CartEvent {
  record ItemAdded(LineItem item) implements CartEvent {}

  record ItemRemoved(String productId) implements CartEvent {}

  record CheckedOut() implements CartEvent {}
}
