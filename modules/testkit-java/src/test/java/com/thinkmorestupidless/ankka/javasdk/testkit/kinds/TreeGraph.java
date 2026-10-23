package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect;
import java.util.List;
import java.util.Map;

/** Each tree node as a graph node, with an edge to the node it is under; a removal tombstones it. */
@ComponentId("tree-graph")
public class TreeGraph extends GraphConsumer<TreeNode.Placed> {

  @Override
  protected ChangeSource<TreeNode.Placed> source() {
    return ChangeSource.of(TreeNode.class);
  }

  @Override
  protected String topic() {
    return "tree-graph";
  }

  @Override
  protected GraphEffect onChange(TreeNode.Placed placed) {
    String id = messageContext().subject();
    var node =
        graph().node(id, List.of("Node"), Map.of("name", placed.name(), "tags", List.of("a", "b")));
    if (placed.under().isEmpty()) return effects().publish(node);
    var edge = graph().edge(id + "-under", "UNDER", id, placed.under(), Map.of());
    return effects().publish(node, edge);
  }

  @Override
  protected GraphEffect onDelete() {
    return effects().publish(graph().tombstoneNode(messageContext().subject()));
  }
}
