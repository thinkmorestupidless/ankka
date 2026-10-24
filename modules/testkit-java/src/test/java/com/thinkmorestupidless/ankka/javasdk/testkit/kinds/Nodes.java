package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;

/** Every tree node as a row, with a recursive query for everything under one. */
@ComponentId("nodes")
public class Nodes extends View<TreeNode.Placed, Nodes.Row> {

  public record Row(String id, String name, String under) {}

  private static final String TABLE = ViewQuery.tableOf(Nodes.class);

  /** Every row under the row {@code row}, to any depth. */
  public static final ViewQuery UNDER =
      ViewQuery.declare(
          "under",
          "WITH RECURSIVE below AS ("
              + " SELECT row_key, payload FROM " + TABLE + " WHERE payload::jsonb->>'under' = :row"
              + " UNION"
              + " SELECT n.row_key, n.payload FROM " + TABLE + " n"
              + " JOIN below b ON n.payload::jsonb->>'under' = b.row_key"
              + ") SELECT payload FROM below");

  /** The rows named {@code name}, watchable. */
  public static final ViewQuery NAMED =
      ViewQuery.declare(
              "named",
              "SELECT row_key, payload FROM " + TABLE + " WHERE payload::jsonb->>'name' = :name")
          .watched();

  @Override
  protected ChangeSource<TreeNode.Placed> source() {
    return ChangeSource.of(TreeNode.class);
  }

  @Override
  protected ViewEffect<Row> onChange(TreeNode.Placed placed) {
    return effects().updateRow(new Row(updateContext().subject(), placed.name(), placed.under()));
  }
}
