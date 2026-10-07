package com.thinkmorestupidless.ankka.sdk

import scala.concurrent.duration.*

/**
 * What a recurring timer's period may be, with the one message every way of setting one gives.
 *
 * Shared by the runtime's scheduler, through which the sidecar and the module host schedule too, so
 * a period refused in Scala, in a process and in a module is refused in the same words.
 */
object TimerRules:

  /** The longest period: a century, which keeps every due time far inside what the table holds. */
  val MaxPeriod: FiniteDuration = 36_500.days

  /** The period in milliseconds, or why it cannot be one. */
  def period(timer: String, period: FiniteDuration): Either[String, Long] =
    if period < 1.milli || period > MaxPeriod then
      Left(
        s"timer '$timer' has a period of $period; a period is from 1 millisecond to " +
          s"${MaxPeriod.toDays} days"
      )
    else Right(period.toMillis)
