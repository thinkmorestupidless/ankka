package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A keyed view's own rows, read while it handles a change. */
public interface ViewRows<Row> {

  /** The row under {@code key}. */
  Optional<Row> get(String key);

  /** A declared query of this view, each value it takes by name. */
  List<Row> ask(ViewQuery query, Map<String, String> values);
}
