package {{package}};

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

// docs:start events
/** What happened to a cart: the journal holds these, as the Scala cart's journal does. */
@Manifest("shopping-cart-event")
public sealed interface CartEvent {
  record ItemAdded(LineItem item) implements CartEvent {}

  record ItemRemoved(String productId) implements CartEvent {}

  record CheckedOut() implements CartEvent {}

  record Discarded() implements CartEvent {}
}
// docs:end events
