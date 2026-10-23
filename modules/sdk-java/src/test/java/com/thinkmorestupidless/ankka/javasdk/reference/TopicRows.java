package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.OptionalInt;

/** The latest message about each subject. Declares no start, so it reads from the earliest. */
@ComponentId("topic-rows")
public class TopicRows extends View<Fanned, Fanned> {

  /** The topic the conformance target fills before the service starts. */
  public static final String TOPIC = "conformance-topic";

  @Override
  protected ChangeSource<Fanned> source() {
    return ChangeSource.ofTopic(TOPIC, Fanned.class);
  }

  // Raised from 1 once: every reference declares 2, which names the group it reads under.
  @Override
  protected OptionalInt version() {
    return OptionalInt.of(2);
  }

  @Override
  protected ViewEffect<Fanned> onChange(Fanned message) {
    return effects().updateRow(message);
  }
}
