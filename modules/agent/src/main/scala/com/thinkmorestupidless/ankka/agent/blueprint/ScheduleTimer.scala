package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.core.{
  Codecs,
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Serializer
}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.{Clock, Instant}
import scala.concurrent.duration.DurationLong

/**
 * Starts a blueprint's scheduled runs. Each due time is a timer of its own, named for it, swept by
 * the service's timers; when one fires, the handler reads the blueprint's current version, finds
 * every due time passed since the last period ended (an outage may have passed several), starts the
 * run or runs the schedule asks for, moves the schedule on and sets the next timer. Run ids and
 * timer names are the due time's, so a firing repeated starts nothing twice, and the sweeper being
 * one per cluster makes one run per due time however many instances run.
 */
final class ScheduleTimer(context: TimedActionContext) extends TimedAction:

  import ScheduleTimer.*

  def fire(due: Due): Effect =
    val client = context.componentClient
    val entity = client.forEventSourcedEntity(EntityId(due.blueprint))
    val record =
      try Some(entity.call(BlueprintEntity.get).invoke())
      catch case e: CommandError if e.code == ErrorCode.NotFound => None
    record.flatMap(r => r.current.map(v => (r, v))) match
      case None => effects.done()
      case Some((record, version)) =>
        Blueprint.fromJson(version.canonical).toOption.flatMap(bp => bp.schedule.map(bp -> _)) match
          case None =>
            // The current version has no schedule: whatever set this timer, it is over.
            entity.call(BlueprintEntity.stopSchedule).invoke(): Unit
            effects.done()
          case Some((blueprint, schedule)) =>
            record.nextDue match
              case Some(pending) if pending != due.at =>
                // The schedule has moved past this occurrence, by a new version or a firing that
                // advanced it and stopped before setting the next timer; make sure that one is set.
                ensure(context.timers, context.clock, due.blueprint, Instant.ofEpochMilli(pending))
                effects.done()
              case _ =>
                val dueAt = Instant.ofEpochMilli(due.at)
                val lastEnd =
                  record.lastPeriodEnd.map(Instant.ofEpochMilli).getOrElse(schedule.previous(dueAt))
                val now = context.clock.instant()
                val missed = schedule.dueTimes(
                  after = lastEnd,
                  upTo = if now.isAfter(dueAt) then now else dueAt
                )
                if missed.isEmpty then
                  // Advanced already past this due time; only the next timer could be missing.
                  ensure(context.timers, context.clock, due.blueprint, schedule.next(lastEnd))
                  effects.done()
                else
                  schedule.periods(lastEnd, missed).foreach { period =>
                    RunCalls.startHeld(
                      client,
                      blueprint,
                      version.number,
                      period.json,
                      runId(due.blueprint, period.to),
                      StartedBy
                    ): Unit
                  }
                  val nextDue = schedule.next(missed.last)
                  try
                    entity
                      .call(BlueprintEntity.advanceSchedule)
                      .invoke(
                        BlueprintEntity
                          .Advance(missed.last.toEpochMilli, Some(nextDue.toEpochMilli))
                      ): Unit
                  catch case e: CommandError if e.code == ErrorCode.Conflict => ()
                  ensure(context.timers, context.clock, due.blueprint, nextDue)
                  effects.done()

object ScheduleTimer
    extends TimedAction.Companion[ScheduleTimer](ComponentId("ankka-blueprint-schedule")):

  /** Which blueprint's schedule is due, and when, as epoch millis. */
  final case class Due(blueprint: String, at: Long)

  given Serializer[Due] = Codecs.serializer[Due]("blueprint-schedule-due")

  /** Who a scheduled run says started it. */
  val StartedBy: String = "schedule"

  def create(context: TimedActionContext) = new ScheduleTimer(context)

  val fire = handler("fire")(_.fire)

  /** The platform's own: no service wrote it, and the console marks it so. */
  def platformDescriptor: TimedActionDescriptor[ScheduleTimer] = descriptor.copy(platform = true)

  /** One timer per occurrence, so the sweeper's forgetting a fired timer forgets only that one. */
  def timerName(blueprint: String, due: Instant): String =
    s"schedule:$blueprint:${due.toEpochMilli}"

  /** The run a due time starts; the same whichever instance, or firing, starts it. */
  def runId(blueprint: String, due: Instant): String =
    s"${RunEntity.SchedulePrefix}$blueprint:${due.toEpochMilli}"

  /** Sets the occurrence's timer, when it is not set already; one due already is due at once. */
  private[blueprint] def ensure(
      timers: TimerScheduler,
      clock: Clock,
      blueprint: String,
      due: Instant
  ): Unit =
    val name = timerName(blueprint, due)
    if !timers.exists(name) then
      val delay = (due.toEpochMilli - clock.instant().toEpochMilli).max(0L).millis
      timers.createSingleTimer(name, delay, fire.deferred(Due(blueprint, due.toEpochMilli)))

  /**
   * What registering a version does to the schedule. A version with one sets the next due time's
   * timer when none is pending, the first period starting one cadence before it or where the last
   * period ended; a pending occurrence that has passed is left to fire, since its handler reads the
   * version current then. A version without one deletes the pending timer and stops the schedule.
   * Without timers nothing is done: the check refused the schedule already.
   */
  private[blueprint] def onRegistered(
      client: ComponentClient,
      timers: Option[TimerScheduler],
      clock: Clock,
      blueprint: Blueprint
  ): Unit =
    val entity = client.forEventSourcedEntity(EntityId(blueprint.name))
    val record = entity.call(BlueprintEntity.get).invoke()
    (blueprint.schedule, timers) match
      case (None, _) =>
        record.nextDue.foreach { pending =>
          timers.foreach(_.delete(timerName(blueprint.name, Instant.ofEpochMilli(pending))))
          entity.call(BlueprintEntity.stopSchedule).invoke(): Unit
        }
      case (Some(_), None) => ()
      case (Some(schedule), Some(t)) =>
        val now    = clock.instant()
        val wanted = schedule.next(now)
        record.nextDue match
          case Some(pending) if pending <= now.toEpochMilli || pending == wanted.toEpochMilli =>
            ensure(t, clock, blueprint.name, Instant.ofEpochMilli(pending))
          case other =>
            other.foreach(pending =>
              t.delete(timerName(blueprint.name, Instant.ofEpochMilli(pending)))
            )
            val periodEnd = record.lastPeriodEnd.getOrElse(schedule.previous(wanted).toEpochMilli)
            entity
              .call(BlueprintEntity.advanceSchedule)
              .invoke(BlueprintEntity.Advance(periodEnd, Some(wanted.toEpochMilli))): Unit
            ensure(t, clock, blueprint.name, wanted)
