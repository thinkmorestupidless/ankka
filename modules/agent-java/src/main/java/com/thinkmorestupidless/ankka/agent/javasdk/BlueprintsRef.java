package com.thinkmorestupidless.ankka.agent.javasdk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The service's blueprints, from {@link AgentClients#blueprints}. */
public interface BlueprintsRef {

  /**
   * Checks {@code blueprint} against the registry and keeps it as a new version when it differs
   * from the current one. One with problems is refused with all of them.
   */
  Registered register(Blueprint blueprint);

  /** As {@link #register(Blueprint)}, from the blueprint's JSON form. */
  Registered register(String json);

  List<Version> versions(String name);

  Optional<Blueprint> current(String name);

  /** A registration: the version it is, whether it was new, and what the check noted. */
  record Registered(String name, int version, boolean isNew, List<String> notes) {}

  record Version(int number, String digest, Instant registeredAt) {}
}
