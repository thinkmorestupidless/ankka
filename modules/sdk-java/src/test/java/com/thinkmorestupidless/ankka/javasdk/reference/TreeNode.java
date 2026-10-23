package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import java.util.Optional;

/** A node of a tree, placed under another or at the root. */
@ComponentId("tree-node")
public class TreeNode extends EventSourcedEntity<TreeNode.Placement, TreeNode.TreeEvent> {

  /** The node it is under, if any. */
  @Manifest("tree-node")
  public record Placement(Optional<String> under) {}

  @Manifest("tree-event")
  public sealed interface TreeEvent {
    record Placed(Optional<String> under) implements TreeEvent {}
  }

  @Override
  protected Placement emptyState() {
    return new Placement(Optional.empty());
  }

  @Override
  protected Placement applyEvent(Placement state, TreeEvent event) {
    return switch (event) {
      case TreeEvent.Placed placed -> new Placement(placed.under());
    };
  }

  /** Places the node under {@code under}; an empty name is the root. */
  @Command("place")
  public Effect<String> place(String under) {
    var parent = Optional.ofNullable(under).filter(u -> !u.isEmpty());
    return effects().persist(new TreeEvent.Placed(parent)).thenReply("placed");
  }
}
