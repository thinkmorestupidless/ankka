package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.StateRecord
import com.thinkmorestupidless.ankka.testkit.autonomous.Fixtures
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.SerializationExtension

/**
 * Pins the stored form of a key value entity's state: the bytes of a `StateRecord` as the durable
 * state table holds them.
 *
 * Every key value entity's row is one of these. A deletion is written as a record marked deleted
 * rather than by removing the row, so the form of that record is pinned too: a runtime from before
 * that change must still read it, and one after must read every row written before.
 * `state-record.txt` holds one line per record, in hex; a change to either shows up as a diff to
 * review, not as an entity nobody can load after the next deploy.
 */
class StateRecordCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val testKit = ActorTestKit(
    "state-record-compatibility",
    ConfigFactory
      .parseString("""
        pekko.actor.provider = local
        pekko.remote.artery.canonical.port = 0
      """)
      .withFallback(ConfigFactory.load())
  )
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val state = """{"name":"Ada","visits":3}""".getBytes("UTF-8")
  private val empty = """{"name":"","visits":0}""".getBytes("UTF-8")

  private val records: Vector[(String, StateRecord)] = Vector(
    "live"     -> StateRecord("profile", state, deleted = false, expiryMillis = 0L),
    "expiring" -> StateRecord("profile", state, deleted = false, expiryMillis = 1790000000000L),
    // A deleted entity: the empty state, marked.
    "deleted" -> StateRecord("profile", empty, deleted = true, expiryMillis = 0L),
    // A deleted remote entity holds no value at all.
    "deleted-remote" -> StateRecord("", Array.emptyByteArray, deleted = true, expiryMillis = 0L)
  )

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"$b%02x").mkString
  private def unhex(text: String): Array[Byte] =
    text.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def same(a: StateRecord, b: StateRecord): Boolean =
    a.manifest == b.manifest && a.payload.sameElements(b.payload) &&
      a.deleted == b.deleted && a.expiryMillis == b.expiryMillis

  private val path =
    Fixtures.repositoryRoot.resolve("modules/testkit/src/test/resources/journal/state-record.txt")

  test("a live, an expiring and a deleted state encode to their pinned form and back") {
    val generated = records
      .map((label, record) => s"$label ${hex(serialization.serialize(record).get)}")
      .mkString("", "\n", "\n")

    records.foreach { (label, record) =>
      val bytes = serialization.serialize(record).get
      val back  = serialization.deserialize(bytes, classOf[StateRecord]).get
      assert(same(back, record), label)
    }

    val problems = Fixtures.check(Map(path -> generated))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("each pinned line decodes, as a row written by an earlier release must") {
    val lines = java.nio.file.Files.readAllLines(path).toArray.toVector.map(_.toString)
    assertEquals(lines.map(_.takeWhile(_ != ' ')), records.map(_._1))
    lines.zip(records).foreach { case (line, (label, record)) =>
      val bytes = unhex(line.dropWhile(_ != ' ').trim)
      val back  = serialization.deserialize(bytes, classOf[StateRecord]).get
      assert(same(back, record), label)
    }
  }

  test("a deleted state is told from a live one by its flag alone") {
    val (_, live)    = records.head
    val (_, deleted) = records(2)
    assert(!live.deleted && deleted.deleted)
    // What a runtime that does not look at the flag sees: a payload it can decode.
    assert(deleted.payload.nonEmpty)
  }
