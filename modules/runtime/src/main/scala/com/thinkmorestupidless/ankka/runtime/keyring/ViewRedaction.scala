package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.runtime.{Database, ViewStore}

import scala.concurrent.{ExecutionContext, Future}

/**
 * An erased data subject's personal envelopes, reduced in every view of the service to the subject
 * alone (feature 042, R14): rows are found by the subject in the clear, rewritten only in what they
 * hold of that subject, and their lookup tokens dropped. Run on a destroyed notice, and again on
 * the replay after a restore; running it twice changes nothing the second time.
 */
object ViewRedaction:

  /** One view's table, and whether it keeps lookup tokens. */
  final case class Table(name: String, lookup: Boolean)

  /** What a redaction rewrote: rows, and the rows among them whose tokens were dropped. */
  final case class Redacted(rows: Long, tokens: Long):
    def +(other: Redacted): Redacted = Redacted(rows + other.rows, tokens + other.tokens)

  object Redacted:
    val none: Redacted = Redacted(0, 0)

  /** Redacts `subject` of `project` from each table in turn. */
  def redact(database: Database, tables: Vector[Table], project: String, subject: String)(using
      ExecutionContext
  ): Future[Redacted] =
    tables.foldLeft(Future.successful(Redacted.none)) { (done, table) =>
      done.flatMap { sum =>
        database
          .query(ViewStore.redact(table.name, project, subject, table.lookup))(row =>
            Redacted(
              row.get("rows", classOf[java.lang.Long]).longValue,
              row.get("tokens", classOf[java.lang.Long]).longValue
            )
          )
          .map(rs => rs.foldLeft(sum)(_ + _))
      }
    }
