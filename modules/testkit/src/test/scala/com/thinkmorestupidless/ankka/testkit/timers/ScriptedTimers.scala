package com.thinkmorestupidless.ankka.testkit.timers

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * What a handler of a [[ScriptedTimerKit]] does when its timer fires, as a scenario says it.
 *
 * A behaviour is chosen per handler before anything fires, and a handler with none is done.
 */
enum Behaviour:
  case Done
  case Fails
  case FailsThenDone(times: Int)
  case Takes(duration: FiniteDuration)

  /** Sets `timer` for this handler again, `delay` from now, with the input it was given. */
  case SetsAgain(timer: String, delay: FiniteDuration)
  case Cancels(timer: String)

  /** The first time it runs, sets `timer` for `target` to recur every `period`; done every time. */
  case SetsRecurringOnce(timer: String, target: String, period: FiniteDuration)

/**
 * One run of a handler, as the handler saw it.
 *
 * `setBefore` and `setAfter` bracket the handler's own call to the scheduler, for a behaviour that
 * makes one: whatever due time the scheduler gave, it gave between the two.
 */
final case class Run(
    handler: String,
    input: String,
    timerName: String,
    previousAttempts: Int,
    dueTime: Instant,
    startedAt: Instant,
    finishedAt: Instant,
    failed: Boolean,
    setBefore: Option[Instant] = None,
    setAfter: Option[Instant] = None
)

/** The timed action a scenario's handlers belong to. Stateless: the kit holds the script. */
final class ScriptedTimers(context: TimedActionContext, kit: ScriptedTimerKit) extends TimedAction:

  def run(handler: String)(input: String): Effect =
    val startedAt = Instant.now()
    val ranBefore = kit.runs(handler).size
    def record(failed: Boolean, set: Option[(Instant, Instant)] = None): Unit =
      kit.recorded.add(
        Run(
          handler,
          input,
          context.timerName,
          context.previousAttempts,
          context.dueTime,
          startedAt,
          Instant.now(),
          failed,
          set.map(_._1),
          set.map(_._2)
        )
      ): Unit

    kit.behaviour(handler) match
      case Behaviour.Done =>
        record(failed = false)
        effects.done()

      case Behaviour.Fails =>
        record(failed = true)
        effects.error(s"$handler fails, as its scenario says")

      case Behaviour.FailsThenDone(times) =>
        if ranBefore < times then
          record(failed = true)
          effects.error(s"$handler fails ${times} time(s), as its scenario says")
        else
          record(failed = false)
          effects.done()

      case Behaviour.Takes(duration) =>
        Thread.sleep(duration.toMillis)
        record(failed = false)
        effects.done()

      case Behaviour.SetsAgain(timer, delay) =>
        val before = Instant.now()
        kit.scheduler().createSingleTimer(timer, delay, kit.handle(handler).deferred(input))
        record(failed = false, Some(before -> Instant.now()))
        effects.done()

      case Behaviour.Cancels(timer) =>
        kit.scheduler().delete(timer)
        record(failed = false)
        effects.done()

      case Behaviour.SetsRecurringOnce(timer, target, period) =>
        if ranBefore == 0 then
          kit
            .scheduler()
            .createRecurringTimer(
              timer,
              scala.concurrent.duration.Duration.Zero,
              period,
              kit.handle(target).deferred(timer)
            )
        record(failed = false)
        effects.done()

/**
 * A timed action whose handlers do what a scenario says: every handler the timer features name,
 * each taking the timer's input as a string.
 *
 * The scheduler reaches the handlers through `scheduler`, a function because the timer runtime that
 * owns it starts after the kit is built.
 */
final class ScriptedTimerKit(id: String, val scheduler: () => TimerScheduler)
    extends TimedAction.Companion[ScriptedTimers](ComponentId(id)):

  def create(context: TimedActionContext) = new ScriptedTimers(context, this)

  private val handles: Map[String, TimedActionHandle[ScriptedTimers, String]] =
    ScriptedTimerKit.Handlers.map(name => name -> handler[String](name)(_.run(name))).toMap

  @volatile private var script = Map.empty[String, Behaviour]

  private[timers] val recorded = ConcurrentLinkedQueue[Run]()

  def handle(name: String): TimedActionHandle[ScriptedTimers, String] =
    handles.getOrElse(name, throw IllegalArgumentException(s"no handler '$name' on '$id'"))

  def declares(name: String): Boolean = handles.contains(name)

  def script(handler: String, behaviour: Behaviour): Unit =
    script = script.updated(handler, behaviour)

  def behaviour(handler: String): Behaviour = script.getOrElse(handler, Behaviour.Done)

  /** Every run, oldest first. */
  def runs: Vector[Run] = recorded.asScala.toVector

  def runs(handler: String): Vector[Run] = runs.filter(_.handler == handler)

  def runsOf(timer: String): Vector[Run] = runs.filter(_.timerName == timer)

object ScriptedTimerKit:
  /** The handlers the features name, on whichever timed action they belong to. */
  val Handlers: Vector[String] = Vector("nudge", "sweep", "sweep-once", "sweep-all", "sweep-deep")
