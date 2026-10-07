package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Done, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** One registration of a blueprint: its number, its canonical text, the text's digest, and when. */
final case class VersionRecord(number: Int, canonical: String, digest: String, registeredAt: Long)

/**
 * A blueprint as the platform holds it: every version ever registered, and where its schedule
 * stands. The platform registers it; a service writes to it only through the blueprint calls.
 */
final case class BlueprintRecord(
    name: String,
    versions: Vector[VersionRecord] = Vector.empty,
    lastPeriodEnd: Option[Long] = None,
    nextDue: Option[Long] = None
):
  def exists: Boolean                        = versions.nonEmpty
  def current: Option[VersionRecord]         = versions.lastOption
  def version(n: Int): Option[VersionRecord] = versions.find(_.number == n)

object BlueprintRecord:
  val empty: BlueprintRecord = BlueprintRecord("")

enum BlueprintEvent:
  case VersionRegistered(number: Int, canonical: String, digest: String, registeredAt: Long)

  /**
   * Where the schedule stands: periods are covered up to `periodEnd`, and `nextDue` is the due time
   * pending, if the schedule goes on. A schedule's first record has the period end one cadence
   * before its first due time, and no run.
   */
  case ScheduleAdvanced(periodEnd: Long, nextDue: Option[Long])
  case ScheduleStopped(at: Long)

/**
 * The record of one blueprint, by name. A version is never changed: registering an equal blueprint
 * answers the current version, and a different one is the next number.
 */
final class BlueprintEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[BlueprintRecord, BlueprintEvent]:

  import BlueprintEntity.*
  import BlueprintEvent as E

  private val name = context.entityId

  def emptyState: BlueprintRecord = BlueprintRecord(name)

  def applyEvent(event: BlueprintEvent): BlueprintRecord =
    val s = currentState
    event match
      case E.VersionRegistered(number, canonical, digest, at) =>
        s.copy(name = name, versions = s.versions :+ VersionRecord(number, canonical, digest, at))
      case E.ScheduleAdvanced(periodEnd, nextDue) =>
        s.copy(lastPeriodEnd = Some(periodEnd), nextDue = nextDue)
      case E.ScheduleStopped(_) => s.copy(nextDue = None)

  /**
   * Checked by the caller: the entity holds what it is given and decides only whether it is new.
   */
  def register(request: Register): Effect[Accepted] =
    currentState.current match
      case Some(current) if current.digest == request.digest =>
        effects.reply(Accepted(current.number, isNew = false))
      case current =>
        val number = current.map(_.number).getOrElse(0) + 1
        effects
          .persist(E.VersionRegistered(number, request.canonical, request.digest, now()))
          .thenReply(_ => Accepted(number, isNew = true))

  def advanceSchedule(request: Advance): Effect[Done] =
    withBlueprint { s =>
      if s.lastPeriodEnd.exists(_ > request.periodEnd) then
        effects.error(
          s"blueprint '$name': a period ending at ${request.periodEnd} is before the last, ${s.lastPeriodEnd.get}",
          ErrorCode.Conflict
        )
      else
        effects.persist(E.ScheduleAdvanced(request.periodEnd, request.nextDue)).thenReply(_ => Done)
    }

  def stopSchedule: Effect[Done] =
    withBlueprint { s =>
      if s.nextDue.isEmpty then effects.reply(Done)
      else effects.persist(E.ScheduleStopped(now())).thenReply(_ => Done)
    }

  def get: ReadOnlyEffect[BlueprintRecord] =
    if currentState.exists then effects.reply(currentState)
    else effects.error(s"no blueprint '$name'", ErrorCode.NotFound)

  private def withBlueprint[R](f: BlueprintRecord => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState)
    else effects.error(s"no blueprint '$name'", ErrorCode.NotFound)

  // A command handler may read the clock; the fold may not, which is why the time is in the event.
  private def now(): Long = System.currentTimeMillis()

object BlueprintEntity
    extends EventSourcedEntity.Companion[BlueprintEntity, BlueprintRecord, BlueprintEvent](
      componentId = ComponentId("ankka-blueprint"),
      stateSerializer = Codecs.serializer[BlueprintRecord]("blueprint-record"),
      eventSerializer = Codecs.serializer[BlueprintEvent]("blueprint-event")
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  final case class Register(canonical: String, digest: String)
  final case class Accepted(number: Int, isNew: Boolean)
  final case class Advance(periodEnd: Long, nextDue: Option[Long])

  given Serializer[Register] = Codecs.serializer[Register]("blueprint-register")
  given Serializer[Accepted] = Codecs.serializer[Accepted]("blueprint-accepted")
  given Serializer[Advance]  = Codecs.serializer[Advance]("blueprint-advance")

  def create(context: EventSourcedEntityContext) = new BlueprintEntity(context)

  val register        = command("register")(_.register)
  val advanceSchedule = command("advance-schedule")(_.advanceSchedule)
  val stopSchedule    = command("stop-schedule")(_.stopSchedule)
  val get             = query("get")(_.get)
