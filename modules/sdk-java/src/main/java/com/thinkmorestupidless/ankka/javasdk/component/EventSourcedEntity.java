package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.EntityCell;
import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.effect.EventSourcedEffects;
import org.jspecify.annotations.Nullable;

/**
 * An entity whose state is the fold of the events it has persisted. The platform runs one instance
 * per id across the cluster and hands it one command at a time, so a handler is ordinary
 * sequential code.
 *
 * <pre>{@code
 * @ComponentId("cart")
 * public class ShoppingCart extends EventSourcedEntity<Cart, CartEvent> {
 *   @Command("add-item")
 *   public Effect<Done> addItem(LineItem item) {
 *     return effects().persist(new ItemAdded(item)).thenDone();
 *   }
 *
 *   @Override
 *   protected Cart applyEvent(Cart state, CartEvent event) { ... }
 * }
 * }</pre>
 *
 * <p>A constructor takes nothing, or the {@link EntityContext}.
 */
public abstract class EventSourcedEntity<S, E> {

  private final EntityCell cell = EntityCell.take();

  protected EventSourcedEntity() {}

  /** The state before any event; {@code null} unless overridden. */
  protected @Nullable S emptyState() {
    return null;
  }

  /**
   * Folds one event into the state: the one place state changes. Runs on replay as on a new event,
   * so it reads nothing but its arguments.
   */
  protected abstract S applyEvent(S state, E event);

  /** The state now: after replay, and after the events this command persisted so far. */
  @SuppressWarnings("unchecked")
  protected final S currentState() {
    return (S) cell.currentState();
  }

  /** This instance and, inside a handler, the command it is handling. */
  protected final EntityContext context() {
    return cell.entityContext();
  }

  /** The effects a handler returns. */
  @SuppressWarnings("unchecked")
  protected final EventSourcedEffects<S, E> effects() {
    return (EventSourcedEffects<S, E>) Effects.eventSourced();
  }
}
