package com.thinkmorestupidless.ankka.runtime

import java.time.Instant

/**
 * Told what the sweeper did each time it ran a timer.
 *
 * For a test, which has to assert due times a handler cannot see — the next due after a success,
 * the time a retry is set for. Called on the sweeper's thread pool, never from its actor, after the
 * statement that follows the run; it must not block for long, and one that throws is logged and
 * changes nothing.
 */
trait TimerObserver:
  def fired(timer: FiredTimer): Unit

object TimerObserver:
  /** What a service has: nothing is told. */
  val none: TimerObserver = _ => ()

/**
 * One run of a timer, as the sweeper saw it.
 *
 * @param dueTime
 *   the due time the run was for, which the handler was told
 * @param attempts
 *   how many times it had failed before this run
 * @param next
 *   when the sweeper will next run it, or `None` when it was removed
 */
final case class FiredTimer(
    name: String,
    dueTime: Instant,
    attempts: Int,
    outcome: FiredTimer.Outcome,
    next: Option[Instant],
    period: Option[Long]
)

object FiredTimer:
  enum Outcome:
    /** The handler succeeded. */
    case Done

    /** The handler failed, threw, or its process could not be reached. */
    case Failed

    /** A timer that fires once named a handler the service does not have, and was removed. */
    case Dropped

    /** A recurring timer named a handler this instance does not have, and was kept for later. */
    case Deferred
