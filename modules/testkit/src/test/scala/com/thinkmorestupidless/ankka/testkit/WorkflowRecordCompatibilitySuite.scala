package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{AnkkaSerializable, WorkflowRecord}
import com.thinkmorestupidless.ankka.testkit.autonomous.Fixtures
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.SerializationExtension

/**
 * Pins the stored form of a workflow's journal: the bytes of a `WorkflowRecord` of every kind.
 *
 * A `state` record gained the standing the engine stamps on it. The lines in `workflow-record.txt`
 * were written by the release before, so each one reading back here is the proof that a journal
 * written before still reads; and a stamped record read into the shape that release has is the
 * proof that it can read what this one writes.
 */
class WorkflowRecordCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val testKit = ActorTestKit(
    "workflow-record-compatibility",
    ConfigFactory
      .parseString("""
        pekko.actor.provider = local
        pekko.remote.artery.canonical.port = 0
      """)
      .withFallback(ConfigFactory.load())
  )
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val state = """{"id":"c1","note":"reserved"}""".getBytes("UTF-8")
  private val input = """{"amount":3}""".getBytes("UTF-8")

  /** One record of each kind, as the release before wrote them: no `state` record is stamped. */
  private val records: Vector[(String, WorkflowRecord)] = Vector(
    "state"      -> WorkflowRecord.stateUpdated(state),
    "transition" -> WorkflowRecord.transitioned("charge", input),
    "pause"      -> WorkflowRecord.paused("charge", 1790000000000L),
    "end"        -> WorkflowRecord.ended,
    "fail"       -> WorkflowRecord.failed("payment declined"),
    "retry"      -> WorkflowRecord.retryRecorded("charge"),
    "delete"     -> WorkflowRecord.deleted
  )

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"$b%02x").mkString
  private def unhex(text: String): Array[Byte] =
    text.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def same(a: WorkflowRecord, b: WorkflowRecord): Boolean =
    a.kind == b.kind && a.state.sameElements(b.state) && a.step == b.step &&
      a.stepInput.sameElements(b.stepInput) && a.message == b.message &&
      a.deadlineMillis == b.deadlineMillis

  private val path =
    Fixtures.repositoryRoot.resolve(
      "modules/testkit/src/test/resources/journal/workflow-record.txt"
    )

  private def pinned: Vector[(String, Array[Byte])] =
    java.nio.file.Files
      .readAllLines(path)
      .toArray
      .toVector
      .map(_.toString)
      .map(line => line.takeWhile(_ != ' ') -> unhex(line.dropWhile(_ != ' ').trim))

  test("a record of every kind encodes to its pinned form and back") {
    val generated = records
      .map((label, record) => s"$label ${hex(serialization.serialize(record).get)}")
      .mkString("", "\n", "\n")

    records.foreach { (label, record) =>
      val bytes = serialization.serialize(record).get
      val back  = serialization.deserialize(bytes, classOf[WorkflowRecord]).get
      assert(same(back, record), label)
    }

    val problems = Fixtures.check(Map(path -> generated))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("each pinned line decodes, as a journal written by an earlier release must") {
    assertEquals(pinned.map(_._1), records.map(_._1))
    pinned.zip(records).foreach { case ((label, bytes), (_, record)) =>
      val back = serialization.deserialize(bytes, classOf[WorkflowRecord]).get
      assert(same(back, record), label)
    }
  }

/**
 * `WorkflowRecord` as the release before declared it: six fields and no standing. Reading a stamped
 * record into it is what that release does with a property it does not know.
 */
final case class LegacyWorkflowRecord(
    kind: Int,
    state: Array[Byte],
    step: String,
    stepInput: Array[Byte],
    message: String,
    deadlineMillis: Long
) extends AnkkaSerializable
