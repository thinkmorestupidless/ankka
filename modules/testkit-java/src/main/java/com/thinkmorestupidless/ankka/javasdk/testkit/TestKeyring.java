package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.ServiceKit;
import java.time.Duration;

/**
 * The keyring the service's personal fields are sealed with (feature 042): the kit's own, in
 * memory, kept across {@code restartService()} as the installation's keyring outlives a restart.
 * The Scala kit's {@code keyring}, the same one.
 */
public final class TestKeyring {

  private final ServiceKit kit;

  TestKeyring(ServiceKit kit) {
    this.kit = kit;
  }

  /**
   * Erases {@code subject} in the kit's project now: its subject key is destroyed at once, every
   * personal field of it reads as erased, and the service is sent the notice. Answers the erasure's
   * sequence. Refused as unavailable in an outage, as a stopped keyring refuses the control plane.
   */
  public long erase(String subject) {
    return kit.erase(subject);
  }

  /**
   * Refuses every request as unavailable for {@code duration}: a subject whose key the service
   * holds is still read and written, and one it does not hold is refused, with {@code
   * ErrorCode.UNAVAILABLE}.
   */
  public void outage(Duration duration) {
    kit.outage(duration);
  }

  /** Ends an outage now. */
  public void recover() {
    kit.recover();
  }
}
