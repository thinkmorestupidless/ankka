package nakka.core.effect

import nakka.core.{CommandError, ErrorCode, Metadata}

import scala.concurrent.duration.FiniteDuration

/**
 * A description of what the runtime should do for one command against a key value
 * entity.
 *
 * Where an event sourced entity records *what happened*, a key value entity records only
 * *what is* — so this algebra replaces state wholesale instead of appending events.
 */
sealed trait KeyValueEffect[S, +R]:
  private[nakka] def newState: Option[S]
  private[nakka] def retention: Option[Retention]
  private[nakka] def outcome: Outcome[S, R]

/** An effect that provably leaves state untouched. */
sealed trait KeyValueReadOnlyEffect[S, +R] extends KeyValueEffect[S, R]:
  private[nakka] final def newState: Option[S]          = None
  private[nakka] final def retention: Option[Retention] = None

object KeyValueEffect:

  private[nakka] final case class Updating[S, R](
      newState: Option[S],
      retention: Option[Retention],
      outcome: Outcome[S, R]
  ) extends KeyValueEffect[S, R]

  private[nakka] final case class ReadOnly[S, R](
      outcome: Outcome[S, R]
  ) extends KeyValueReadOnlyEffect[S, R]

  /** Reduced form shared by the runtime interpreter and the testkit. */
  final case class Materialised[S, R](
      newState: S,
      changed: Boolean,
      retention: Option[Retention],
      reply: Either[CommandError, Option[R]]
  )

  private[nakka] def materialise[S, R](
      effect: KeyValueEffect[S, R],
      current: S
  ): Materialised[S, R] =
    effect.outcome match
      case Outcome.Fail(error) =>
        Materialised(current, changed = false, None, Left(error))
      case Outcome.NoReply =>
        val next = effect.newState.getOrElse(current)
        Materialised(next, effect.newState.isDefined, effect.retention, Right(None))
      case Outcome.Reply(compute, _) =>
        val next = effect.newState.getOrElse(current)
        Materialised(
          next,
          effect.newState.isDefined,
          effect.retention,
          Right(Some(compute(next)))
        )

/** The `effects` surface inside a key value entity's command handlers. */
final class KeyValueEffects[S] private[nakka] ():
  import KeyValueEffect.*

  /** Replace the entity's state, then decide what to reply. */
  def updateState(state: S): UpdateBuilder[S] =
    new UpdateBuilder(Some(state), None)

  /** Delete the entity's state. */
  def deleteEntity(): UpdateBuilder[S] =
    new UpdateBuilder(None, Some(Retention.DeleteNow))

  def reply[R](value: R): KeyValueReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Reply(_ => value, Metadata.empty))

  def reply[R](value: R, metadata: Metadata): KeyValueReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Reply(_ => value, metadata))

  def error[R](message: String): KeyValueReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Fail(CommandError(message)))

  def error[R](message: String, code: ErrorCode): KeyValueReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Fail(CommandError(message, code)))

  def error[R](failure: CommandError): KeyValueReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Fail(failure))

  def noReply[R]: KeyValueReadOnlyEffect[S, R] =
    ReadOnly(Outcome.NoReply)

/** Accumulates the pending state change before the reply is chosen. */
final class UpdateBuilder[S] private[nakka] (
    private val newState: Option[S],
    private val retention: Option[Retention]
):
  import KeyValueEffect.*

  /** Delete automatically after `duration` with no further update. */
  def expireAfter(duration: FiniteDuration): UpdateBuilder[S] =
    new UpdateBuilder(newState, Some(Retention.ExpireAfter(duration)))

  def thenReply[R](compute: S => R): KeyValueEffect[S, R] =
    Updating(newState, retention, Outcome.Reply(compute, Metadata.empty))

  def thenReply[R](compute: S => R, metadata: Metadata): KeyValueEffect[S, R] =
    Updating(newState, retention, Outcome.Reply(compute, metadata))

  /** Reply with the updated state itself. */
  def thenReplyState: KeyValueEffect[S, S] =
    Updating(newState, retention, Outcome.Reply(identity, Metadata.empty))

  def thenNoReply[R]: KeyValueEffect[S, R] =
    Updating(newState, retention, Outcome.NoReply)
