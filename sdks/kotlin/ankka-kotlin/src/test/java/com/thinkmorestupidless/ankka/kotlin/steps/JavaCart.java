package com.thinkmorestupidless.ankka.kotlin.steps;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import java.util.ArrayList;
import java.util.List;

/** The cart of {@code features/java-kotlin/kotlin.feature}, declared in Java, as `KotlinCart` is in Kotlin. */
@ComponentId("cart")
public class JavaCart extends EventSourcedEntity<JavaCart.State, JavaCart.Event> {

  @Manifest("item")
  public record Item(String name) {}

  @Manifest("cart-state")
  public record State(List<Item> items) {}

  @Manifest("cart-event")
  public sealed interface Event {
    record Added(Item item) implements Event {}
  }

  @Override
  protected State emptyState() {
    return new State(List.of());
  }

  @Override
  protected State applyEvent(State state, Event event) {
    return switch (event) {
      case Event.Added added -> {
        var items = new ArrayList<>(state.items());
        items.add(added.item());
        yield new State(List.copyOf(items));
      }
    };
  }

  @Command("add-item")
  public Effect<Done> addItem(Item item) {
    return effects().persist(new Event.Added(item)).thenDone();
  }

  @Query("get-cart")
  public ReadOnlyEffect<State> getCart() {
    return effects().reply(currentState());
  }
}
