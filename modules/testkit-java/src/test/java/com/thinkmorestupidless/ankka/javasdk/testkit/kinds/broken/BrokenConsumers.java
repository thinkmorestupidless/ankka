package com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.TreeNode;
import java.util.Optional;

/** Consumers declared wrongly, each refused at start. */
public final class BrokenConsumers {
  private BrokenConsumers() {}

  /** Names a topic to publish to but has nothing to publish. */
  @ComponentId("publishes-void")
  public static class PublishesVoid extends Consumer<TreeNode.Placed, Void> {
    @Override
    protected ChangeSource<TreeNode.Placed> source() {
      return ChangeSource.of(TreeNode.class);
    }

    @Override
    protected Optional<Publication> publishes() {
      return Optional.of(Publication.to("nowhere"));
    }

    @Override
    protected ConsumerEffect<Void> onChange(TreeNode.Placed change) {
      return effects().done();
    }
  }

  /** A graph consumer with no topic. */
  @ComponentId("no-topic")
  public static class NoTopic extends GraphConsumer<TreeNode.Placed> {
    @Override
    protected ChangeSource<TreeNode.Placed> source() {
      return ChangeSource.of(TreeNode.class);
    }

    @Override
    protected String topic() {
      return "";
    }

    @Override
    protected GraphEffect onChange(TreeNode.Placed change) {
      return effects().ignore();
    }
  }
}
