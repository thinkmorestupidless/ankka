package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Contract;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import java.util.Optional;

/** A consumer of a topic that states its contracts and its broker, at both ends. */
@ComponentId("contracted")
public class Contracted extends Consumer<String, String> {

  public static final Contract orders = new Contract("orders", "sha256:0a1b");
  public static final Contract lines = new Contract("order-lines", "sha256:2c3d");

  @Override
  protected ChangeSource<String> source() {
    return ChangeSource.ofTopic("orders", String.class)
        .fromEarliest()
        .withContract(orders)
        .onBroker("events")
        .parallel();
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("order-lines").withContract(lines).onBroker("events"));
  }

  @Override
  protected ConsumerEffect<String> onChange(String order) {
    return effects().publish(order);
  }
}
