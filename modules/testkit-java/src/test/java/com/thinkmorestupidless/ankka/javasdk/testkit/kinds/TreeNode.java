package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;

/** A node of a tree, placed under another by id ({@code ""} for a root), and removable. */
@ComponentId("tree-node")
public class TreeNode extends KeyValueEntity<TreeNode.Placed> {

  public record Placed(String name, String under) {}

  @Command("place")
  public Effect<Done> place(Placed placed) {
    return effects().updateState(placed).thenDone();
  }

  @Command("remove")
  public Effect<Done> remove() {
    return effects().deleteEntity().thenDone();
  }
}
