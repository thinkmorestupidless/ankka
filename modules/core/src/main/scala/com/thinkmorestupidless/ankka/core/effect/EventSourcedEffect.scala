package com.thinkmorestupidless.ankka.core.effect

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Metadata}

import scala.concurrent.duration.FiniteDuration

/**
 * A *description* of what the runtime should do for one command against an event sourced entity.
 * Building one performs no I/O and touches no state, which is what lets unit tests assert on
 * handler behaviour with no runtime, no database and no cluster.
 *
 * State can only ever change by persisting an event — there is deliberately no `updateState` here,
 * mirroring the guarantee Akka's event sourced entities make.
 */
sealed trait EventSourcedEffect[S, E, +R]:
  private[ankka] def events: Vector[E]
  private[ankka] def retention: Option[Retention]
  private[ankka] def outcome: Outcome[S, R]
  private[ankka] def replication: Option[ReplicationChange] = None

/**
 * What a replicated entity's handler asks of its home beside the events it persists (feature 051):
 * a handover to another installation, or a change to the replication set. Written by the home after
 * the events, as a record in the entity's own journal.
 */
enum ReplicationChange:
  /** Hand the home to `to`, once it holds everything before the handover. */
  case HandOver(to: String)

  /** Add `add` to the replication set and take `remove` out of it, as one record. */
  case SetChange(add: Set[String], remove: Set[String])

/**
 * An effect that provably persists nothing, so the runtime may serve it from a replica without
 * routing to the entity's write region.
 */
sealed trait ReadOnlyEffect[S, E, +R] extends EventSourcedEffect[S, E, R]:
  private[ankka] final def events: Vector[E]            = Vector.empty
  private[ankka] final def retention: Option[Retention] = None

object EventSourcedEffect:

  private[ankka] final case class Persisting[S, E, R](
      events: Vector[E],
      retention: Option[Retention],
      outcome: Outcome[S, R],
      override val replication: Option[ReplicationChange] = None
  ) extends EventSourcedEffect[S, E, R]

  private[ankka] final case class ReadOnly[S, E, R](
      outcome: Outcome[S, R]
  ) extends ReadOnlyEffect[S, E, R]

  /**
   * The result of running an effect against a concrete state — the shape both the runtime
   * interpreter and the testkit reduce to, so they cannot disagree about semantics.
   */
  final case class Materialised[S, E, R](
      events: Vector[E],
      newState: S,
      retention: Option[Retention],
      reply: Either[CommandError, Option[R]],
      replication: Option[ReplicationChange] = None
  ):
    def persisted: Boolean      = events.nonEmpty
    def replyOrThrow: Option[R] = reply.fold(throw _, identity)

  /**
   * Folds `events` over `current` and then resolves the outcome.
   *
   * `Fail` yields no events and the untouched state. The builder already makes a
   * failing-yet-persisting effect unconstructable — `error` returns a `ReadOnlyEffect` and
   * `PersistBuilder` cannot produce `Fail` — so this branch encodes that invariant rather than
   * enforcing it at runtime.
   */
  private[ankka] def materialise[S, E, R](
      effect: EventSourcedEffect[S, E, R],
      current: S,
      applyEvent: (S, E) => S
  ): Materialised[S, E, R] =
    effect.outcome match
      case Outcome.Fail(error) =>
        Materialised(Vector.empty, current, None, Left(error))
      case Outcome.NoReply =>
        val next = effect.events.foldLeft(current)(applyEvent)
        Materialised(effect.events, next, effect.retention, Right(None), effect.replication)
      case Outcome.Reply(compute, _) =>
        val next = effect.events.foldLeft(current)(applyEvent)
        Materialised(
          effect.events,
          next,
          effect.retention,
          Right(Some(compute(next))),
          effect.replication
        )

/**
 * The `effects` surface available inside an event sourced entity's command handlers.
 *
 * Stateless and allocation-light: one instance is shared per entity instance.
 */
final class EventSourcedEffects[S, E] private[ankka] ():
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

  /**
   * Hand a replicated entity's home to `installation`, with no event of its own. The handover is
   * recorded once the new home holds everything before it; until then this home goes on as home.
   */
  def handOverTo(installation: String): PersistBuilder[S, E] =
    new PersistBuilder[S, E](Vector.empty, None).handOverTo(installation)

  /** Add `installation` to a replicated entity's replication set, with no event of its own. */
  def replicateTo(installation: String): PersistBuilder[S, E] =
    new PersistBuilder[S, E](Vector.empty, None).replicateTo(installation)

  /** Take `installation` out of a replicated entity's replication set; it keeps its copy. */
  def stopReplicatingTo(installation: String): PersistBuilder[S, E] =
    new PersistBuilder[S, E](Vector.empty, None).stopReplicatingTo(installation)

/** Accumulates events and retention before the reply is chosen. */
final class PersistBuilder[S, E] private[ankka] (
    private val events: Vector[E],
    private val retention: Option[Retention],
    private val replication: Option[ReplicationChange] = None
):
  import EventSourcedEffect.*

  /** Persist these events, then delete the entity. */
  def deleteEntity(): PersistBuilder[S, E] =
    new PersistBuilder(events, Some(Retention.DeleteNow), replication)

  /** Delete automatically after `duration` with no further update. */
  def expireAfter(duration: FiniteDuration): PersistBuilder[S, E] =
    new PersistBuilder(events, Some(Retention.ExpireAfter(duration)), replication)

  /**
   * Persist these events, then hand a replicated entity's home to `installation`, which must be in
   * its replication set. The handover is recorded once the new home holds everything before it.
   * Cannot be combined with a change to the replication set on one command.
   */
  def handOverTo(installation: String): PersistBuilder[S, E] =
    replication match
      case None =>
        new PersistBuilder(events, retention, Some(ReplicationChange.HandOver(installation)))
      case Some(other) => throw PersistBuilder.twoChanges(other)

  /**
   * Persist these events, then add `installation` to a replicated entity's replication set. Several
   * on one command are recorded together.
   */
  def replicateTo(installation: String): PersistBuilder[S, E] =
    setChange(add = Set(installation), remove = Set.empty)

  /**
   * Persist these events, then take `installation` out of a replicated entity's replication set. It
   * stops mirroring and keeps its copy, which no query reads again.
   */
  def stopReplicatingTo(installation: String): PersistBuilder[S, E] =
    setChange(add = Set.empty, remove = Set(installation))

  private def setChange(add: Set[String], remove: Set[String]): PersistBuilder[S, E] =
    val change = replication match
      case None => ReplicationChange.SetChange(add, remove)
      case Some(ReplicationChange.SetChange(a, r)) =>
        val both = (a ++ add).intersect(r ++ remove)
        if both.nonEmpty then
          throw IllegalArgumentException(
            s"a command both adds and removes ${both.mkString(", ")} in the replication set"
          )
        ReplicationChange.SetChange(a ++ add, r ++ remove)
      case Some(other) => throw PersistBuilder.twoChanges(other)
    new PersistBuilder(events, retention, Some(change))

  /** Reply with a value derived from the state *after* the events are applied. */
  def thenReply[R](compute: S => R): EventSourcedEffect[S, E, R] =
    Persisting(events, retention, Outcome.Reply(compute, Metadata.empty), replication)

  def thenReply[R](compute: S => R, metadata: Metadata): EventSourcedEffect[S, E, R] =
    Persisting(events, retention, Outcome.Reply(compute, metadata), replication)

  /** Reply with the post-event state itself. */
  def thenReplyState: EventSourcedEffect[S, E, S] =
    Persisting(events, retention, Outcome.Reply(identity, Metadata.empty), replication)

  def thenNoReply[R]: EventSourcedEffect[S, E, R] =
    Persisting(events, retention, Outcome.NoReply, replication)

private object PersistBuilder:
  def twoChanges(already: ReplicationChange): IllegalArgumentException =
    IllegalArgumentException(
      "a command asks for one handover or one change to the replication set; it already asked " +
        s"for $already"
    )
