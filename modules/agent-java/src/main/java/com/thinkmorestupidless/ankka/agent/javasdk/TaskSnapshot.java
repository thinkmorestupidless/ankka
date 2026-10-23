package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.Optional;

/**
 * A task as it was read: its id and type, its status as a wire word ({@code pending}, {@code
 * assigned}, {@code in-progress}, {@code result-rejected}, {@code completed}, {@code failed} or
 * {@code cancelled}), why it ended when it failed or was cancelled, the instance it is assigned to,
 * the iterations spent on it, and its result once it has one.
 */
public record TaskSnapshot<R>(
    String id,
    String type,
    String status,
    Optional<String> reason,
    Optional<String> instanceId,
    int iterations,
    Optional<R> result) {

  /** Whether the task has completed, failed or been cancelled. */
  public boolean ended() {
    return status.equals("completed") || status.equals("failed") || status.equals("cancelled");
  }
}
