package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{ComponentId, MethodName}
import com.thinkmorestupidless.ankka.runtime.{Database, FiredTimer, TimerObserver, TimerStore}
import com.thinkmorestupidless.ankka.sdk.DeferredCall
import org.apache.pekko.actor.typed.ActorSystem

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * What the timer runtime did, for a test to read: every run of every timer, and a timer as the
 * database holds it.
 *
 * {{{
 * val probe  = TimerProbe()
 * val timers = TimerRuntime(pollInterval = 100.millis, observer = probe)
 * val kit    = AnkkaTestKit.start(Seq(Cleanup.descriptor), Seq(timers))
 * probe.bind(kit)
 *
 * timers.timerScheduler.createRecurringTimer("sweep-carts", Duration.Zero, 2.seconds, Cleanup.sweep.deferred)
 * // … once it has run three times …
 * val due = probe.dueTimes("sweep-carts")
 * assertEquals(due(1), due(0).plusSeconds(2))
 * }}}
 *
 * A test of a cadence reads due times from here rather than from when its handler happened to be
 * called: a run starts up to a poll interval after its due, and the due is what the runtime
 * promises. The probe keeps its record across `restartService()`, so a due from before a restart
 * can be compared with one after it.
 */
final class TimerProbe extends TimerObserver:

  private val runs                                = ConcurrentLinkedQueue[FiredTimer]()
  @volatile private var kit: Option[AnkkaTestKit] = None

  /** Called by the sweeper after each run. */
  def fired(timer: FiredTimer): Unit = runs.add(timer): Unit

  /** Every run of the timer named `name`, oldest first. */
  def fired(name: String): Vector[FiredTimer] = runs.asScala.filter(_.name == name).toVector

  /** The due times `name` was run for and finished, one for each run that succeeded. */
  def dueTimes(name: String): Vector[Instant] =
    fired(name).filter(_.outcome == FiredTimer.Outcome.Done).map(_.dueTime)

  /** The service `scheduled` reads from. Answers this probe, for chaining. */
  def bind(kit: AnkkaTestKit): TimerProbe =
    this.kit = Some(kit)
    this

  /** The timer as the database holds it, or `None` when there is none. */
  def scheduled(name: String): Option[ScheduledTimer] =
    val service = kit.getOrElse(
      throw IllegalStateException("bind the probe to the test kit before reading a timer from it")
    )
    given ActorSystem[?] = service.service.system
    Await.result(
      Database().queryOne(TimerStore.scheduled(name)) { row =>
        val attempts = row.get("attempts", classOf[Integer]).intValue
        val period   = Option(row.get("period_millis", classOf[java.lang.Long])).map(_.longValue)
        val dueAt    = Option(row.get("due_at", classOf[Instant]))
        val dueFor   = Option(row.get("due_for", classOf[Instant]))
        // A recurring timer's next run is for `due_for`; one that fires once is for its `due_at`
        // until it fails, and then for the `due_for` its backoff kept.
        val due = (period, dueAt) match
          case (Some(_), _)                      => dueFor.get
          case (None, Some(at)) if attempts == 0 => at
          case (None, at)                        => dueFor.orElse(at).get
        ScheduledTimer(
          row.get("timer_name", classOf[String]),
          DeferredCall(
            ComponentId(row.get("component_id", classOf[String])),
            MethodName(row.get("method", classOf[String])),
            row.get("payload", classOf[Array[Byte]])
          ),
          due,
          period.map(_.millis),
          attempts
        )
      },
      10.seconds
    )

/**
 * A timer as the database holds it.
 *
 * @param dueTime
 *   the due the next run is for
 * @param period
 *   a recurring timer's period; `None` for one that fires once
 * @param attempts
 *   failures since it was set, or, for a recurring timer, since its last run that succeeded
 */
final case class ScheduledTimer(
    name: String,
    target: DeferredCall,
    dueTime: Instant,
    period: Option[FiniteDuration],
    attempts: Int
)
