package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.Optional;

/** One row per node, walked by a declared recursive query and watched by another. */
@ComponentId("tree-rows")
public class TreeRows extends View<TreeNode.TreeEvent, TreeRows.TreeRow> {

  @Manifest("tree-row")
  public record TreeRow(String key, Optional<String> under) {}

  private static final String TABLE = ViewQuery.tableOf(TreeRows.class);

  /** Every row under the row {@code row}, to any depth, by key: the same in every language. */
  public static final ViewQuery UNDER =
      ViewQuery.declare(
          "under",
          "WITH RECURSIVE below AS (\n"
              + "  SELECT row_key, payload FROM "
              + TABLE
              + " WHERE payload::jsonb->>'under' = :row\n"
              + "  UNION\n"
              + "  SELECT n.row_key, n.payload FROM "
              + TABLE
              + " n JOIN below b ON n.payload::jsonb->>'under' = b.row_key\n"
              + ")\n"
              + "SELECT payload FROM below ORDER BY row_key");

  /** Every row, by key, watchable: the same statement in every language. */
  public static final ViewQuery ALL_ROWS =
      ViewQuery.declare("all-rows", "SELECT row_key, payload FROM " + TABLE + " ORDER BY row_key")
          .watched();

  @Override
  protected ChangeSource<TreeNode.TreeEvent> source() {
    return ChangeSource.of(TreeNode.class);
  }

  @Override
  protected ViewEffect<TreeRow> onChange(TreeNode.TreeEvent event) {
    return switch (event) {
      case TreeNode.TreeEvent.Placed placed ->
          effects().updateRow(new TreeRow(updateContext().subject(), placed.under()));
    };
  }
}
