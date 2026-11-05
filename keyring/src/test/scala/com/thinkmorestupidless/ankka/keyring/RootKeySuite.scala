package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.runtime.{Database, DatabaseSecretStore, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/**
 * Feature 042: the root key on Postgres, in a database the platform's schema never reached. The
 * keyring's database is made by CNPG with no schema, so the secret store's table is the keyring's
 * to make; the test kit's databases carry the platform's DDL, which hid that it made none.
 */
class RootKeySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def database: Database = Database()(using kit.service.system)
  private def secrets            = DatabaseSecretStore(
    database,
    kit.secretKey.flatMap(k => com.thinkmorestupidless.ankka.runtime.SecretKey.parse(k).toOption)
  )

  private def tableExists: Boolean =
    Await.result(
      database.query(SqlFragment.raw("SELECT to_regclass('ankka_secrets') IS NOT NULL AS made"))(
        _.get("made", classOf[java.lang.Boolean]).booleanValue
      ),
      30.seconds
    ) == Vector(true)

  test("the root key is made in a database with no secret store table, and read back after") {
    Await.result(
      database.execute(SqlFragment.raw("DROP TABLE IF EXISTS ankka_secrets")),
      30.seconds
    )
    assert(!tableExists, "the table is still there")
    val first = RootKey.postgres(secrets, database)
    assert(tableExists, "the root key was made with no table to keep it in")
    val second  = RootKey.postgres(secrets, database)
    val wrapped = first.wrap("kek".getBytes, "brand".getBytes)
    assertEquals(String(second.unwrap(wrapped, "brand".getBytes)), "kek")
  }

  test("the table the keyring makes is the platform's") {
    val stream = getClass.getResourceAsStream("/ankka/ddl/40-secrets-postgres.sql")
    assert(stream != null, "the runtime carries no 40-secrets-postgres.sql")
    val ddl = try String(stream.readAllBytes())
    finally stream.close()
    def words(text: String) = text.split("\\s+").filter(_.nonEmpty).mkString(" ")
    val statement           = words(ddl.linesIterator.filterNot(_.startsWith("--")).mkString(" "))
    assertEquals(statement.stripSuffix(";").trim, words(RootKey.SecretsTable))
  }
