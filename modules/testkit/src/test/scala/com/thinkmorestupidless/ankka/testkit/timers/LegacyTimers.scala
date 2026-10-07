package com.thinkmorestupidless.ankka.testkit.timers

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.DeferredCall

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * What a runtime from before recurring timers does to the timer table, and nothing else.
 *
 * `Store` is `TimerStore` as it is at tag `v0.10.0`, copied verbatim from
 * `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/TimerRuntime.scala` lines
 * 104–140 of that tag; `createTable` is that tag's `30-timers-postgres.sql`; and `sweep` does what
 * that tag's `Sweep.runBatch` did with them: select what is due, run each, delete it by name when
 * it succeeded and back it off by name when it failed.
 *
 * Its whole value is that it knows nothing about periods. Never fix it, extend it or point it at
 * the current `TimerStore`: a test that a recurring timer survives this code is only a test if this
 * is the code that was released.
 */
object LegacyTimers:

  /** `v0.10.0`'s `TimerStore`, verbatim. */
  object Store:

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

    def due(now: Instant, limit: Int): SqlFragment =
      SqlFragment.raw(
        "SELECT timer_name, component_id, method, payload, attempts FROM ankka_timers WHERE due_at <= "
      ) ++ sql"$now" ++ SqlFragment.raw(s" ORDER BY due_at LIMIT $limit")

    def reschedule(name: String, attempts: Int): SqlFragment =
      val backoffSeconds = math.min(3L * (1L << math.min(attempts, 4)), 30L)
      val nextDue        = Instant.now().plusSeconds(backoffSeconds)
      SqlFragment.raw("UPDATE ankka_timers SET attempts = ") ++
        sql"${attempts + 1}" ++ SqlFragment.raw(", due_at = ") ++ sql"$nextDue" ++
        SqlFragment.raw(" WHERE timer_name = ") ++ sql"$name"

  /** `v0.10.0`'s `30-timers-postgres.sql`, as statements. */
  val createTable: Vector[SqlFragment] = Vector(
    SqlFragment.raw(
      """CREATE TABLE IF NOT EXISTS ankka_timers (
        |  timer_name   TEXT PRIMARY KEY,
        |  component_id TEXT NOT NULL,
        |  method       TEXT NOT NULL,
        |  payload      BYTEA NOT NULL,
        |  due_at       TIMESTAMPTZ NOT NULL,
        |  attempts     INT NOT NULL DEFAULT 0,
        |  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
        |)""".stripMargin
    ),
    SqlFragment.raw("CREATE INDEX IF NOT EXISTS ankka_timers_due_idx ON ankka_timers (due_at)")
  )

  /** A row as `v0.10.0`'s sweeper read it. */
  final case class Due(
      name: String,
      componentId: String,
      method: String,
      payload: Array[Byte],
      attempts: Int
  )

  /** One batch, as `v0.10.0`'s sweeper ran it: `run` answers whether the handler succeeded. */
  def sweep(database: Database)(run: Due => Boolean)(using ExecutionContext): Seq[Due] =
    val due = Await.result(
      database.query(Store.due(Instant.now(), 100)) { row =>
        Due(
          row.get("timer_name", classOf[String]),
          row.get("component_id", classOf[String]),
          row.get("method", classOf[String]),
          row.get("payload", classOf[Array[Byte]]),
          row.get("attempts", classOf[Integer]).intValue
        )
      },
      10.seconds
    )
    val outcomes = due.map { timer =>
      val succeeded =
        try run(timer)
        catch case scala.util.control.NonFatal(_) => false
      if succeeded then database.execute(Store.delete(timer.name))
      else database.execute(Store.reschedule(timer.name, timer.attempts))
    }
    Await.result(Future.sequence(outcomes), 10.seconds): Unit
    due
