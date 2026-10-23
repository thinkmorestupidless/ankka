package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.bridge.ViewCell;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A queryable table kept up to date from one source: one row per source id, written by {@link
 * #onChange} and removed when the source is deleted. Rows are eventually consistent with the
 * source. Declare its queries as {@code public static final ViewQuery} fields.
 *
 * @param <Src> what the source delivers: an entity's events or state, a workflow's state, or a
 *     topic's messages
 * @param <Row> the row the view keeps per source id
 */
public abstract class View<Src, Row> {

  private final ViewCell cell = ViewCell.take();

  protected View() {}

  /** Where the view's changes come from, built with {@link ChangeSource}'s factories. */
  protected abstract ChangeSource<Src> source();

  /** The row for one change; returns {@code effects().updateRow(...)}, {@code ignore()} or {@code deleteRow()}. */
  protected abstract ViewEffect<Row> onChange(Src change);

  /** What happens when the source is deleted: the row is removed unless overridden. */
  protected ViewEffect<Row> onDelete() {
    return effects().deleteRow();
  }

  /**
   * The view's version. Raising it rebuilds the view from the start of its source into a fresh
   * table; empty means the view keeps the table it has.
   */
  protected OptionalInt version() {
    return OptionalInt.empty();
  }

  /** How many source ids the view updates at once; four unless overridden. */
  protected int parallelism() {
    return 4;
  }

  /** The row the change applies to, empty for a source id seen for the first time. */
  @SuppressWarnings("unchecked")
  protected final Optional<Row> rowState() {
    return Optional.ofNullable((Row) cell.row());
  }

  /** The change being handled: its source id and position. */
  protected final ChangeContext updateContext() {
    return cell.context();
  }

  @SuppressWarnings("unchecked")
  protected final ViewEffects<Row> effects() {
    return (ViewEffects<Row>) Effects.view();
  }
}
