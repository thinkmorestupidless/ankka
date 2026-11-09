package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Ledger;
import java.time.Duration;

/** Several installations of one service in one test, replicating between them. */
public final class FleetKits {

  private FleetKits() {}

  private static ServiceBuilder ledgers() {
    return Ankka.service("ledgers").register(Ledger.class);
  }

  // docs:start several-installations
  /** Writes at the home and reads the mirrored copy at the follower. */
  public static Ledger.State mirrored() {
    try (var fleet = AnkkaTestKit.installations("smoke-a", "smoke-b")) {
      var a = fleet.start("smoke-a", ledgers());
      var b = fleet.start("smoke-b", ledgers());
      var atA = a.componentClient().forEventSourcedEntity("v1");
      var atB = b.componentClient().forEventSourcedEntity("v1");
      atA.call(Ledger::share).invoke("smoke-b");
      for (int n = 1; n <= 3; n++) atA.call(Ledger::enter).invoke("step-" + n);
      b.eventually(
          Duration.ofSeconds(20),
          () -> {
            if (atB.call(Ledger::get).invoke().entries().size() < 3)
              throw new AssertionError("smoke-b does not hold the three steps yet");
          });
      // Each entry says where it was written: "step-1@smoke-a (mirrored)" at smoke-b.
      return atB.call(Ledger::get).invoke();
    }
  }
  // docs:end several-installations
}
