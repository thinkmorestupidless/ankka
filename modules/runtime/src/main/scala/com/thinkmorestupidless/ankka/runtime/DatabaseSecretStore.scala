package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, PlatformVariables}
import com.thinkmorestupidless.ankka.sdk.{SecretRules, SecretStore}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import io.r2dbc.spi.R2dbcException
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.{blocking, Await, Future}
import scala.util.control.NonFatal

/**
 * The secret store the runtime gives components: one table, `ankka_secrets`, in the service's own
 * database, on the pool the journal already uses.
 *
 * Nothing is cached. Another instance may have replaced or removed a value since it was last read,
 * and a cache would be a second place a plaintext lives. Names are logged; values never are.
 */
private[ankka] final class DatabaseSecretStore(
    database: Database,
    key: Option[SecretKey],
    timeout: FiniteDuration = 10.seconds
) extends SecretStore:

  private val log = LoggerFactory.getLogger(classOf[DatabaseSecretStore])

  def put(name: String, value: String): Unit =
    SecretRules.check(name, value)
    val stored = SecretCipher.encrypt(required, name, value)
    run(name)(database.execute(SecretStoreSql.upsert(name, stored))): Unit
    log.debug("kept the secret '{}'", name)

  def get(name: String): Option[String] =
    SecretRules.check(name)
    val k = required
    run(name)(database.queryOne(SecretStoreSql.byName(name))(_.get(0, classOf[Array[Byte]])))
      .map(SecretCipher.decrypt(k, name, _))

  def delete(name: String): Unit =
    SecretRules.check(name)
    run(name)(database.execute(SecretStoreSql.delete(name))): Unit
    log.debug("removed the secret '{}'", name)

  /** Every name the table holds: what a move copies and checks. */
  private[ankka] def names(): Vector[String] =
    run("*")(database.query(SecretStoreSql.names)(_.get(0, classOf[String])))

  /** Removes every row: the last step of a move, once every name has been checked equal. */
  private[ankka] def deleteAll(): Long =
    run("*")(database.execute(SecretStoreSql.deleteAll))

  private def required: SecretKey =
    key.getOrElse(
      throw CommandError(
        s"this service has no secret key, so it cannot keep or read a secret; set " +
          s"${PlatformVariables.SecretKey} to ${SecretKey.Form}",
        ErrorCode.Internal
      )
    )

  private def run[A](name: String)(work: => Future[A]): A =
    try blocking(Await.result(work, timeout))
    catch
      case e: R2dbcException if e.getSqlState == "42P01" =>
        throw CommandError(
          "this service's database has no ankka_secrets table; apply 40-secrets-postgres.sql " +
            "from the platform's schema, or recreate a local database created before it existed",
          ErrorCode.Internal
        )
      case e: CommandError => throw e
      case NonFatal(e) =>
        log.warn("the secret store could not reach the database for '{}': {}", name, e.getMessage)
        throw CommandError(
          s"the secret store is unavailable: ${e.getMessage}",
          ErrorCode.Unavailable
        )

/** SQL for the secrets table. */
private[ankka] object SecretStoreSql:

  def upsert(name: String, ciphertext: Array[Byte]): SqlFragment =
    SqlFragment.raw("INSERT INTO ankka_secrets (name, ciphertext) VALUES (") ++
      sql"$name, $ciphertext" ++
      SqlFragment.raw(
        ") ON CONFLICT (name) DO UPDATE SET ciphertext = EXCLUDED.ciphertext, updated_at = now()"
      )

  def byName(name: String): SqlFragment =
    SqlFragment.raw("SELECT ciphertext FROM ankka_secrets WHERE name = ") ++ sql"$name"

  def delete(name: String): SqlFragment =
    SqlFragment.raw("DELETE FROM ankka_secrets WHERE name = ") ++ sql"$name"

  val names: SqlFragment     = SqlFragment.raw("SELECT name FROM ankka_secrets ORDER BY name")
  val deleteAll: SqlFragment = SqlFragment.raw("DELETE FROM ankka_secrets")
