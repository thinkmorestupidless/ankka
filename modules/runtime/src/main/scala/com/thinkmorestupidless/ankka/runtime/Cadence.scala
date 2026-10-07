package com.thinkmorestupidless.ankka.runtime

import java.time.Instant

/**
 * A recurring timer's next due, worked out in one place.
 *
 * The next due is the due just run plus one period — never the time the handler finished plus one
 * period, so a handler's own duration does not move the cadence. When that has already passed,
 * because the service was down, the handler kept failing or one run outlasted a period, it is the
 * first cadence point still to come: the periods between are never fired, and the cadence stays
 * where it was.
 */
private[ankka] object Cadence:

  /** `dueFor + k × period` for the smallest whole `k ≥ 1` that is after `now`. */
  def next(dueFor: Instant, periodMillis: Long, now: Instant): Instant =
    dueFor.plusMillis(Math.multiplyExact(skipped(dueFor, periodMillis, now) + 1, periodMillis))

  /** How many cadence points after `dueFor` have already passed at `now`. */
  def skipped(dueFor: Instant, periodMillis: Long, now: Instant): Long =
    require(periodMillis > 0, s"a period is positive, not $periodMillis ms")
    val elapsed = now.toEpochMilli - dueFor.toEpochMilli
    if elapsed < periodMillis then 0L else elapsed / periodMillis
