package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{JournalRecord, RemoteStateRecord}
import com.thinkmorestupidless.ankka.testkit.autonomous.Fixtures
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.SerializationExtension

/**
 * Pins the stored form of an event sourced entity's journal and of a remote host's snapshot: the
 * bytes of a `JournalRecord` of every kind written before replication, and of a
 * `RemoteStateRecord`.
 *
 * The lines in `journal-record.txt` and `remote-state-record.txt` were written by the release
 * before replication (feature 051). A record that names no installation must stay byte for byte
 * what it was, so a service that never replicates writes the journal it always wrote; each line
 * reading back here is the proof that a journal written before still reads.
 */
class JournalRecordCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val testKit = ActorTestKit(
    "journal-record-compatibility",
    ConfigFactory
      .parseString("""
        pekko.actor.provider = local
        pekko.remote.artery.canonical.port = 0
      """)
      .withFallback(ConfigFactory.load())
  )
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val event = """{"type":"ItemAdded","item":"socks"}""".getBytes("UTF-8")
  private val state = """{"items":["socks"]}""".getBytes("UTF-8")

  /** One record of each kind, as the release before replication wrote them. */
  private val records: Vector[(String, JournalRecord)] = Vector(
    "domain"  -> JournalRecord.domain("cart-event", event),
    "deleted" -> JournalRecord.deleted,
    "expiry"  -> JournalRecord.expiry(1790000000000L)
  )

  private val snapshots: Vector[(String, RemoteStateRecord)] = Vector(
    "remote"         -> RemoteStateRecord("cart", state, false, 0L, 12L, 0L),
    "remote-deleted" -> RemoteStateRecord("", Array.emptyByteArray, true, 0L, 20L, 19L)
  )

  private def hex(bytes: Array[Byte]): String  = bytes.map(b => f"$b%02x").mkString
  private def unhex(text: String): Array[Byte] =
    text.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def same(a: JournalRecord, b: JournalRecord): Boolean =
    a.kind == b.kind && a.manifest == b.manifest && a.payload.sameElements(b.payload) &&
      a.expiryMillis == b.expiryMillis

  private def same(a: RemoteStateRecord, b: RemoteStateRecord): Boolean =
    a.manifest == b.manifest && a.payload.sameElements(b.payload) && a.deleted == b.deleted &&
      a.expiryMillis == b.expiryMillis && a.snapshotSeq == b.snapshotSeq &&
      a.deletionSeq == b.deletionSeq

  private val journalPath =
    Fixtures.repositoryRoot.resolve("modules/testkit/src/test/resources/journal/journal-record.txt")
  private val snapshotPath =
    Fixtures.repositoryRoot.resolve(
      "modules/testkit/src/test/resources/journal/remote-state-record.txt"
    )

  private def lines[A <: AnyRef](entries: Vector[(String, A)]): String =
    entries
      .map((label, r) => s"$label ${hex(serialization.serialize(r).get)}")
      .mkString("", "\n", "\n")

  private def pinned(path: java.nio.file.Path): Vector[(String, Array[Byte])] =
    java.nio.file.Files
      .readAllLines(path)
      .toArray
      .toVector
      .map(_.toString)
      .map(line => line.takeWhile(_ != ' ') -> unhex(line.dropWhile(_ != ' ').trim))

  test("a record of every kind written before replication encodes to its pinned form") {
    val problems =
      Fixtures.check(Map(journalPath -> lines(records), snapshotPath -> lines(snapshots)))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("each pinned journal line decodes, as a journal written by an earlier release must") {
    val read = pinned(journalPath)
    assertEquals(read.map(_._1), records.map(_._1))
    read.zip(records).foreach { case ((label, bytes), (_, record)) =>
      val back = serialization.deserialize(bytes, classOf[JournalRecord]).get
      assert(same(back, record), label)
    }
  }

  test("each pinned snapshot line decodes, as a snapshot saved by an earlier release must") {
    val read = pinned(snapshotPath)
    assertEquals(read.map(_._1), snapshots.map(_._1))
    read.zip(snapshots).foreach { case ((label, bytes), (_, record)) =>
      val back = serialization.deserialize(bytes, classOf[RemoteStateRecord]).get
      assert(same(back, record), label)
    }
  }
