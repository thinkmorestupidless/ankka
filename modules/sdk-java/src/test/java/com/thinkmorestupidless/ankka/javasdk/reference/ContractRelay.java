package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Contract;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Reads {@code conformance-contracts} as {@code order.v1} on broker {@code legacy}, partitions in
 * parallel, and publishes one more.
 */
@ComponentId("contract-relay")
public class ContractRelay extends Consumer<Fanned, Fanned> {

  /** The contract every reference states, from the same schema document: the fixtures' one. */
  static final String ORDER_SCHEMA =
      "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"required\":[\"id\",\"total\"],\"properties\":{\"id\":{\"type\":\"string\"},"
          + "\"total\":{\"type\":\"number\"}}}";

  static final Contract ORDER =
      Contract.fromSchema("order.v1", ORDER_SCHEMA.getBytes(StandardCharsets.UTF_8));

  /** The declared broker the reference names; every conformance target declares it. */
  static final String BROKER = "legacy";

  @Override
  protected ChangeSource<Fanned> source() {
    return ChangeSource.ofTopic("conformance-contracts", Fanned.class)
        .fromEarliest()
        .withContract(ORDER)
        .onBroker(BROKER)
        .parallel();
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(
        Publication.to("conformance-contracted").withContract(ORDER).onBroker(BROKER));
  }

  @Override
  protected ConsumerEffect<Fanned> onChange(Fanned message) {
    return effects().publish(new Fanned(message.n() + 1));
  }
}
