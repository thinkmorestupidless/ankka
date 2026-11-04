package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Feature 042, T051: the keyring replays both copies of the erasure log before it serves. */
class ErasureLogReplaySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def database: Database        = Database()(using kit.service.system)
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)

  private val at = Instant.parse("2026-11-04T10:00:00Z")
  private def entry(seq: Long, project: String, subject: String) =
    LogEntry(seq, project, subject, s"rid-$seq", at.plusSeconds(seq))

  private final class Copy(
      val name: String,
      var entries: Vector[LogEntry],
      var fails: Boolean = false
  ) extends LogCopy:
    def after(seq: Long): Vector[LogEntry] =
      if fails then throw IllegalStateException(s"$name is away")
      entries.filter(_.seq > seq)

  private def key(store: KeyStore, project: String, subject: String) =
    await(store.subjectKey(project, subject, create = false)(Array.fill(32)(1)))

  test(
    "a database restored from before an erasure destroys the key again, from the copy that holds it"
  ) {
    val store = KeyStore(database)
    // The key existed, then was destroyed as entry 1; then the database is restored from before.
    await(store.subjectKey("r1", "player/8c1f", create = true)(Array.fill(32)(9))): Unit
    await(store.subjectKey("r1", "player/9d2e", create = true)(Array.fill(32)(8))): Unit
    assert(key(store, "r1", "player/8c1f").isInstanceOf[KeyStore.Stored.Key])
    // The control plane's copy is behind: it lost entry 2 to its own restore; the bucket holds both.
    val table  = Copy("control plane", Vector(entry(1, "r1", "player/8c1f")))
    val bucket =
      Copy("bucket", Vector(entry(1, "r1", "player/8c1f"), entry(2, "r1", "player/9d2e")))
    val replay = ErasureLogReplay(store, Vector(table, bucket))
    assert(!replay.done)
    val report = await(replay.run())
    assert(replay.done)
    assertEquals(report.applied, 2)
    assertEquals(report.behind, Vector(ErasureLogReplay.Behind("control plane", Vector(2L))))
    assertEquals(key(store, "r1", "player/8c1f"), KeyStore.Stored.Destroyed)
    assertEquals(key(store, "r1", "player/9d2e"), KeyStore.Stored.Destroyed)
    assertEquals(await(store.tombstone("r1", "player/9d2e")).map(_._1), Some(2L))
    // A second replay applies nothing new.
    assertEquals(await(ErasureLogReplay(store, Vector(table, bucket)).run()).applied, 0)
  }

  test(
    "a copy that cannot be read keeps the keyring unready, and the replay finishes once it can"
  ) {
    await(database.execute(SqlFragment.raw("SELECT 1"))): Unit
    val store = KeyStore(database)
    val last  = await(store.lastApplied())
    await(store.subjectKey("r2", "player/1", create = true)(Array.fill(32)(7))): Unit
    val table  = Copy("control plane", Vector(entry(last + 1, "r2", "player/1")))
    val bucket = Copy("bucket", Vector.empty, fails = true)
    val replay = ErasureLogReplay(store, Vector(table, bucket))
    val first  = await(replay.run())
    assert(!first.finished)
    assertEquals(first.unread.map(_.copy), Vector("bucket"))
    assert(!replay.done)
    // Nothing was applied on half the log.
    assert(key(store, "r2", "player/1").isInstanceOf[KeyStore.Stored.Key])
    bucket.fails = false
    val second = await(replay.untilDone(50.millis))
    assert(second.finished)
    assertEquals(second.behind.map(_.copy), Vector("bucket"))
    assertEquals(key(store, "r2", "player/1"), KeyStore.Stored.Destroyed)
  }
