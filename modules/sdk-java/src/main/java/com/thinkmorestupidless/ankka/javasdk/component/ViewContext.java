package com.thinkmorestupidless.ankka.javasdk.component;

import java.util.Optional;

/** The change a view or consumer is handling. */
public interface ViewContext {

  /** The source id: the entity's or workflow's id, or the message's subject. */
  String subject();

  /** The change's position in its source. */
  long sequenceNumber();

  /** Whether the change was made in this installation rather than replicated into it. */
  boolean localOrigin();

  /** The installation the change was made in, when the source is replicated. */
  Optional<String> installation();
}
