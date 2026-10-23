package com.thinkmorestupidless.ankka.javasdk.codec;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shapes {@code protocol/fixtures} were generated from ({@code EncodingShapes} in {@code
 * modules/core}), declared as a Java developer would declare them.
 */
public final class Shapes {

  private Shapes() {}

  public record LineItem(String productId, String name, int quantity) {}

  @Manifest("shopping-cart")
  public record ShoppingCart(List<LineItem> items, boolean checkedOut) {}

  @Manifest("shopping-cart-event")
  public sealed interface ShoppingCartEvent {}

  public record ItemAdded(LineItem item) implements ShoppingCartEvent {}

  public record ItemRemoved(String productId) implements ShoppingCartEvent {}

  public record CheckedOut() implements ShoppingCartEvent {}

  public record Step(String name, int order) {}

  @Manifest("plan")
  public record Plan(String title, List<Step> steps, Map<String, String> labels) {}

  @Manifest("status")
  public enum Status {
    Ready,
    Failed
  }

  @Manifest("service")
  public record Service(
      String name, Instant createdAt, Optional<String> owner, Optional<String> note, Status status) {}

  @Manifest("service-event")
  public sealed interface ServiceEvent {}

  public record Applied(long generation, Optional<Instant> at) implements ServiceEvent {}

  @Manifest("numbers")
  public record Numbers(long big, double half, double tenBillion, double tenth, int negative) {}

  @Manifest("tree")
  public record Tree(String label, List<Tree> children) {}
}
