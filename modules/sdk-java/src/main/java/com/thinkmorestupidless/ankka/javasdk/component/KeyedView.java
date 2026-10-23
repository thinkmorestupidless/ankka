package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffects;
import java.util.OptionalInt;

/**
 * A view that reads several sources and names its rows by key: each {@code @Source} method
 * handles one source's changes and writes or deletes any rows it chooses. A method takes the
 * change, or the change and its {@link KeyedChange}; a {@code @Source(deleted = true)} method
 * takes nothing or the {@link KeyedChange}, and handles the source's deletions. Declare queries
 * as {@code public static final ViewQuery} fields.
 *
 * @param <Row> the row the view keeps per key
 */
public abstract class KeyedView<Row> {

  protected KeyedView() {}

  /** The view's version; raising it rebuilds the view into a fresh table. */
  protected OptionalInt version() {
    return OptionalInt.empty();
  }

  @SuppressWarnings("unchecked")
  protected final KeyedViewEffects<Row> effects() {
    return (KeyedViewEffects<Row>) Effects.keyedView();
  }
}
