package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.component.GraphDelta;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.KindKits;
import java.util.List;

/**
 * One graph consumer driven in memory, from {@link ConsumerTestKit#graph}: each delta it publishes
 * is read from the bytes and key it would be published under, by the reader the merge sink uses.
 */
public final class GraphConsumerTestKit<Src> {

  private final KindKits.GraphKit kit;

  GraphConsumerTestKit(KindKits.GraphKit kit) {
    this.kit = kit;
  }

  /** Hands the consumer {@code message}, a change of {@code test} at sequence number one. */
  public List<GraphDelta> onMessage(Src message) {
    return onMessage(message, "test", 1);
  }

  public List<GraphDelta> onMessage(Src message, String subject, long sequenceNumber) {
    return kit.onMessage(message, subject, sequenceNumber);
  }

  /** Tells the consumer that {@code subject}, its source entity, was deleted. */
  public List<GraphDelta> onDelete(String subject) {
    return kit.onDelete(subject);
  }
}
