package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

import scala.concurrent.{ExecutionContext, Future}

/**
 * The version each topic-sourced view's rows were built at, and the two locks that keep a rebuild
 * and a write from overlapping.
 *
 * A view declared at a higher version than the one recorded is **rebuilt**: emptied, and read again
 * from its topic. Every instance of a service starts its views at once, and during a rolling update
 * old and new instances run side by side, so two rules have to hold however their statements
 * interleave: a view is emptied once, and no row an earlier version writes survives the emptying.
 *
 * A rebuild takes the view's advisory lock exclusively, reads the recorded version again, and only
 * if it is still lower empties the table and records the new version — all in one transaction, so
 * the instance that waited for the lock finds the work done. A write takes the same lock shared,
 * reads the recorded version, and writes only if it equals the writer's own. A write therefore
 * either finishes before a rebuild can start, or starts after it has committed and sees the version
 * it recorded; a conditional write alone would not do, because under `READ COMMITTED` a write that
 * decided the version matched and then waited for the truncation would land after it.
 *
 * Created by the runtime beside the view tables, only by a service with a topic-sourced view, and
 * never dropped: a view removed and added back later finds the version it was last built at.
 */
private[ankka] object ViewVersions:

  /** The first half of each view's advisory lock key; the second is the hash of its id. */
  val LockClass: Int = 62716

  val createTable: SqlFragment =
    SqlFragment.raw(
      """CREATE TABLE IF NOT EXISTS ankka_view_versions (
        |  component_id TEXT PRIMARY KEY,
        |  version      INTEGER NOT NULL CHECK (version >= 1),
        |  built_at     TIMESTAMPTZ NOT NULL DEFAULT now()
        |)""".stripMargin
    )

  /**
   * A view with no recorded version is taken to be at version 1, without touching its rows: its
   * table predates versions, and was built by the handler it has.
   */
  def ensure(componentId: String): SqlFragment =
    SqlFragment.raw("INSERT INTO ankka_view_versions (component_id, version) VALUES (") ++
      sql"$componentId" ++ SqlFragment.raw(", 1) ON CONFLICT (component_id) DO NOTHING")

  private def lock(componentId: String, shared: Boolean): SqlFragment =
    val function = if shared then "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
    SqlFragment.raw(s"SELECT $function($LockClass, hashtext(") ++ sql"$componentId" ++
      SqlFragment.raw("))")

  private def selectVersion(componentId: String): SqlFragment =
    SqlFragment.raw("SELECT version FROM ankka_view_versions WHERE component_id = ") ++
      sql"$componentId"

  private def readVersion(tx: Database.Transaction, componentId: String)(using
      ExecutionContext
  ): Future[Int] =
    tx.query(selectVersion(componentId))(_.get("version", classOf[Integer]).intValue)
      .map(_.headOption.getOrElse(1))

  /** The version a view's rows were last built at; 1 for one never recorded. */
  def recorded(database: Database, componentId: String)(using ExecutionContext): Future[Int] =
    database
      .query(selectVersion(componentId))(_.get("version", classOf[Integer]).intValue)
      .map(_.headOption.getOrElse(1))

  /** What a rebuild found once it held the view's lock. */
  enum Rebuilt:
    /** It emptied the table, and the view is now recorded at the declared version. */
    case Emptied(from: Int)

    /** Another instance had already built the view at this version. */
    case AlreadyBuilt

    /** The view is recorded at a higher version than this instance declares. */
    case Behind(recorded: Int)

  /** Empties `table` and records `declared`, once, if the view is still recorded below it. */
  def rebuild(database: Database, table: String, componentId: String, declared: Int)(using
      ExecutionContext
  ): Future[Rebuilt] =
    database.inTransaction { tx =>
      for
        _        <- tx.execute(lock(componentId, shared = false))
        recorded <- readVersion(tx, componentId)
        result <-
          if recorded < declared then
            for
              _ <- tx.execute(SqlFragment.raw(s"TRUNCATE $table"))
              _ <- tx.execute(
                SqlFragment.raw("UPDATE ankka_view_versions SET version = ") ++ sql"$declared" ++
                  SqlFragment.raw(", built_at = now() WHERE component_id = ") ++ sql"$componentId"
              )
            yield Rebuilt.Emptied(recorded)
          else if recorded == declared then Future.successful(Rebuilt.AlreadyBuilt)
          else Future.successful(Rebuilt.Behind(recorded))
      yield result
    }

  /** Whether a guarded write wrote, or found the view recorded at another version. */
  enum Written:
    case Wrote
    case Behind(recorded: Int)

  /**
   * `write`, if and only if the view is recorded at `declared`, in one transaction under the view's
   * shared lock.
   */
  def guarded(database: Database, componentId: String, declared: Int)(
      write: SqlFragment
  )(using ExecutionContext): Future[Written] =
    database.inTransaction { tx =>
      for
        _        <- tx.execute(lock(componentId, shared = true))
        recorded <- readVersion(tx, componentId)
        result <-
          if recorded == declared then tx.execute(write).map(_ => Written.Wrote)
          else Future.successful(Written.Behind(recorded))
      yield result
    }

/**
 * Every write a topic-sourced view makes, made only while its rows are recorded at the version this
 * instance declares. A write that finds them at another version writes nothing, tells `onBehind`
 * (which stops the view's subscription), and fails, so the message's offset is not committed and an
 * instance at the right version reads it.
 */
private[ankka] final class ViewGuard(
    database: Database,
    componentId: String,
    declared: Int,
    onBehind: Int => Unit
)(using ec: ExecutionContext):

  def write(fragment: SqlFragment): Future[org.apache.pekko.Done] =
    ViewVersions.guarded(database, componentId, declared)(fragment).flatMap {
      case ViewVersions.Written.Wrote => Future.successful(org.apache.pekko.Done)
      case ViewVersions.Written.Behind(recorded) =>
        onBehind(recorded)
        Future.failed(ViewGuard.Behind(componentId, declared, recorded))
    }

private[ankka] object ViewGuard:
  final case class Behind(componentId: String, declared: Int, recorded: Int)
      extends RuntimeException(
        s"view '$componentId' is declared at version $declared and its rows are recorded at " +
          s"$recorded; this instance writes nothing to them"
      )
