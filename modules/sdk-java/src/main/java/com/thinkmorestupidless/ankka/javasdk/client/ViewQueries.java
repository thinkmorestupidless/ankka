package com.thinkmorestupidless.ankka.javasdk.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Reads of one view's rows. The methods block the calling virtual thread, as a component call
 * does.
 */
public interface ViewQueries<Row> {

  /** The row under {@code key}: a view's source id, or a keyed view's key. */
  Optional<Row> get(String key);

  /** Every row, at most a thousand. */
  List<Row> all();

  List<Row> all(int limit);

  /** How many rows the view holds. */
  long count();

  /** As {@link #get}, without waiting: the row, once read. */
  CompletionStage<Optional<Row>> getAsync(String key);

  /** As {@link #all(int)}, without waiting. */
  CompletionStage<List<Row>> allAsync(int limit);

  /** As {@link #count}, without waiting. */
  CompletionStage<Long> countAsync();

  /** As {@link #ask(ViewQuery, Map)}, without waiting. */
  CompletionStage<List<Row>> askAsync(ViewQuery query, Map<String, String> values);

  /** A declared query of this view, each value it takes by name; at most a thousand rows. */
  List<Row> ask(ViewQuery query, Map<String, String> values);

  List<Row> ask(ViewQuery query, int limit, Map<String, String> values);

  /** A declared query's rows as a stream, read as the subscriber asks for them. */
  Flow.Publisher<Row> askStream(ViewQuery query, Map<String, String> values);

  /**
   * The rows a query declared {@code watched()} selects now, then each row as it is written or
   * removed, for as long as the watch is read. Refused for a query the view does not declare, or one
   * it does not declare watched.
   */
  ViewWatch<Row> watch(ViewQuery query, Map<String, String> values);

  /** One row by its key, now and as it is written or removed. */
  ViewWatch<Row> watchRow(String key);
}
