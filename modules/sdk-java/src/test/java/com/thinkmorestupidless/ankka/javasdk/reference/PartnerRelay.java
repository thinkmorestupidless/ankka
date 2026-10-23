package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import java.util.Optional;

/** Reads project {@code partner}'s {@code conformance-shared} and republishes to its other. */
@ComponentId("partner-relay")
public class PartnerRelay extends Consumer<Fanned, Fanned> {

  static final String PARTNER = "partner";

  @Override
  protected ChangeSource<Fanned> source() {
    return ChangeSource.ofTopic(PARTNER, "conformance-shared", Fanned.class).fromEarliest();
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to(PARTNER, "conformance-shared-relayed"));
  }

  @Override
  protected ConsumerEffect<Fanned> onChange(Fanned message) {
    return effects().publish(message);
  }
}
