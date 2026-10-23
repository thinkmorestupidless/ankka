package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Source;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedChange;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A keyed view of two sources: rows the left names, which the right finds by a query. */
@ComponentId("joined-rows")
public class JoinedRows extends KeyedView<JoinedRows.JoinedRow> {

  /** A row the left writes under the key it names, holding a right entity's id. */
  @Manifest("joined-row")
  public record JoinedRow(String key, String holding, List<String> notes) {
    JoinedRow noting(String who) {
      var more = new ArrayList<>(notes);
      more.add(who);
      return new JoinedRow(key, holding, List.copyOf(more));
    }
  }

  /** The rows holding one right entity, by key: the same statement in every language. */
  public static final ViewQuery OF_RIGHT =
      ViewQuery.declare(
          "of-right",
          "SELECT payload FROM "
              + ViewQuery.tableOf(JoinedRows.class)
              + " WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key");

  /** The left names a row {@code key|holding}, and writes it from what it held, noting itself. */
  @Source(JoinedLeft.class)
  public KeyedViewEffect<JoinedRow> onLeft(Noted event, KeyedChange<JoinedRow> change) {
    var parts = event.text().split("\\|", 2);
    var key = parts[0];
    var holding = parts[1];
    var held = change.rows().get(key).map(JoinedRow::notes).orElse(List.of());
    return effects().updateRow(key, new JoinedRow(key, holding, held).noting("left"));
  }

  /** The right finds every row holding it by asking the view's own query, and notes itself. */
  @Source(JoinedRight.class)
  public KeyedViewEffect<JoinedRow> onRight(Noted event, KeyedChange<JoinedRow> change) {
    var theirs = change.rows().ask(OF_RIGHT, Map.of("holding", change.subject()));
    var rows = new LinkedHashMap<String, JoinedRow>();
    for (var row : theirs) rows.put(row.key(), row.noting("right"));
    return effects().updateRows(rows);
  }
}
