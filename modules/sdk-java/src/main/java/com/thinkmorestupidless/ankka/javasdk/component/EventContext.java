package com.thinkmorestupidless.ankka.javasdk.component;

import java.util.Optional;

/** Where the event being applied was written, read inside {@code applyEvent}. */
public interface EventContext {

  /** The installation that wrote it; empty for an entity that is not replicated. */
  Optional<String> installation();

  /** Whether it was written at this installation. */
  boolean isLocal();

  /** Its position in the entity's journal. */
  long sequenceNumber();
}
