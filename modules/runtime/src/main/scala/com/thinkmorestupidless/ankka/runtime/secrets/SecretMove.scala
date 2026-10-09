package com.thinkmorestupidless.ankka.runtime.secrets

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, PlatformVariables}
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaExecutors,
  Database,
  DatabaseSecretStore,
  SqlFragment
}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.SecretStore
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** A phase of a move from the Postgres backend to Secret Manager, as the installation names it. */
private[ankka] enum MovePhase(val word: String):
  case Copy   extends MovePhase("copy")
  case Check  extends MovePhase("check")
  case Remove extends MovePhase("remove")

private[ankka] object MovePhase:
  val Key: String = "ankka.secrets.move"

  def parse(word: String): Either[String, Option[MovePhase]] =
    word.trim match
      case "" => Right(None)
      case w =>
        MovePhase.values
          .find(_.word == w)
          .map(Some(_))
          .toRight(
            s"${PlatformVariables.SecretMove} is '$w', which is not a phase of a move; use copy, " +
              "check or remove"
          )

/** How one name compares across the two backends, by digest; never by value. */
private[ankka] enum NameState:
  case Equal
  case Different
  case MissingInSecretManager

  /** As a report names it: `equal`, `different`, `missing-in-secret-manager`. */
  def word: String = this match
    case Equal                  => "equal"
    case Different              => "different"
    case MissingInSecretManager => "missing-in-secret-manager"

/**
 * What a move did when this instance started, held until it starts again: the phase, the outcome,
 * and each name's state. Names and states only; never a value.
 */
private[ankka] final case class MoveReport(
    phase: String,
    outcome: String,
    names: Vector[(String, String)] = Vector.empty,
    detail: Option[String] = None
)

private[ankka] object MoveReport:
  val Running: String     = "running"
  val Copied: String      = "copied"
  val Checked: String     = "checked"
  val Removed: String     = "removed"
  val Refused: String     = "refused"
  val Unreachable: String = "unreachable"
  val RolledBack: String  = "rolled-back"

/**
 * The names a move must remember in the service's own database: a secret kept in Secret Manager
 * after the copy, which the database does not hold, and the mark that the removal step has run.
 * Names only, never a value; made when a move first needs it, as the role the service connects as.
 */
private[ankka] final class MoveLedger(database: Database, timeout: FiniteDuration):

  private def await[A](work: Future[A]): A = Await.result(work, timeout)

  /** The mark the removal step leaves: a name no secret can have, since `*` is not allowed. */
  val RemovedMark: String = "*removed*"

  def ensure(): Unit =
    await(
      database.execute(
        SqlFragment.raw(
          "CREATE TABLE IF NOT EXISTS ankka_secret_moves (name TEXT PRIMARY KEY, " +
            "at TIMESTAMPTZ NOT NULL DEFAULT now())"
        )
      )
    ): Unit

  def exists(): Boolean =
    await(
      database.queryOne(SqlFragment.raw("SELECT to_regclass('ankka_secret_moves')::text"))(row =>
        Option(row.get(0, classOf[String]))
      )
    ).flatten.nonEmpty

  def keptOnlyInSecretManager(): Vector[String] =
    if !exists() then Vector.empty
    else
      await(
        database.query(
          sql"SELECT name FROM ankka_secret_moves WHERE name <> $RemovedMark ORDER BY name"
        )(
          _.get(0, classOf[String])
        )
      )

  def removedAt(): Option[Instant] =
    if !exists() then None
    else
      await(
        database.queryOne(sql"SELECT at FROM ankka_secret_moves WHERE name = $RemovedMark")(
          _.get(0, classOf[java.time.OffsetDateTime]).toInstant
        )
      )

  def kept(name: String): Unit =
    await(
      database.execute(
        sql"INSERT INTO ankka_secret_moves (name) VALUES ($name) ON CONFLICT (name) DO NOTHING"
      )
    ): Unit

  def forgotten(name: String): Unit =
    await(database.execute(sql"DELETE FROM ankka_secret_moves WHERE name = $name")): Unit

  def markRemoved(): Unit =
    await(
      database.execute(
        sql"INSERT INTO ankka_secret_moves (name) VALUES ($RemovedMark) ON CONFLICT (name) DO NOTHING"
      )
    ): Unit

/**
 * The Secret Manager store during a move: a secret kept that the database does not hold is written
 * in the ledger, by name, so a service set back to the Postgres backend before the removal step can
 * say which secrets only Secret Manager holds.
 */
private[ankka] final class MovingStore(
    underlying: SecretManagerStore,
    rows: DatabaseSecretStore,
    ledger: MoveLedger
) extends SecretStore
    with ReadDetail:
  def put(name: String, value: String): Unit =
    underlying.put(name, value)
    if !rows.names().contains(name) then ledger.kept(name)
  def get(name: String): Option[String]   = underlying.get(name)
  def read(name: String): ReadDetail.Read = underlying.read(name)
  def delete(name: String): Unit =
    underlying.delete(name)
    ledger.forgotten(name)

/**
 * A move of a service's secrets from its database to Secret Manager, one phase at a time, run when
 * the instance starts. The instance is not ready until the phase has run to its end; while Secret
 * Manager cannot be reached it is not ready, says why, changes nothing, and tries again.
 *
 *   - `copy`: every row not already in Secret Manager is copied there, decrypted with the service's
 *     own key; a secret Secret Manager already holds is never overwritten. Then a check.
 *   - `check`: each name's value in the database against Secret Manager's newest, by SHA-256
 *     digest.
 *   - `remove`: a check, and the rows are deleted only when every name is equal; otherwise they are
 *     left and the names that differ are reported. A service that has removed its rows marks it,
 *     and will not start on the Postgres backend again.
 *
 * Nothing here reads a value into a log, a report or an exception: what is compared is a digest.
 */
private[ankka] final class SecretMove(
    phase: MovePhase,
    rows: DatabaseSecretStore,
    target: SecretManagerStore,
    ledger: MoveLedger,
    retryEvery: FiniteDuration
):
  private val log = LoggerFactory.getLogger(classOf[SecretMove])

  @volatile private var current: MoveReport = MoveReport(phase.word, MoveReport.Running)
  @volatile private var finished: Boolean   = false

  def report: MoveReport = current

  /** Ready once the phase has run to its end; the reason otherwise. */
  def readiness: () => Either[String, Unit] = () =>
    if finished then Right(())
    else
      Left(
        s"the move of this service's secrets to Secret Manager (phase ${phase.word}) has not " +
          s"finished" + current.detail.fold("")(d => s": $d")
      )

  /** Runs the phase on a virtual thread, again every `retryEvery` while Secret Manager is away. */
  def start(): Unit =
    Future(loop())(using AnkkaExecutors.virtual): Unit

  private def loop(): Unit =
    var done = false
    while !done do
      try
        current = run()
        finished = true
        done = true
        log.info(
          "secret move {}: {} ({} names)",
          phase.word,
          current.outcome,
          current.names.size
        )
      catch
        case e: CommandError if e.code == ErrorCode.Unavailable =>
          current = MoveReport(phase.word, MoveReport.Unreachable, detail = Some(e.message))
          log.warn(
            "secret move {}: Secret Manager cannot be reached, will try again: {}",
            phase.word,
            e.message
          )
          Thread.sleep(retryEvery.toMillis)
        case NonFatal(e) =>
          current = MoveReport(phase.word, MoveReport.Refused, detail = Some(e.getMessage))
          log.error("secret move {} failed: {}", phase.word, e.getMessage)
          done = true

  private def run(): MoveReport =
    ledger.ensure()
    phase match
      case MovePhase.Copy =>
        rows.names().foreach { name =>
          if target.read(name).value.isEmpty then
            rows.get(name).foreach(value => target.put(name, value))
        }
        MoveReport(phase.word, MoveReport.Copied, check())
      case MovePhase.Check =>
        MoveReport(phase.word, MoveReport.Checked, check())
      case MovePhase.Remove =>
        val states    = check()
        val differing = states.filter(_._2 != NameState.Equal.word)
        if differing.isEmpty then
          rows.deleteAll(): Unit
          ledger.markRemoved()
          MoveReport(phase.word, MoveReport.Removed, states)
        else
          MoveReport(
            phase.word,
            MoveReport.Refused,
            states,
            Some(s"not removed: ${differing.map(_._1).mkString(", ")} differ from Secret Manager")
          )

  /** Each name the database holds, and how Secret Manager's newest compares, by digest. */
  private def check(): Vector[(String, String)] =
    rows.names().map { name =>
      val here  = rows.get(name).map(SecretMove.digest)
      val there = target.read(name).value.map(SecretMove.digest)
      val state = (here, there) match
        case (_, None)                    => NameState.MissingInSecretManager
        case (Some(a), Some(b)) if a == b => NameState.Equal
        case _                            => NameState.Different
      name -> state.word
    }

private[ankka] object SecretMove:

  def digest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /**
   * On the Postgres backend: refused when the removal step has run, since the database no longer
   * holds the secrets; otherwise, after a move that was set back before its removal step, the names
   * only Secret Manager holds.
   */
  def onPostgres(ledger: MoveLedger, service: String): MoveReport =
    ledger.removedAt() match
      case Some(at) =>
        throw IllegalStateException(
          s"the secrets of $service live only in Secret Manager since its removal step at $at; " +
            s"set ${PlatformVariables.SecretBackend} back to secret-manager"
        )
      case None =>
        val only = ledger.keptOnlyInSecretManager()
        if only.nonEmpty then
          LoggerFactory
            .getLogger(classOf[SecretMove])
            .warn(
              "kept only in Secret Manager, not read on the Postgres backend: {}",
              only.mkString(", ")
            )
        MoveReport("", MoveReport.RolledBack, only.map(_ -> "only-in-secret-manager"))
