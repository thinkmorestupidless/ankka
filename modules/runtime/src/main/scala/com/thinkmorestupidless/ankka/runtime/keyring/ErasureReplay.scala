package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.slf4j.LoggerFactory

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

/**
 * A service on a database the erasure log has not yet been applied to (feature 042, R22): one
 * restored to before an erasure, or the first start of a service on a new database line. Until
 * every notice the keyring holds for the project has been carried out here (its views redacted, its
 * sessions ended), the instance is not ready, as `erasure-replay`; then the line is recorded in the
 * database, with when, and no later start on it waits.
 *
 * A database restored to before the record holds no record for its own line (a restore is a new
 * line), so it waits again: that is the point.
 */
final class ErasureReplay(
    database: () => Database,
    line: String,
    project: String,
    client: KeyringClient,
    duties: ErasureDuties,
    clock: Clock = Clock.systemUTC(),
    every: scala.concurrent.duration.FiniteDuration =
      scala.concurrent.duration.DurationInt(100).millis
)(using ExecutionContext):

  private val log  = LoggerFactory.getLogger(classOf[ErasureReplay])
  private val done = AtomicReference[Option[Instant]](None)

  /** When the erasure log was applied on this line; none while it is being applied. */
  def replayedAt: Option[Instant] = done.get

  /** The readiness check: not ready, naming what it waits for, until the log is applied. */
  val readiness: () => Either[String, Unit] = () =>
    if done.get.isDefined then Right(())
    else Left(s"erasure-replay: the erasure log of '$project' is not yet applied to this service")

  /** Applies the log if this line has not had it; the time it was, or had been, applied. */
  def start(): Future[Instant] =
    val applied =
      for
        _     <- database().execute(ErasureReplay.Table)
        found <- recorded()
        when  <- found match
          case Some(at) => Future.successful(at)
          case None     =>
            log.info(
              s"erasure replay: database line '$line' has no record of the erasure log; this " +
                "instance is not ready until it has carried out every erasure of the project"
            )
            for
              _ <- client.caughtUp
              end = client.lastNotice
              _ <- until(duties.doneThrough(end))
              _ <- database().execute(
                sql"INSERT INTO ankka_erasure_replay (line, replayed_at) VALUES ($line, ${clock
                    .instant()}) " ++ SqlFragment.raw("ON CONFLICT (line) DO NOTHING")
              )
              at <- recorded().map(_.getOrElse(clock.instant()))
            yield
              log.info(s"erasure replay: every erasure up to $end is carried out on '$line'")
              at
      yield when
    applied.map { at =>
      done.set(Some(at))
      at
    }

  /** What the observe port serves: the line, and when it was applied, once it was. */
  def json: String =
    val at = done.get.fold("")(t => s""","replayedAt":"$t"""")
    s"""{"line":${com.thinkmorestupidless.ankka.runtime.Json.str(line)}$at}"""

  private def recorded(): Future[Option[Instant]] =
    database().queryOne(
      sql"SELECT replayed_at FROM ankka_erasure_replay WHERE line = $line"
    )(_.get("replayed_at", classOf[java.time.OffsetDateTime]).toInstant)

  private def until(condition: => Boolean): Future[Unit] =
    Future {
      while !condition do Thread.sleep(every.toMillis)
    }(using AnkkaExecutors.virtual)

object ErasureReplay:
  /** Made by the runtime beside its view tables, as `ankka_view_versions` is. */
  val Table: SqlFragment = SqlFragment.raw(
    "CREATE TABLE IF NOT EXISTS ankka_erasure_replay (line text PRIMARY KEY, replayed_at timestamptz NOT NULL)"
  )

  /** The readiness check's name, which the probe's reason begins with. */
  val Name = "erasure-replay"

  private val bySystem =
    java.util.concurrent
      .ConcurrentHashMap[org.apache.pekko.actor.typed.ActorSystem[?], ErasureReplay]()

  /** The replay of `system`'s service, which the observe port reads; none without duties. */
  def of(system: org.apache.pekko.actor.typed.ActorSystem[?]): Option[ErasureReplay] =
    Option(bySystem.get(system))

  private[runtime] def register(
      system: org.apache.pekko.actor.typed.ActorSystem[?],
      replay: ErasureReplay
  ): Unit =
    bySystem.put(system, replay): Unit
    system.whenTerminated.foreach(_ => bySystem.remove(system): Unit)(using
      ExecutionContext.parasitic
    )
