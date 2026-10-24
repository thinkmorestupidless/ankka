package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.KindKits;

/**
 * One consumer driven in memory: hand it a change, read back what it would publish. Nothing is
 * started; the change is decoded as the runtime decodes it and the effect applied by the function
 * the runtime applies it with, against a publisher that only records. A result the runtime would
 * refuse throws here, where in a running service the change would be delivered again.
 */
public final class ConsumerTestKit<Src, Out> {

  private final KindKits.ConsumerKit kit;

  private ConsumerTestKit(KindKits.ConsumerKit kit) {
    this.kit = kit;
  }

  public static <Src, Out> ConsumerTestKit<Src, Out> of(
      Class<? extends Consumer<Src, Out>> consumer) {
    return of(consumer, TestClient.of());
  }

  /** The consumer, its calls to other components answered by {@code client}. */
  public static <Src, Out> ConsumerTestKit<Src, Out> of(
      Class<? extends Consumer<Src, Out>> consumer, TestClient client) {
    return new ConsumerTestKit<>(KindKits.consumer(consumer, client.stubs()));
  }

  /** A graph consumer: what it reads back are the deltas, as the merge sink reads them. */
  public static <Src> GraphConsumerTestKit<Src> graph(
      Class<? extends GraphConsumer<Src>> consumer) {
    return graph(consumer, TestClient.of());
  }

  /** A graph consumer, its calls to other components answered by {@code client}. */
  public static <Src> GraphConsumerTestKit<Src> graph(
      Class<? extends GraphConsumer<Src>> consumer, TestClient client) {
    return new GraphConsumerTestKit<>(KindKits.graph(consumer, client.stubs()));
  }

  /** Hands the consumer {@code message}, a change of {@code test} at sequence number one. */
  public ConsumerResult<Out> onMessage(Src message) {
    return onMessage(message, "test", 1);
  }

  /** Hands the consumer {@code message}, a change of {@code subject} at {@code sequenceNumber}. */
  public ConsumerResult<Out> onMessage(Src message, String subject, long sequenceNumber) {
    return kit.onMessage(message, subject, sequenceNumber);
  }

  /** Tells the consumer that {@code subject}, its source entity, was deleted. */
  public ConsumerResult<Out> onDelete(String subject) {
    return kit.onDelete(subject);
  }
}
