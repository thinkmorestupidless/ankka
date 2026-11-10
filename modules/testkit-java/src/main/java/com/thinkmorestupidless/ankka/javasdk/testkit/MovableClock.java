package com.thinkmorestupidless.ankka.javasdk.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock a test moves. Give it to {@link Settings#withClock}, and a timer due in a week fires, or
 * a workflow's pause of a day ends, when the test says the time has passed. It never moves on its
 * own, and only forward.
 *
 * <pre>{@code
 * var clock = MovableClock.at(Instant.parse("2026-11-10T09:00:00Z"));
 * try (var kit = AnkkaTestKit.start(Main.service(), Settings.defaults().withClock(clock))) {
 *   clock.moveBy(Duration.ofDays(1));
 * }
 * }</pre>
 */
public final class MovableClock extends Clock {

  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  private MovableClock(AtomicReference<Instant> now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  /** A clock at {@code instant}, in UTC. */
  public static MovableClock at(Instant instant) {
    return new MovableClock(new AtomicReference<>(instant), ZoneOffset.UTC);
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  /** The same clock, read in another zone: moving either moves both. */
  @Override
  public Clock withZone(ZoneId other) {
    return new MovableClock(now, other);
  }

  /** Moves the clock to {@code instant}, which may not be before where it is. */
  public void moveTo(Instant instant) {
    now.updateAndGet(
        current -> {
          if (instant.isBefore(current)) {
            throw new IllegalArgumentException(
                "a MovableClock moves forward only: " + instant + " is before " + current);
          }
          return instant;
        });
  }

  /** Moves the clock forward by {@code duration}. */
  public void moveBy(Duration duration) {
    moveTo(instant().plus(duration));
  }
}
