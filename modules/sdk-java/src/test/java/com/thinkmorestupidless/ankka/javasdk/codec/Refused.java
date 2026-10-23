package com.thinkmorestupidless.ankka.javasdk.codec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shapes the codec refuses at start, and shapes it accepts that are easy to get wrong. */
public final class Refused {

  private Refused() {}

  public record WithFloat(String name, float weight) {}

  public record WithIntKeys(Map<Integer, String> byNumber) {}

  public record WithTypeField(String type) {}

  public record Inner(List<Short> counts) {}

  public record Outer(Inner inner) {}

  public static final class NotARecord {
    public String name = "";
  }

  public record HoldsAClass(NotARecord thing) {}

  public record Node(String label, Optional<Node> next, List<Node> children) {}

  public record Discounted(String productId, Optional<Integer> discount) {}

  public record Basket(List<Discounted> items) {}

  public record Big(long value, int small) {}

  public sealed interface Shape {}

  public record Circle(double radius) implements Shape {}

  public record Square(double side) implements Shape {}
}
