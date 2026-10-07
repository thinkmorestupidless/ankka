package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}

import io.r2dbc.spi.R2dbcException

import java.time.Instant
import java.time.temporal.ChronoUnit
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
final class TimerRuntime private (pollInterval: FiniteDuration, observer: TimerObserver)
    extends RuntimeExtension:

  @volatile private var scheduler: Option[TimerScheduler] = None

  def name: String = "timers"

  /** Available once the service has started; inject into endpoints and workflows. */
  def timerScheduler: TimerScheduler =
    scheduler.getOrElse(
      throw IllegalStateException("the timer runtime has not started yet")
    )

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system

    val actions = service.registry.components.collect {
      case a: TimedActionDescriptor[?]           => a
      case r: remote.RemoteTimedActionDescriptor => r
    }
    val database = Database()

    scheduler = Some(new DatabaseTimerScheduler(database))

    if actions.isEmpty then
      system.log.debug("no timed actions registered; timers can still be scheduled and cancelled")
    else
      val byId = actions.map(a => a.componentId -> a).toMap
      val _ = ClusterSingleton(system).init(
        SingletonActor(
          TimerSweeper(
            database,
            byId,
            service.componentClient,
            service.secrets,
            service.services,
            service.conversation,
            pollInterval,
            observer
          ),
          "ankka-timer-sweeper"
        )
      )
      system.log.info(
        "timer sweeper started for {} ({} poll interval)",
        actions.map(_.componentId).mkString(", "),
        pollInterval
      )

object TimerRuntime:
  /**
   * Polls once a second, which bounds a timer's lateness rather than its accuracy. `observer` is
   * told what the sweeper did with each timer; a service has none, and a test a probe.
   */
  def apply(
      pollInterval: FiniteDuration = 1.second,
      observer: TimerObserver = TimerObserver.none
  ): TimerRuntime =
    new TimerRuntime(pollInterval, observer)

  /** The documented ceiling on a timer payload. */
  val MaxPayloadBytes: Int = 1024

  /**
   * What a database whose timer table predates recurring timers is told: a local database created
   * before them keeps its tables, because the schema is applied only when its volume is new.
   */
  private val oldTableWarned = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Said once per process: a table from before recurring timers still runs timers that fire once,
   * so this is a warning and not a failure, and repeating it every poll would bury the rest.
   */
  private[ankka] def warnOldTable(remedy: String): Unit =
    if oldTableWarned.compareAndSet(false, true) then
      org.slf4j.LoggerFactory
        .getLogger("ankka.timers")
        .warn(
          s"$remedy. Timers that fire once go on working; a recurring timer is refused until then"
        )

  private[ankka] def schemaTooOld(cause: Throwable): CommandError =
    val error = CommandError(
      "this service's database has an ankka_timers table without the columns recurring timers " +
        "need; apply 30-timers-postgres.sql from the platform's schema, or recreate a local " +
        "database created before recurring timers",
      ErrorCode.Internal
    )
    error.initCause(cause): Unit
    error

/** The `TimerScheduler` the runtime hands to components. */
private[ankka] final class DatabaseTimerScheduler(database: Database) extends TimerScheduler:

  private val timeout = 10.seconds

  def createSingleTimer(name: String, delay: FiniteDuration, call: DeferredCall): Unit =
    check(name, call)
    val dueAt = due(delay)
    try Await.result(database.execute(TimerStore.upsert(name, call, dueAt)), timeout): Unit
    catch
      case e: R2dbcException if e.getSqlState == "42703" =>
        // A table from before recurring timers: a timer that fires once needs none of their columns.
        TimerRuntime.warnOldTable(TimerRuntime.schemaTooOld(e).message)
        run(TimerStore.legacyUpsert(name, call, dueAt)): Unit

  def createRecurringTimer(
      name: String,
      delay: FiniteDuration,
      period: FiniteDuration,
      call: DeferredCall
  ): Unit =
    val periodMillis =
      TimerRules.period(name, period).fold(m => throw IllegalArgumentException(m), identity)
    check(name, call)
    run(TimerStore.upsertRecurring(name, call, periodMillis, due(delay))): Unit

  def delete(name: String): Unit =
    run(TimerStore.delete(name)): Unit

  def exists(name: String): Boolean =
    Await
      .result(database.query(TimerStore.byName(name))(_.get(0, classOf[String])), timeout)
      .nonEmpty

  private def check(name: String, call: DeferredCall): Unit =
    if name.isEmpty then throw IllegalArgumentException("a timer needs a name")
    else if call.payload.length > TimerRuntime.MaxPayloadBytes then
      // A timer is a reminder, not a queue. Storing arbitrarily large payloads here
      // would quietly turn the timer table into one.
      throw IllegalArgumentException(
        s"timer '$name' payload is ${call.payload.length} bytes, over the " +
          s"${TimerRuntime.MaxPayloadBytes} byte limit; store the data and schedule its id"
      )

  /**
   * A due time, in whole milliseconds: the period and every wire value are milliseconds, so a due a
   * handler is told is the due stored, in every language. A delay of zero or less is due at once.
   */
  private def due(delay: FiniteDuration): Instant =
    Instant.now().truncatedTo(ChronoUnit.MILLIS).plusMillis(delay.toMillis.max(0L))

  private def run(statement: SqlFragment): Long =
    try Await.result(database.execute(statement), timeout)
    catch case e: R2dbcException if e.getSqlState == "42703" => throw TimerRuntime.schemaTooOld(e)

/**
 * SQL for the timer table.
 *
 * A timer that fires once has a finite `due_at`; a recurring timer has `due_at = 'infinity'`, its
 * next run in `fire_at` and its period in `period_millis` (`30-timers-postgres.sql` says why). Both
 * keep the due the next run is for in `due_for`. `due_at` is never selected for a recurring row, so
 * the driver is never asked to decode `'infinity'`.
 *
 * Every statement the sweeper runs after a handler returns is matched on what it read, so it is a
 * no-op on a row the handler, a cancel or another sweeper changed in the meantime.
 */
private[ankka] object TimerStore:

  private val Infinity = "'infinity'::timestamptz"

  /** Schedules a timer that fires once, replacing whatever is under the name. */
  def upsert(name: String, call: DeferredCall, dueAt: Instant): SqlFragment =
    SqlFragment.raw(
      "INSERT INTO ankka_timers " +
        "(timer_name, component_id, method, payload, due_at, attempts, period_millis, fire_at, due_for) " +
        "VALUES ("
    ) ++
      sql"$name, ${call.componentId: String}, ${call.method: String}, ${call.payload}, $dueAt, ${0}" ++
      SqlFragment.raw(", NULL, NULL, ") ++ sql"$dueAt" ++
      SqlFragment.raw(
        ") ON CONFLICT (timer_name) DO UPDATE SET " +
          "component_id = EXCLUDED.component_id, method = EXCLUDED.method, " +
          "payload = EXCLUDED.payload, due_at = EXCLUDED.due_at, attempts = 0, " +
          "period_millis = NULL, fire_at = NULL, due_for = EXCLUDED.due_for"
      )

  /**
   * Schedules a recurring timer, first due at `first`.
   *
   * When the name already holds a recurring timer for the same component and handler with the same
   * period, its next run, the due that run is for and its attempt count are kept and only the
   * payload is taken: setting the same recurring timer again changes nothing about when it fires,
   * so a service may set its recurring timers every time it starts. Anything else is replaced. One
   * statement, so two instances setting the same timer at once cannot reset each other.
   */
  def upsertRecurring(
      name: String,
      call: DeferredCall,
      periodMillis: Long,
      first: Instant
  ): SqlFragment =
    val same =
      s"t.due_at = $Infinity AND t.period_millis = EXCLUDED.period_millis " +
        "AND t.component_id = EXCLUDED.component_id AND t.method = EXCLUDED.method"
    SqlFragment.raw(
      "INSERT INTO ankka_timers AS t " +
        "(timer_name, component_id, method, payload, due_at, attempts, period_millis, fire_at, due_for) " +
        "VALUES ("
    ) ++
      sql"$name, ${call.componentId: String}, ${call.method: String}, ${call.payload}" ++
      SqlFragment.raw(s", $Infinity, 0, ") ++ sql"$periodMillis, $first, $first" ++
      SqlFragment.raw(
        ") ON CONFLICT (timer_name) DO UPDATE SET " +
          "component_id = EXCLUDED.component_id, method = EXCLUDED.method, " +
          s"payload = EXCLUDED.payload, period_millis = EXCLUDED.period_millis, due_at = $Infinity, " +
          s"fire_at = CASE WHEN $same THEN t.fire_at ELSE EXCLUDED.fire_at END, " +
          s"due_for = CASE WHEN $same THEN t.due_for ELSE EXCLUDED.due_for END, " +
          s"attempts = CASE WHEN $same THEN t.attempts ELSE 0 END"
      )

  /** Cancels a timer by name: whatever is scheduled under it, which is what the caller means. */
  def delete(name: String): SqlFragment =
    SqlFragment.raw("DELETE FROM ankka_timers WHERE timer_name = ") ++ sql"$name"

  /**
   * Removes the timer the sweeper ran, and only that one.
   *
   * Matched on the due time the sweeper read as well as the name: a handler that sets its own timer
   * again rewrites the same row before it returns, and deleting by name alone would delete what it
   * set. The value matched is the one read back from the table, so the comparison is exact.
   */
  def deleteRan(name: String, dueAt: Instant): SqlFragment =
    SqlFragment.raw("DELETE FROM ankka_timers WHERE timer_name = ") ++ sql"$name" ++
      SqlFragment.raw(" AND due_at = ") ++ sql"$dueAt"

  def byName(name: String): SqlFragment =
    SqlFragment.raw("SELECT timer_name FROM ankka_timers WHERE timer_name = ") ++ sql"$name"

  /** A timer as the table holds it, for a test to read. */
  def scheduled(name: String): SqlFragment =
    SqlFragment.raw(
      "SELECT timer_name, component_id, method, payload, attempts, period_millis, due_for, " +
        s"CASE WHEN due_at = $Infinity THEN NULL ELSE due_at END AS due_at " +
        "FROM ankka_timers WHERE timer_name = "
    ) ++ sql"$name"

  /**
   * Due timers, oldest first, capped so one slow batch cannot starve the rest: those that fire once
   * whose `due_at` has passed, and recurring ones whose `fire_at` has. Each arm reads its own
   * index.
   */
  def due(now: Instant, limit: Int): SqlFragment =
    val columns = "timer_name, component_id, method, payload, attempts, period_millis, due_for"
    SqlFragment.raw(
      s"(SELECT $columns, due_at, due_at AS run_at FROM ankka_timers WHERE due_at <= "
    ) ++ sql"$now" ++ SqlFragment.raw(
      s" ORDER BY due_at LIMIT $limit) UNION ALL " +
        s"(SELECT $columns, NULL::timestamptz AS due_at, fire_at AS run_at FROM ankka_timers " +
        s"WHERE due_at = $Infinity AND fire_at <= "
    ) ++ sql"$now" ++ SqlFragment.raw(
      s" ORDER BY fire_at LIMIT $limit) ORDER BY run_at LIMIT $limit"
    )

  // ── A table from before recurring timers ─────────────────────────────────────
  //
  // A local database keeps the table its volume was created with: Postgres applies a schema only to
  // a new one. These are the statements of the previous release, so a timer that fires once is set,
  // run and backed off on such a table as it was before; each is still matched on the `due_at` read.

  /** The previous release's upsert: a timer that fires once, writing no column it lacks. */
  def legacyUpsert(name: String, call: DeferredCall, dueAt: Instant): SqlFragment =
    SqlFragment.raw(
      "INSERT INTO ankka_timers (timer_name, component_id, method, payload, due_at, attempts) VALUES ("
    ) ++
      sql"$name, ${call.componentId: String}, ${call.method: String}, ${call.payload}, $dueAt, ${0}" ++
      SqlFragment.raw(
        ") ON CONFLICT (timer_name) DO UPDATE SET " +
          "component_id = EXCLUDED.component_id, method = EXCLUDED.method, " +
          "payload = EXCLUDED.payload, due_at = EXCLUDED.due_at, attempts = 0"
      )

  /** What is due on such a table: timers that fire once, which is all it can hold. */
  def legacyDue(now: Instant, limit: Int): SqlFragment =
    SqlFragment.raw(
      "SELECT timer_name, component_id, method, payload, attempts, due_at FROM ankka_timers " +
        "WHERE due_at <= "
    ) ++ sql"$now" ++ SqlFragment.raw(s" ORDER BY due_at LIMIT $limit")

  /** A backoff on such a table, with no `due_for` to keep. */
  def legacyReschedule(name: String, attempts: Int, dueAt: Instant, nextDue: Instant): SqlFragment =
    SqlFragment.raw("UPDATE ankka_timers SET attempts = ") ++
      sql"${attempts + 1}" ++ SqlFragment.raw(", due_at = ") ++ sql"$nextDue" ++
      SqlFragment.raw(" WHERE timer_name = ") ++ sql"$name" ++
      SqlFragment.raw(" AND due_at = ") ++ sql"$dueAt"

  /** 3s doubling to a 30s ceiling — the range Akka documents, two attempts a minute at worst. */
  private[ankka] def backoff(attempts: Int): Long =
    math.min(3L * (1L << math.min(attempts, 4)), 30L)

  /** When a timer that has failed `attempts` times before this failure runs again. */
  private[ankka] def retryAt(attempts: Int): Instant = Instant.now().plusSeconds(backoff(attempts))

  /**
   * Pushes a failed timer that fires once out with exponential backoff, keeping in `due_for` the
   * due the attempt was for, so its retry is told the same one.
   *
   * Matched on the due time read, as [[deleteRan]] is, so a timer the handler replaced before it
   * failed keeps the schedule the handler gave it.
   */
  def reschedule(name: String, attempts: Int, dueAt: Instant, dueFor: Instant): SqlFragment =
    reschedule(name, attempts, dueAt, dueFor, retryAt(attempts))

  def reschedule(
      name: String,
      attempts: Int,
      dueAt: Instant,
      dueFor: Instant,
      nextDue: Instant
  ): SqlFragment =
    SqlFragment.raw("UPDATE ankka_timers SET attempts = ") ++
      sql"${attempts + 1}" ++ SqlFragment.raw(", due_at = ") ++ sql"$nextDue" ++
      SqlFragment.raw(", due_for = ") ++ sql"$dueFor" ++
      SqlFragment.raw(" WHERE timer_name = ") ++ sql"$name" ++
      SqlFragment.raw(" AND due_at = ") ++ sql"$dueAt"

  /** A recurring timer read with `dueFor` and `periodMillis`, and not changed since. */
  private def sameRecurring(name: String, dueFor: Instant, periodMillis: Long): SqlFragment =
    SqlFragment.raw(" WHERE timer_name = ") ++ sql"$name" ++
      SqlFragment.raw(s" AND due_at = $Infinity AND due_for = ") ++ sql"$dueFor" ++
      SqlFragment.raw(" AND period_millis = ") ++ sql"$periodMillis"

  /** A recurring timer that ran: next due `next`, failures forgotten. */
  def advance(name: String, dueFor: Instant, periodMillis: Long, next: Instant): SqlFragment =
    SqlFragment.raw("UPDATE ankka_timers SET due_for = ") ++ sql"$next" ++
      SqlFragment.raw(", fire_at = ") ++ sql"$next" ++ SqlFragment.raw(", attempts = 0") ++
      sameRecurring(name, dueFor, periodMillis)

  /** A recurring timer that failed: run again after the backoff, for the same due. */
  def rescheduleRecurring(
      name: String,
      attempts: Int,
      dueFor: Instant,
      periodMillis: Long
  ): SqlFragment =
    rescheduleRecurring(name, attempts, dueFor, periodMillis, retryAt(attempts))

  def rescheduleRecurring(
      name: String,
      attempts: Int,
      dueFor: Instant,
      periodMillis: Long,
      at: Instant
  ): SqlFragment =
    SqlFragment.raw("UPDATE ankka_timers SET attempts = ") ++ sql"${attempts + 1}" ++
      SqlFragment.raw(", fire_at = ") ++ sql"$at" ++ sameRecurring(name, dueFor, periodMillis)

  /**
   * How long a recurring timer whose handler this sweeper lacks waits before it is looked at again.
   */
  val DeferSeconds: Long = 30L

  /**
   * A recurring timer whose handler this sweeper does not have: looked at again later, nothing else
   * changed. Nothing failed, so the attempt count is not raised.
   */
  def defer(
      name: String,
      dueFor: Instant,
      periodMillis: Long,
      at: Instant = Instant.now().plusSeconds(DeferSeconds)
  ): SqlFragment =
    SqlFragment.raw("UPDATE ankka_timers SET fire_at = ") ++ sql"$at" ++
      sameRecurring(name, dueFor, periodMillis)
