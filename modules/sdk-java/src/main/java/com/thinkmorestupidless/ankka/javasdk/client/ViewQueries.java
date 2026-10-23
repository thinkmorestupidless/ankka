package com.thinkmorestupidless.ankka.javasdk.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /** A declared query of this view, each value it takes by name; at most a thousand rows. */
  List<Row> ask(ViewQuery query, Map<String, String> values);

  List<Row> ask(ViewQuery query, int limit, Map<String, String> values);

  /** A declared query's rows as a stream, read as the subscriber asks for them. */
  Flow.Publisher<Row> askStream(ViewQuery query, Map<String, String> values);
}
