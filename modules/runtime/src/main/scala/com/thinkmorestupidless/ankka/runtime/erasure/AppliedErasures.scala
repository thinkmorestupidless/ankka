package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

import scala.concurrent.{ExecutionContext, Future}

/**
 * `ankka_erasures_applied` (`50-erasure-postgres.sql`): which erasures this service has applied to
 * its own tables. A restored database has lost the rows of every erasure since the restore point
 * with everything else, which is what makes the service apply them again before it is ready.
 */
private[ankka] object AppliedErasures:

  def highest(database: Database)(using ExecutionContext): Future[Option[Long]] =
    database
      .queryOne(SqlFragment.raw("SELECT max(sequence) AS s FROM ankka_erasures_applied")) { row =>
        Option(row.get("s", classOf[java.lang.Long])).map(_.longValue)
      }
      .map(_.flatten)

  def record(
      database: Database,
      project: String,
      completion: Completion,
      subject: String
  ): Future[Long] =
    val handler = completion.handler
      .map(h => if h.ok then s"done: ${h.detail}" else s"failed: ${h.detail}")
      .orNull
    val erased = completion.handler.flatMap(_.objectsErased).map(java.lang.Long.valueOf).orNull
    val finalAt = completion.handler
      .flatMap(_.objectsFinalAt)
      .map(i => java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC))
      .orNull
    database.execute(
      SqlFragment.raw(
        "INSERT INTO ankka_erasures_applied (erasure_id, sequence, project, subject, handler_outcome, objects_erased, objects_final_at) VALUES ("
      ) ++ sql"${completion.erasureId}, ${completion.sequence}, $project, $subject, " ++
        nullable(handler, classOf[String]) ++ SqlFragment.raw(", ") ++
        nullable(erased, classOf[java.lang.Long]) ++ SqlFragment.raw(", ") ++
        nullable(finalAt, classOf[java.time.OffsetDateTime]) ++
        SqlFragment.raw(
          ") ON CONFLICT (erasure_id) DO UPDATE SET applied_at = now(), " +
            "handler_outcome = EXCLUDED.handler_outcome, objects_erased = EXCLUDED.objects_erased, " +
            "objects_final_at = EXCLUDED.objects_final_at"
        )
    )

  private def nullable(value: AnyRef, javaType: Class[?]): SqlFragment =
    new SqlFragment(
      Vector("", ""),
      Vector(com.thinkmorestupidless.ankka.runtime.SqlParam(value, javaType))
    )
