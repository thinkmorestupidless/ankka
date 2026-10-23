package com.thinkmorestupidless.ankka.kotlin.codec;

import java.util.List;
import java.util.Optional;

/** The Java records a Kotlin data class of the same shape must encode as. */
public final class JavaShapes {
  public record Item(String productId, int quantity) {}

  public record Cart(String owner, List<Item> items, int discount, Optional<String> note) {}

  public sealed interface Event {
    record ItemAdded(Item item) implements Event {}

    record CheckedOut() implements Event {}
  }

  private JavaShapes() {}
}
