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

  /** Who acted on the command that wrote it, as the command was told; empty when nobody said. */
  Optional<String> attribution();

  /** The installation the command that wrote it was forwarded from, when it was forwarded. */
  Optional<String> forwardedFrom();
}
