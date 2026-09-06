package nakka.core.effect

import nakka.core.{CommandError, ErrorCode}

/**
 * The outcome of a scheduled call.
 *
 * Timers are at-least-once and are only removed once they succeed, so `Fail` means
 * "reschedule me". A timed action for work that has become obsolete must therefore
 * return `done()`, not fail — otherwise it retries forever. This is the sharpest edge in
 * the timer API and the reason the two cases are named this bluntly.
 */
sealed trait TimedActionEffect

object TimedActionEffect:
  case object Done                           extends TimedActionEffect
  final case class Fail(error: CommandError) extends TimedActionEffect

/** The `effects` surface inside a timed action. */
final class TimedActionEffects private[nakka] ():

  /** Complete the timer. It will not fire again. */
  def done(): TimedActionEffect = TimedActionEffect.Done

  /** Fail, so the runtime reschedules with backoff. */
  def error(message: String): TimedActionEffect =
    TimedActionEffect.Fail(CommandError(message))

  def error(message: String, code: ErrorCode): TimedActionEffect =
    TimedActionEffect.Fail(CommandError(message, code))
