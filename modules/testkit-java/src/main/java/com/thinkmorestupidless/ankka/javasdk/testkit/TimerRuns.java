package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.TimerRunsKit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the service's timers did, for a test to read: every run of every timer, and a timer as the
 * database holds it. A test of a cadence reads due times from here rather than from when its action
 * happened to run: a run starts up to a poll interval after its due, and the due is what the runtime
 * promises. The record is kept across {@code restartService()}.
 */
public final class TimerRuns {

  /** How one run ended. */
  public enum Outcome {
    /** The action succeeded. */
    DONE,
    /** The action failed, threw, or could not be reached. */
    FAILED,
    /** A timer that fires once named an action the service does not have, and was removed. */
    DROPPED,
    /** A recurring timer named an action this instance does not have, and was kept for later. */
    DEFERRED
  }

  /** One run: the due it was for, the attempts before it, how it ended and the next due, if any. */
  public record Run(
      String name,
      Instant dueTime,
      int attempts,
      Outcome outcome,
      Optional<Instant> next,
      Optional<Duration> period) {}

  /** A timer as the database holds it: the due its next run is for, and its failures since. */
  public record Scheduled(
      String name, String action, Instant dueTime, Optional<Duration> period, int attempts) {}

  private final TimerRunsKit kit;

  TimerRuns(TimerRunsKit kit) {
    this.kit = kit;
  }

  /** Every run of the timer named {@code name}, oldest first. */
  public List<Run> fired(String name) {
    return kit.fired(name);
  }

  /** The due times {@code name} was run for and finished, one for each run that succeeded. */
  public List<Instant> dueTimes(String name) {
    return kit.dueTimes(name);
  }

  /** The timer as the database holds it, or empty when there is none. */
  public Optional<Scheduled> scheduled(String name) {
    return kit.scheduled(name);
  }
}
