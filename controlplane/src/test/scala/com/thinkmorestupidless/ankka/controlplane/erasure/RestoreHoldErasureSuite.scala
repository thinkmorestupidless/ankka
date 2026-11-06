package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.deploy.RestoreHold
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/**
 * Feature 042, T056: the control plane's database restored to before some erasures. Its table of
 * the erasure log lacks them and its bucket does not; held, it writes them back under their own
 * sequences before anyone can release it, and says so in the hold's listing.
 */
final class RestoreHoldErasureSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: Option[AnkkaTestKit] = None

  override def beforeAll(): Unit = kit = Some(AnkkaTestKit.start(Seq.empty))
  override def afterAll(): Unit  = kit.foreach(_.stop())

  private def service            = kit.getOrElse(fail("no kit")).service
  private def database           = Database()(using service.system)
  private given ExecutionContext = ExecutionContext.global

  private def sql(text: String): Unit =
    Await.result(database.execute(SqlFragment.raw(text)), 30.seconds): Unit

  private def seqs: Vector[Long] =
    Await.result(
      database.query(SqlFragment.raw("SELECT seq FROM erasure_log ORDER BY seq"))(
        _.get("seq", classOf[java.lang.Long]).longValue
      ),
      30.seconds
    )

  test("a restored table gets back what its bucket holds, under the same sequences, then goes on") {
    sql("DROP TABLE IF EXISTS erasure_log")
    val bucket  = MemoryErasureLogBucket()
    val log     = ErasureLog(database, bucket)
    val at      = Instant.parse("2026-11-04T10:00:00Z")
    val written =
      Vector("r1", "r2", "r3").map(rid =>
        Await.result(log.write("brand", rid, s"player/$rid", at), 30.seconds)
      )
    // The restore: a backup taken after the first entry was written.
    sql(s"DELETE FROM erasure_log WHERE seq > ${written.head.seq}")
    assertEquals(seqs, Vector(written.head.seq))

    // Held, as a control plane on a restored database starts.
    sql(
      "CREATE TABLE IF NOT EXISTS ankka_restore_marker (restored_at timestamptz NOT NULL, " +
        "target_time timestamptz NOT NULL, released_at timestamptz, released_by text)"
    )
    sql("DELETE FROM ankka_restore_marker")
    sql(s"INSERT INTO ankka_restore_marker (restored_at, target_time) VALUES (now(), '$at')")
    val plane = ErasurePlane(KeyringAdmin.off, Some(bucket))
    val hold  = new RestoreHold(_ => database, Vector(plane.reconciler))
    hold.start(service)
    assert(hold.held)
    assertEquals(seqs, written.map(_.seq))
    assertEquals(
      hold.status.reconciled,
      Vector(
        s"the erasure log: 2 entries (${written(1).seq}, ${written(2).seq}) written back from its bucket"
      )
    )
    // The next entry is never one the bucket holds.
    val next = Await.result(log.write("brand", "r4", "player/r4", at), 30.seconds)
    assert(next.seq > written.last.seq, s"${next.seq} after ${written.last.seq}")

    // Nothing lacked: said so.
    assertEquals(
      plane.reconciler(service),
      "the erasure log: the table holds every entry its bucket holds"
    )
    sql("DELETE FROM ankka_restore_marker")
  }

  test("a bucket that cannot be read is listed, for whoever releases the hold") {
    val bucket = MemoryErasureLogBucket()
    val broken = new ErasureLogBucket:
      def putOnce(name: String, bytes: Array[Byte]): Unit = bucket.putOnce(name, bytes)
      def entries() = throw IllegalStateException("the bucket is gone")
    val line = ErasurePlane(KeyringAdmin.off, Some(broken)).reconciler(service)
    assert(line.startsWith("the erasure log was not brought up to date"), line)
    assert(line.contains("the bucket is gone"), line)
  }
