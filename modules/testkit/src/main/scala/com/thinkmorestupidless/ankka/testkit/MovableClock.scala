package com.thinkmorestupidless.ankka.testkit

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/**
 * A clock a test moves. Hand it to `TimerRuntime(clock = …)` and a timer due in a week fires when
 * the test says the week has passed. It never moves on its own.
 */
final class MovableClock private (now: AtomicReference[Instant], zone: ZoneId) extends Clock:

  def instant(): Instant = now.get()
  def getZone: ZoneId    = zone

  override def withZone(other: ZoneId): Clock = new MovableClock(now, other)

  def moveTo(instant: Instant): Unit =
    now.updateAndGet { current =>
      if instant.isBefore(current) then
        throw IllegalArgumentException(
          s"a MovableClock moves forward only: $instant is before $current"
        )
      else instant
    }: Unit

  def moveBy(duration: FiniteDuration): Unit = moveTo(instant().plusMillis(duration.toMillis))

object MovableClock:
  def at(instant: Instant, zone: ZoneId = ZoneOffset.UTC): MovableClock =
    new MovableClock(AtomicReference(instant), zone)
