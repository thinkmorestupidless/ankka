package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.MovableClock;
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartTimers;
import java.time.Duration;
import java.time.Instant;

/** A timer an hour away, fired by moving the kit's clock rather than by waiting. */
public final class ClockChecks {

  private ClockChecks() {}

  /** The runs of the timer before the clock moved, and after. */
  public record Runs(long before, long after) {}

  public static Runs anHourAway() throws InterruptedException {
    // docs:start movable-clock
    var clock = MovableClock.at(Instant.parse("2026-11-10T09:00:00Z"));
    var settings = Settings.defaults().withClock(clock);
    try (var kit = AnkkaTestKit.start(Ankka.service("timers").register(CartTimers.class), settings)) {
      kit.timers().schedule("expire-c-9", Duration.ofHours(1), CartTimers::expire, "c-9");
      // docs:end movable-clock
      // Several polls of the sweeper, with the clock where it was: the timer is not yet due.
      Thread.sleep(1_000);
      long before = runs();
      // docs:start move
      clock.moveBy(Duration.ofHours(1));
      // docs:end move
      kit.eventually(
          Duration.ofSeconds(10),
          () -> {
            if (runs() == before) throw new AssertionError("the timer has not run");
          });
      return new Runs(before, runs());
    }
  }

  private static long runs() {
    return CartTimers.runs.stream().filter(r -> r.timer().equals("expire-c-9")).count();
  }
}
