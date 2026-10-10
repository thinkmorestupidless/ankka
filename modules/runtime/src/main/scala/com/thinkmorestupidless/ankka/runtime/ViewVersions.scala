package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcSession
import org.apache.pekko.projection.scaladsl.ProjectionManagement

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

/**
 * The version each view's rows were built at, and the two locks that keep a rebuild and a write
 * from overlapping.
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
 * Created by the runtime beside the view tables, by a service with any view, and never dropped: a
 * view removed and added back later finds the version it was last built at. A view that reads
 * entities is rebuilt by its projections reading every source again under names carrying the new
 * version (`ViewProjections`); a view that reads a topic, under a new group.
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

  /**
   * The view's lock, as a statement any transaction can take it with: shared by a plain view's
   * write, exclusive by a rebuild and by every change a keyed view handles.
   */
  def lockFragment(componentId: String, shared: Boolean): SqlFragment = lock(componentId, shared)

  private def lock(componentId: String, shared: Boolean): SqlFragment =
    val function = if shared then "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
    SqlFragment.raw(s"SELECT $function($LockClass, hashtext(") ++ sql"$componentId" ++
      SqlFragment.raw("))")

  def selectVersion(componentId: String): SqlFragment =
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
              // Every watch of the view, on every instance, ends when this commits.
              _ <- tx.execute(ViewStore.announceRebuilt(table))
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

/**
 * Every write a view that reads entities makes, made in the change's own transaction only while the
 * view's rows are recorded at the version this instance declares (`contracts/rebuild.md`, "The
 * guarded write").
 *
 * The view's lock is taken first — shared by a plain view, so its slices do not queue behind each
 * other, and exclusive by a keyed view, which is also what makes it handle one change at a time —
 * then the recorded version is read. A rebuild holds the lock exclusively, so a write either
 * commits before the emptying or starts after it and finds the new version: 024's argument, for
 * entities. A write that finds another version writes nothing, fails so the change is not recorded
 * as handled, pauses its projection, and the instance says once that it is behind.
 */
private[ankka] final class EntityViewGuard(
    componentId: String,
    declared: Int,
    shared: Boolean,
    projection: ProjectionId,
    said: AtomicBoolean
)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  private val lock    = ViewVersions.lockFragment(componentId, shared)
  private val version = ViewVersions.selectVersion(componentId)

  private def compare(recorded: Option[Int]): Future[Done] =
    val at = recorded.getOrElse(1)
    if at == declared then Future.successful(Done)
    else
      if said.compareAndSet(false, true) then
        system.log.warn(
          "view behind its recorded version: component={} declared={} recorded={}; this instance " +
            "reads nothing from its sources and writes nothing to its table",
          componentId,
          declared,
          at
        )
      ProjectionManagement(system).pause(projection): Unit
      Future.failed(ViewGuard.Behind(componentId, declared, at))

  /** Through a projection's own session, as an exactly-once handler writes. */
  def inSession(session: R2dbcSession): Future[Done] =
    def select[A](fragment: SqlFragment)(read: io.r2dbc.spi.Row => A) =
      session.selectOne(Database.bind(session.createStatement(fragment.render), fragment))(read)
    for
      _        <- select(lock)(_ => ())
      recorded <- select(version)(_.get("version", classOf[Integer]).intValue)
      done     <- compare(recorded)
    yield done

  /** In a transaction of the view's own, as an at-least-once handler writes. */
  def inTransaction(tx: Database.Transaction): Future[Done] =
    for
      _        <- tx.query(lock)(_ => ())
      recorded <- tx.query(version)(_.get("version", classOf[Integer]).intValue)
      done     <- compare(recorded.headOption)
    yield done
