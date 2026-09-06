package nakka.core.effect

import nakka.core.{CommandError, ErrorCode, Metadata}

import scala.concurrent.duration.FiniteDuration

/**
 * A *description* of what the runtime should do for one command against an event sourced
 * entity. Building one performs no I/O and touches no state, which is what lets unit
 * tests assert on handler behaviour with no runtime, no database and no cluster.
 *
 * State can only ever change by persisting an event — there is deliberately no
 * `updateState` here, mirroring the guarantee Akka's event sourced entities make.
 */
sealed trait EventSourcedEffect[S, E, +R]:
  private[nakka] def events: Vector[E]
  private[nakka] def retention: Option[Retention]
  private[nakka] def outcome: Outcome[S, R]

/**
 * An effect that provably persists nothing, so the runtime may serve it from a replica
 * without routing to the entity's write region.
 */
sealed trait ReadOnlyEffect[S, E, +R] extends EventSourcedEffect[S, E, R]:
  private[nakka] final def events: Vector[E]            = Vector.empty
  private[nakka] final def retention: Option[Retention] = None

object EventSourcedEffect:

  private[nakka] final case class Persisting[S, E, R](
      events: Vector[E],
      retention: Option[Retention],
      outcome: Outcome[S, R]
  ) extends EventSourcedEffect[S, E, R]

  private[nakka] final case class ReadOnly[S, E, R](
      outcome: Outcome[S, R]
  ) extends ReadOnlyEffect[S, E, R]

  /**
   * The result of running an effect against a concrete state — the shape both the
   * runtime interpreter and the testkit reduce to, so they cannot disagree about
   * semantics.
   */
  final case class Materialised[S, E, R](
      events: Vector[E],
      newState: S,
      retention: Option[Retention],
      reply: Either[CommandError, Option[R]]
  ):
    def persisted: Boolean            = events.nonEmpty
    def replyOrThrow: Option[R]       = reply.fold(throw _, identity)

  /**
   * Folds `events` over `current` and then resolves the outcome.
   *
   * `Fail` yields no events and the untouched state. The builder already makes a
   * failing-yet-persisting effect unconstructable — `error` returns a `ReadOnlyEffect`
   * and `PersistBuilder` cannot produce `Fail` — so this branch encodes that invariant
   * rather than enforcing it at runtime.
   */
  private[nakka] def materialise[S, E, R](
      effect: EventSourcedEffect[S, E, R],
      current: S,
      applyEvent: (S, E) => S
  ): Materialised[S, E, R] =
    effect.outcome match
      case Outcome.Fail(error) =>
        Materialised(Vector.empty, current, None, Left(error))
      case Outcome.NoReply =>
        val next = effect.events.foldLeft(current)(applyEvent)
        Materialised(effect.events, next, effect.retention, Right(None))
      case Outcome.Reply(compute, _) =>
        val next = effect.events.foldLeft(current)(applyEvent)
        Materialised(effect.events, next, effect.retention, Right(Some(compute(next))))

/**
 * The `effects` surface available inside an event sourced entity's command handlers.
 *
 * Stateless and allocation-light: one instance is shared per entity instance.
 */
final class EventSourcedEffects[S, E] private[nakka] ():
  import EventSourcedEffect.*

  /** Persist one or more events, then decide what to reply. */
  def persist(event: E, more: E*): PersistBuilder[S, E] =
    new PersistBuilder(event +: more.toVector, None)

  def persistAll(events: Seq[E]): PersistBuilder[S, E] =
    new PersistBuilder(events.toVector, None)

  /** Reply without persisting. Safe to serve from any region. */
  def reply[R](value: R): ReadOnlyEffect[S, E, R] =
    ReadOnly(Outcome.Reply(_ => value, Metadata.empty))

  def reply[R](value: R, metadata: Metadata): ReadOnlyEffect[S, E, R] =
    ReadOnly(Outcome.Reply(_ => value, metadata))

  /** Reject the command. Nothing is persisted and the caller sees a typed failure. */
  def error[R](message: String): ReadOnlyEffect[S, E, R] =
    ReadOnly(Outcome.Fail(CommandError(message)))

  def error[R](message: String, code: ErrorCode): ReadOnlyEffect[S, E, R] =
    ReadOnly(Outcome.Fail(CommandError(message, code)))

  def error[R](failure: CommandError): ReadOnlyEffect[S, E, R] =
    ReadOnly(Outcome.Fail(failure))

  /** Delete the entity without recording a final event. */
  def deleteEntity(): PersistBuilder[S, E] =
    new PersistBuilder(Vector.empty, Some(Retention.DeleteNow))

  def noReply[R]: ReadOnlyEffect[S, E, R] =
    ReadOnly(Outcome.NoReply)

/** Accumulates events and retention before the reply is chosen. */
final class PersistBuilder[S, E] private[nakka] (
    private val events: Vector[E],
    private val retention: Option[Retention]
):
  import EventSourcedEffect.*

  /** Persist these events, then delete the entity. */
  def deleteEntity(): PersistBuilder[S, E] =
    new PersistBuilder(events, Some(Retention.DeleteNow))

  /** Delete automatically after `duration` with no further update. */
  def expireAfter(duration: FiniteDuration): PersistBuilder[S, E] =
    new PersistBuilder(events, Some(Retention.ExpireAfter(duration)))

  /** Reply with a value derived from the state *after* the events are applied. */
  def thenReply[R](compute: S => R): EventSourcedEffect[S, E, R] =
    Persisting(events, retention, Outcome.Reply(compute, Metadata.empty))

  def thenReply[R](compute: S => R, metadata: Metadata): EventSourcedEffect[S, E, R] =
    Persisting(events, retention, Outcome.Reply(compute, metadata))

  /** Reply with the post-event state itself. */
  def thenReplyState: EventSourcedEffect[S, E, S] =
    Persisting(events, retention, Outcome.Reply(identity, Metadata.empty))

  def thenNoReply[R]: EventSourcedEffect[S, E, R] =
    Persisting(events, retention, Outcome.NoReply)
