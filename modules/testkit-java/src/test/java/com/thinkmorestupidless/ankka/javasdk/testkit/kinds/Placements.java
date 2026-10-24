package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;

/** Places a tree node for each placement read from a topic: a consumer that calls, not publishes. */
@ComponentId("placements")
public class Placements extends Consumer<Placements.Placement, Void> {

  public record Placement(String node, String name, String under) {}

  @Override
  protected ChangeSource<Placement> source() {
    return ChangeSource.ofTopic("placements", Placement.class).fromEarliest();
  }

  @Override
  protected ConsumerEffect<Void> onChange(Placement placement) {
    componentClient()
        .forKeyValueEntity(placement.node())
        .call(TreeNode::place)
        .invoke(new TreeNode.Placed(placement.name(), placement.under()));
    return effects().done();
  }
}
