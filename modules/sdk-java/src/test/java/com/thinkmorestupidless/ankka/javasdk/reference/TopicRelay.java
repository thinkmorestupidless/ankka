package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import java.util.Optional;

/** Republishes what it reads, from the latest: none of what the topic held when it started. */
@ComponentId("topic-relay")
public class TopicRelay extends Consumer<Fanned, Fanned> {

  @Override
  protected ChangeSource<Fanned> source() {
    return ChangeSource.ofTopic(TopicRows.TOPIC, Fanned.class).fromLatest();
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("conformance-topic-relayed"));
  }

  @Override
  protected ConsumerEffect<Fanned> onChange(Fanned message) {
    return effects().publish(message);
  }
}
