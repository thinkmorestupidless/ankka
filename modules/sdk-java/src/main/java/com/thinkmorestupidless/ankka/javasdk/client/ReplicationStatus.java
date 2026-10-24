package com.thinkmorestupidless.ankka.javasdk.client;

import java.util.Optional;
import java.util.Set;

/**
 * How the copy of a replicated entity at this installation stands: its home, its replication set,
 * a pending handover, a conflict, and how much of its history it holds.
 */
public record ReplicationStatus(
    Optional<String> home,
    Set<String> replicationSet,
    Optional<String> pendingHandover,
    Optional<String> conflict,
    long position) {

  /** Whether the entity was created at two installations and an operator must resolve it. */
  public boolean inConflict() {
    return conflict.isPresent();
  }
}
