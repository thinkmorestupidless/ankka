package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.Optional;

/**
 * A task as it was read: its id and type, its status as a wire word ({@code pending}, {@code
 * assigned}, {@code in-progress}, {@code result-rejected}, {@code completed}, {@code failed} or
 * {@code cancelled}), why it ended when it failed or was cancelled, the instance it is assigned to,
 * the iterations spent on it, its result once it has one, and the whole record as the JSON every
 * SDK reads, which carries what the fields above do not (when it was made, assigned and ended).
 */
public record TaskSnapshot<R>(
    String id,
    String type,
    String status,
    Optional<String> reason,
    Optional<String> instanceId,
    int iterations,
    Optional<R> result,
    String json) {

  /** Whether the task has completed, failed or been cancelled. */
  public boolean ended() {
    return status.equals("completed") || status.equals("failed") || status.equals("cancelled");
  }
}
