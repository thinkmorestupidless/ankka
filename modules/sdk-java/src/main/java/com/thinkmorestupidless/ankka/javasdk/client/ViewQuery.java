package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import java.util.Objects;

/**
 * A view's declared query: a named SQL statement over the view's table, each value it takes
 * written {@code :name}. Declare one as a {@code public static final} field of the view, naming
 * the table with {@link #tableOf}:
 *
 * <pre>{@code
 * public static final ViewQuery OPEN =
 *     ViewQuery.declare("open", "SELECT payload FROM " + ViewQuery.tableOf(CartRows.class)
 *         + " WHERE (payload::jsonb->>'checkedOut')::boolean = false");
 * }</pre>
 *
 * The statement is checked when the service starts.
 */
public final class ViewQuery {

  private final String name;
  private final String statement;
  private final boolean watched;

  private ViewQuery(String name, String statement, boolean watched) {
    this.name = name;
    this.statement = statement;
    this.watched = watched;
  }

  public static ViewQuery declare(String name, String statement) {
    return new ViewQuery(Objects.requireNonNull(name), Objects.requireNonNull(statement), false);
  }

  /** The same query, watchable: it must select each row's key beside the row. */
  public ViewQuery watched() {
    return new ViewQuery(name, statement, true);
  }

  /** The table of the view annotated {@code @ComponentId}. */
  public static String tableOf(Class<?> view) {
    ComponentId id = view.getAnnotation(ComponentId.class);
    if (id == null) {
      throw new IllegalArgumentException(view.getName() + " has no @ComponentId");
    }
    StringBuilder table = new StringBuilder("ankka_view_");
    id.value().chars().forEach(c -> table.append(Character.isLetterOrDigit(c) ? (char) c : '_'));
    return table.toString();
  }

  public String name() {
    return name;
  }

  public String statement() {
    return statement;
  }

  public boolean isWatched() {
    return watched;
  }

  @Override
  public String toString() {
    return "ViewQuery(" + name + ")";
  }
}
