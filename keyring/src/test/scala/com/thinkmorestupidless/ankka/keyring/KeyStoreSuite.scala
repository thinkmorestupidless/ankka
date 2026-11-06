package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Feature 042, T047: the keyring's tables against Postgres. */
class KeyStoreSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  // A service of no components, for its database: the keyring's tables are made in it by code.
  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def database: Database        = Database()(using kit.service.system)
  private def store                     = KeyStore(database)
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)

  private def key(n: Int): Array[Byte] = Array.fill(32)(n.toByte)

  test("racing first writes of one data subject agree on one key") {
    val s       = store
    val answers = await(
      Future.sequence(
        (1 to 8).map(n =>
          Future(await(s.subjectKey("brand", "player/race", create = true)(key(n))))
        )
      )
    )
    val keys = answers.collect { case KeyStore.Stored.Key(k) => k.toVector }.distinct
    assertEquals(keys.size, 1, answers.toString)
    assertEquals(
      await(s.subjectKey("brand", "player/race", create = false)(fail("made again"))) match
        case KeyStore.Stored.Key(k) => k.toVector
        case other                  => fail(other.toString)
      ,
      keys.head
    )
    assertEquals(
      await(s.subjectKey("brand", "player/nobody", create = false)(key(0))),
      KeyStore.Stored.Absent
    )
  }

  test("a destroy deletes the key, writes the tombstone and the applied entry, and is idempotent") {
    val s  = store
    val at = Instant.parse("2026-11-04T10:00:00Z")
    await(s.subjectKey("brand", "player/8c1f", create = true)(key(42))): Unit
    assertEquals(await(s.destroy(1, "brand", "player/8c1f", at)), at)
    assertEquals(
      await(s.subjectKey("brand", "player/8c1f", create = false)(key(0))),
      KeyStore.Stored.Destroyed
    )
    // A write after the destroy is refused: the tombstone, never a new key.
    assertEquals(
      await(s.subjectKey("brand", "player/8c1f", create = true)(key(43))),
      KeyStore.Stored.Destroyed
    )
    assertEquals(await(s.tombstone("brand", "player/8c1f")), Some(1L -> at))
    assertEquals(await(s.lastApplied()), 1L)
    assertEquals(await(s.destroy(1, "brand", "player/8c1f", at.plusSeconds(60))), at)
    // Another project's subject of the same name is untouched.
    await(s.subjectKey("other", "player/8c1f", create = true)(key(44))): Unit
    assert(
      await(s.subjectKey("other", "player/8c1f", create = false)(key(0)))
        .isInstanceOf[KeyStore.Stored.Key]
    )
  }

  test("after a destroy no tuple of the table holds the key's bytes, live or dead") {
    val s      = store
    val marker = Array.tabulate[Byte](32)(i => (0x5a ^ i).toByte)
    await(s.subjectKey("brand", "player/vacuum", create = true)(marker)): Unit
    def tuples: Vector[Array[Byte]] = await(
      database.query(
        SqlFragment.raw(
          "SELECT t_data FROM generate_series(0, pg_relation_size('keyring.subject_key') / current_setting('block_size')::int - 1) AS p, " +
            "heap_page_items(get_raw_page('keyring.subject_key', p::int)) WHERE t_data IS NOT NULL"
        )
      )(_.get("t_data", classOf[Array[Byte]]))
    )
    await(database.execute(SqlFragment.raw("CREATE EXTENSION IF NOT EXISTS pageinspect"))): Unit
    def holds(t: Array[Byte]) = t.sliding(marker.length).exists(java.util.Arrays.equals(_, marker))
    assert(
      tuples.exists(holds),
      "the key was not found before the destroy: the check would prove nothing"
    )
    await(s.destroy(2, "brand", "player/vacuum", Instant.now())): Unit
    assert(!tuples.exists(holds), "a tuple still holds the destroyed key")
  }

  test("a project's KEK and lookup key are made once and read back") {
    val s     = store
    val first = await(s.kek("brand", "root:1")(key(1)))
    assertEquals(await(s.kek("brand", "root:1")(key(2))).toVector, first.toVector)
    val lookup = await(s.lookupKey("brand")(key(3)))
    assertEquals(await(s.lookupKey("brand")(key(4))).toVector, lookup.toVector)
  }
