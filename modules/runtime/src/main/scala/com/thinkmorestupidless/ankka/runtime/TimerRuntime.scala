package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.Await

/**
 * Runs scheduled calls.
 *
 * Timers live in Postgres rather than in an actor's memory, because the whole promise of a timer is
 * that it outlives the process that set it. A cluster singleton polls for due calls and runs them;
 * a singleton rather than sharded slices because timers are capped in the hundreds of thousands,
 * and one poller is far easier to reason about than N pollers racing for the same rows.
 */
final class TimerRuntime private (pollInterval: FiniteDuration) extends RuntimeExtension:

  @volatile private var scheduler: Option[TimerScheduler] = None

  def name: String = "timers"

  /** Available once the service has started; inject into endpoints and workflows. */
  def timerScheduler: TimerScheduler =
    scheduler.getOrElse(
      throw IllegalStateException("the timer runtime has not started yet")
    )

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system

    val actions  = service.registry.components.collect { case a: TimedActionDescriptor[?] => a }
    val database = Database()

    scheduler = Some(new DatabaseTimerScheduler(database))

    if actions.isEmpty then
      system.log.debug("no timed actions registered; timers can still be scheduled and cancelled")
    else
      val byId = actions.map(a => a.componentId -> a).toMap
      val _ = ClusterSingleton(system).init(
        SingletonActor(
          TimerSweeper(database, byId, service.componentClient, pollInterval),
          "ankka-timer-sweeper"
        )
      )
      system.log.info(
        "timer sweeper started for {} ({} poll interval)",
        actions.map(_.componentId).mkString(", "),
        pollInterval
      )

object TimerRuntime:
  /** Polls once a second, which bounds a timer's lateness rather than its accuracy. */
  def apply(pollInterval: FiniteDuration = 1.second): TimerRuntime =
    new TimerRuntime(pollInterval)

  /** The documented ceiling on a timer payload. */
  val MaxPayloadBytes: Int = 1024

/** The `TimerScheduler` the runtime hands to components. */
private[ankka] final class DatabaseTimerScheduler(database: Database) extends TimerScheduler:

  private val timeout = 10.seconds

  def createSingleTimer(name: String, delay: FiniteDuration, call: DeferredCall): Unit =
    if name.isEmpty then throw IllegalArgumentException("a timer needs a name")
    else if call.payload.length > TimerRuntime.MaxPayloadBytes then
      // A timer is a reminder, not a queue. Storing arbitrarily large payloads here
      // would quietly turn the timer table into one.
      throw IllegalArgumentException(
        s"timer '$name' payload is ${call.payload.length} bytes, over the " +
          s"${TimerRuntime.MaxPayloadBytes} byte limit; store the data and schedule its id"
      )
    else
      val dueAt = Instant.now().plusMillis(delay.toMillis)
      Await.result(
        database.execute(TimerStore.upsert(name, call, dueAt)),
        timeout
      ): Unit

  def delete(name: String): Unit =
    Await.result(database.execute(TimerStore.delete(name)), timeout): Unit

  def exists(name: String): Boolean =
    Await
      .result(database.query(TimerStore.byName(name))(_.get(0, classOf[String])), timeout)
      .nonEmpty

/** SQL for the timer table. */
private[ankka] object TimerStore:

  def upsert(name: String, call: DeferredCall, dueAt: Instant): SqlFragment =
    SqlFragment.raw(
      "INSERT INTO ankka_timers (timer_name, component_id, method, payload, due_at, attempts) VALUES ("
    ) ++
      sql"$name, ${call.componentId: String}, ${call.method: String}, ${call.payload}, $dueAt, ${0}" ++
      SqlFragment.raw(
        ") ON CONFLICT (timer_name) DO UPDATE SET " +
          "component_id = EXCLUDED.component_id, method = EXCLUDED.method, " +
          "payload = EXCLUDED.payload, due_at = EXCLUDED.due_at, attempts = 0"
      )

  def delete(name: String): SqlFragment =
    SqlFragment.raw("DELETE FROM ankka_timers WHERE timer_name = ") ++ sql"$name"

  def byName(name: String): SqlFragment =
    SqlFragment.raw("SELECT timer_name FROM ankka_timers WHERE timer_name = ") ++ sql"$name"

  /** Due timers, oldest first, capped so one slow batch cannot starve the rest. */
  def due(now: Instant, limit: Int): SqlFragment =
    SqlFragment.raw(
      "SELECT timer_name, component_id, method, payload, attempts FROM ankka_timers WHERE due_at <= "
    ) ++ sql"$now" ++ SqlFragment.raw(s" ORDER BY due_at LIMIT $limit")

  /**
   * Pushes a failed timer out with exponential backoff, 3s doubling to a 30s ceiling — the range
   * Akka documents, so a permanently failing timer costs two attempts a minute rather than
   * saturating the poller.
   */
  def reschedule(name: String, attempts: Int): SqlFragment =
    val backoffSeconds = math.min(3L * (1L << math.min(attempts, 4)), 30L)
    val nextDue        = Instant.now().plusSeconds(backoffSeconds)
    SqlFragment.raw("UPDATE ankka_timers SET attempts = ") ++
      sql"${attempts + 1}" ++ SqlFragment.raw(", due_at = ") ++ sql"$nextDue" ++
      SqlFragment.raw(" WHERE timer_name = ") ++ sql"$name"
