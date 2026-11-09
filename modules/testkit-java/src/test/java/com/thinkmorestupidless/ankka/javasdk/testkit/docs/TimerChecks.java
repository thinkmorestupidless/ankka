package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartTimers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Reading a cadence from what the kit recorded of a timer's runs. */
public final class TimerChecks {

  private TimerChecks() {}

  // docs:start due-times
  /** The due times of a recurring timer's first three runs, a second apart. */
  public static List<Instant> threeDueTimes(AnkkaTestKit kit) {
    kit.timers()
        .scheduleRecurring("tick-o-7", Duration.ZERO, Duration.ofSeconds(1), CartTimers::tick);
    kit.eventually(
        Duration.ofSeconds(20),
        () -> {
          if (kit.timerRuns().dueTimes("tick-o-7").size() < 3)
            throw new AssertionError("fewer than three runs so far");
        });
    kit.timers().cancel("tick-o-7");
    // A run starts up to a poll interval after its due time, so the cadence is read from the due
    // times the kit recorded, not from when the handler ran: due.get(1) is due.get(0) plus a
    // second, and due.get(2) a second after that.
    return kit.timerRuns().dueTimes("tick-o-7");
  }
  // docs:end due-times
}
