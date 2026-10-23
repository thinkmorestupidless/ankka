package com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Source;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedChange;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.TreeNode;

/** Views declared wrongly, each in a way the service refuses at start. */
public final class BrokenViews {
  private BrokenViews() {}

  public record Row(String name) {}

  /** Twice the same query name. */
  @ComponentId("twice")
  public static class Twice extends View<TreeNode.Placed, Row> {
    public static final ViewQuery A = ViewQuery.declare("same", "SELECT payload FROM x");
    public static final ViewQuery B = ViewQuery.declare("same", "SELECT payload FROM y");

    @Override
    protected ChangeSource<TreeNode.Placed> source() {
      return ChangeSource.of(TreeNode.class);
    }

    @Override
    protected ViewEffect<Row> onChange(TreeNode.Placed placed) {
      return effects().updateRow(new Row(placed.name()));
    }
  }

  /** A source method of three parameters, and one naming both a class and a topic. */
  @ComponentId("misshapen")
  public static class Misshapen extends KeyedView<Row> {
    @Source(TreeNode.class)
    public KeyedViewEffect<Row> three(TreeNode.Placed p, KeyedChange<Row> c, String extra) {
      return effects().ignore();
    }

    @Source(value = TreeNode.class, topic = "nodes")
    public KeyedViewEffect<Row> both(TreeNode.Placed p) {
      return effects().ignore();
    }
  }
}
