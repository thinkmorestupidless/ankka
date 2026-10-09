package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment, ViewVersions}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

import scala.concurrent.{ExecutionContext, Future}

/**
 * Rewrites every personal envelope of one data subject in a view's rows to its erased form,
 * removing the lookup token with the ciphertext (FR-012, R14).
 *
 * One statement per view, over the envelope's fixed grammar (`contracts/personal-envelope.md`), so
 * nothing decodes a row and a view whose row type has since changed is redacted all the same. The
 * subject is matched literally: its alphabet's one regular-expression character, `.`, is escaped.
 * Under the view's own lock, exclusively, as a rebuild takes it, so no write of the view
 * interleaves.
 */
private[ankka] object ViewRedaction:

  /** The present envelope of `subject` in `project`, as a POSIX regular expression. */
  def pattern(project: String, subject: String): String =
    s"""\\{"subject":"${literal(subject)}","project":"${literal(
        project
      )}","data":"[A-Za-z0-9+/=]*"(,"lookup":"[0-9a-f]*")?\\}"""

  def replacement(project: String, subject: String): String =
    s"""{"subject":"$subject","project":"$project"}"""

  private def literal(text: String): String = text.flatMap {
    case c if "\\.^$|?*+()[]{}".contains(c) => s"\\$c"
    case c                                  => c.toString
  }

  /** The statements that redact one view; the first takes the view's lock. */
  def statements(
      viewId: String,
      table: String,
      project: String,
      subject: String
  ): Vector[SqlFragment] =
    val like = s"""%"subject":"$subject","project":"$project","data":%"""
    Vector(
      ViewVersions.lockFragment(viewId, shared = false),
      SqlFragment.raw(s"UPDATE $table SET payload = regexp_replace(payload, ") ++
        sql"${pattern(project, subject)}, ${replacement(project, subject)}" ++
        SqlFragment.raw(", 'g'), updated_at = now() WHERE payload LIKE ") ++ sql"$like"
    )

  /** Redacts every view; answers the views it touched and how many rows changed. */
  def redact(database: Database, views: Vector[(String, String)], project: String, subject: String)(
      using ExecutionContext
  ): Future[(Vector[String], Long)] =
    views.foldLeft(Future.successful((Vector.empty[String], 0L))) {
      case (previous, (viewId, table)) =>
        previous.flatMap { (touched, total) =>
          database
            .inTransaction { tx =>
              val Vector(lock, update) = statements(viewId, table, project, subject)
              tx.query(lock)(_ => ()).flatMap(_ => tx.execute(update))
            }
            .map(rows => (if rows > 0 then touched :+ viewId else touched, total + rows))
        }
    }
