package com.thinkmorestupidless.ankka.javasdk.effect;

import java.util.Collection;
import java.util.Map;

/** The effects a keyed view's source methods return. */
public interface KeyedViewEffects<Row> {

  KeyedViewEffect<Row> updateRow(String key, Row row);

  KeyedViewEffect<Row> deleteRow(String key);

  /** Write several rows, in the map's iteration order. */
  KeyedViewEffect<Row> updateRows(Map<String, Row> rows);

  KeyedViewEffect<Row> deleteRows(Collection<String> keys);

  /** Change nothing. */
  KeyedViewEffect<Row> ignore();
}
