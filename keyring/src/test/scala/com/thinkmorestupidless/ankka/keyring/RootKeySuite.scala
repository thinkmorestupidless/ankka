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

  test("replicas starting at once on a database with no table agree on one root key") {
    for round <- 1 to 5 do
      Await.result(
        database.execute(SqlFragment.raw("DROP TABLE IF EXISTS ankka_secrets")),
        30.seconds
      )
      val roots = Await.result(
        scala.concurrent.Future.sequence(
          (1 to 4).map(_ =>
            scala.concurrent.Future {
              val db = database
              RootKey.postgres(
                DatabaseSecretStore(
                  db,
                  kit.secretKey.flatMap(k =>
                    com.thinkmorestupidless.ankka.runtime.SecretKey.parse(k).toOption
                  )
                ),
                db
              )
            }
          )
        ),
        2.minutes
      )
      val wrapped = roots.head.wrap("kek".getBytes, "brand".getBytes)
      roots.zipWithIndex.tail.foreach { (root, i) =>
        val opened = scala.util.Try(String(root.unwrap(wrapped, "brand".getBytes)))
        assertEquals(opened.toOption, Some("kek"), s"round $round: replica $i holds another root")
      }
      // And what a replica started later reads is the same root.
      val later = RootKey.postgres(secrets, database)
      assertEquals(String(later.unwrap(wrapped, "brand".getBytes)), "kek", s"round $round")
  }

  test("the table the keyring makes is the platform's") {
    val stream = getClass.getResourceAsStream("/ankka/ddl/40-secrets-postgres.sql")
    assert(stream != null, "the runtime carries no 40-secrets-postgres.sql")
    val ddl = try String(stream.readAllBytes())
    finally stream.close()
    def words(text: String) = text.split("\\s+").filter(_.nonEmpty).mkString(" ")
    val statement           = words(ddl.linesIterator.filterNot(_.startsWith("--")).mkString(" "))
    assertEquals(
      statement.stripSuffix(";").trim,
      words(RootKey.SecretsTable).replace("public.ankka_secrets", "ankka_secrets")
    )
  }

  test("a keyring started again, beside a schema named for its role, reads the same root key") {
    def sql(text: String) = Await.result(database.execute(SqlFragment.raw(text)), 30.seconds)
    val role              = Await
      .result(
        database
          .query(SqlFragment.raw("SELECT current_user AS who"))(_.get("who", classOf[String])),
        30.seconds
      )
      .head
    // As a fresh keyring database: no secret store, and no schema of the role's own name yet.
    sql("DROP TABLE IF EXISTS ankka_secrets")
    sql(s"""DROP SCHEMA IF EXISTS "$role" CASCADE""")
    try
      val first   = RootKey.postgres(secrets, database)
      val wrapped = first.wrap("kek".getBytes, "brand".getBytes)
      // What KeyStore does for the keyring's role: a schema its search path finds first.
      sql(s"""CREATE SCHEMA "$role"""")
      val again = RootKey.postgres(secrets, database)
      assertEquals(
        scala.util.Try(String(again.unwrap(wrapped, "brand".getBytes))).toOption,
        Some("kek"),
        "the keyring started again with another root key"
      )
    finally sql(s"""DROP SCHEMA IF EXISTS "$role" CASCADE"""): Unit
  }
