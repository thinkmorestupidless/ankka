package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.EntityCell;
import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyValueEffects;
import org.jspecify.annotations.Nullable;

/**
 * An entity that keeps only its current state, with no history. The same one-instance,
 * one-command-at-a-time guarantees as an event sourced entity.
 */
public abstract class KeyValueEntity<S> {

  private final EntityCell cell = EntityCell.take();

  protected KeyValueEntity() {}

  /** The state before anything is written; {@code null} unless overridden. */
  protected @Nullable S emptyState() {
    return null;
  }

  @SuppressWarnings("unchecked")
  protected final S currentState() {
    return (S) cell.currentState();
  }

  protected final EntityContext context() {
    return cell.entityContext();
  }

  @SuppressWarnings("unchecked")
  protected final KeyValueEffects<S> effects() {
    return (KeyValueEffects<S>) Effects.keyValue();
  }
}
