package com.thinkmorestupidless.ankka.testkit.awaiting

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaSerializable,
  EntityKeys,
  EntityProtocol,
  MetaEntry,
  Observability,
  WorkflowSnapshot
}
import com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys
import com.thinkmorestupidless.ankka.sdk.WorkflowLifecycle
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.serialization.{SerializationExtension, SerializerWithStringManifest}
import org.apache.pekko.util.Timeout

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/** A snapshot as written before waiting: no failed step, no deletion flag. */
final case class SnapshotBeforeWaiting(
    state: Array[Byte],
    status: Int,
    pendingStep: String,
    pendingInput: Array[Byte],
    retries: Vector[MetaEntry],
    startedAtMillis: Long,
    pauseDeadlineMillis: Long,
    pauseOnTimeout: String,
    failure: String
) extends AnkkaSerializable

/**
 * The engine's side of a wait, asked directly as the transport asks it: the reserved method on an
 * `Invoke`, holding what the payload names. What it answers for each standing, when it answers, and
 * what it records — and what the journal's records and snapshots carry so it can answer at all.
 */
class AwaitEngineSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Seq(QuoteWorkflow.descriptor))

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def quote(id: String) = kit.componentClient.forWorkflow(EntityId(id))

  /**
   * Asks the engine to hold a wait for `hold`, as the transport does, and returns the future reply.
   */
  private def ask(id: String, hold: FiniteDuration): Future[EntityProtocol.Reply] =
    ClusterSharding(kit.service.system)
      .entityRefFor(EntityKeys.forComponent(QuoteWorkflow.componentId), EntityId(id))
      .ask[EntityProtocol.Reply](replyTo =>
        EntityProtocol.Invoke(
          WorkflowLifecycle.AwaitEnd,
          hold.toMillis.toString.getBytes(UTF_8),
          Vector.empty,
          replyTo
        )
      )(using Timeout(hold + 5.seconds))

  private def answer(id: String, hold: FiniteDuration): EntityProtocol.Reply =
    Await.result(ask(id, hold), hold + 10.seconds)

  private def lifecycle(id: String) = quote(id).lifecycle(QuoteWorkflow).invoke()

  private def ended(id: String): Unit =
    val deadline = 30.seconds.fromNow
    while !lifecycle(id).isTerminal && deadline.hasTimeLeft() do Thread.sleep(50)
    assert(lifecycle(id).isTerminal, s"$id did not end")

  private def start(id: String, script: StepScript = StepScript()): Unit =
    StepScript.set(id, script)
    quote(id).call(QuoteWorkflow.start).invoke(QuoteRequest(100)): Unit

  test("a completed workflow answers its state at once, with the state's manifest beside it") {
    start("e1")
    ended("e1")
    val started = System.nanoTime()
    answer("e1", 5.seconds) match
      case EntityProtocol.Succeeded(bytes, metadata) =>
        assert((System.nanoTime() - started).nanos < 2.seconds, "answered at once")
        val state = QuoteWorkflow.stateSerializer.fromBytes(bytes)
        assertEquals(state.steps, Vector("rates", "margin", "offer"))
        assertEquals(state.total, Some(110))
        val meta = MetaEntry.toMetadata(metadata)
        assertEquals(meta.get(PayloadKeys.Manifest), Some("quote"))
        assertEquals(meta.get(PayloadKeys.ContentType), Some("application/json"))
      case other => fail(s"expected the state, got $other")
  }

  test("a failed workflow answers the step that failed and the reason") {
    start("e2", StepScript(failing = Some("margin")))
    ended("e2")
    answer("e2", 5.seconds) match
      case EntityProtocol.WorkflowFailed(step, reason, deleted) =>
        assertEquals(step, Some("margin"))
        assert(reason.contains("no margin for e2"), reason)
        assert(!deleted)
      case other => fail(s"expected a failure, got $other")
  }

  test("a deleted workflow answers that it was deleted, and one started again does not") {
    start("e3", StepScript(durations = Map("rates" -> 30.seconds)))
    quote("e3").call(QuoteWorkflow.cancel).invoke(): Unit
    assertEquals(
      answer("e3", 5.seconds),
      EntityProtocol.WorkflowFailed(None, "the workflow was deleted", deleted = true)
    )
    StepScript.set("e3", StepScript())
    quote("e3").call(QuoteWorkflow.start).invoke(QuoteRequest(5)): Unit
    ended("e3")
    assert(answer("e3", 5.seconds).isInstanceOf[EntityProtocol.Succeeded], "the new life ended")
  }

  test("a running workflow answers nothing until it ends, then every waiter at once") {
    start("e4", StepScript(durations = Map("margin" -> 2.seconds)))
    val first  = ask("e4", 8.seconds)
    val second = ask("e4", 8.seconds)
    Thread.sleep(500)
    assert(!first.isCompleted && !second.isCompleted, "answered before the end")
    val replies = Seq(first, second).map(Await.result(_, 20.seconds))
    replies.foreach(reply => assert(reply.isInstanceOf[EntityProtocol.Succeeded], reply.toString))
    val endedAt = StepScript.finishedAt("e4", "offer").get
    assert(System.nanoTime() - endedAt < 1.second.toNanos, "answered when it ended")
  }

  test("a wait held as long as it asked is told not yet, and the workflow runs on") {
    start("e5", StepScript(durations = Map("rates" -> 3.seconds)))
    answer("e5", 200.millis) match
      case EntityProtocol.NotYet(held) => assert(held >= 150 && held < 2000, s"held $held ms")
      case other                       => fail(s"expected not yet, got $other")
    assertEquals(lifecycle("e5").status, "Running")
    ended("e5")
  }

  test("a wait is never held longer than half an ask, whatever it asks for") {
    // The test kit's ask timeout is the platform's, ten seconds: a hold of five at most.
    start("e6", StepScript(durations = Map("rates" -> 15.seconds)))
    val started = System.nanoTime()
    answer("e6", 60.seconds) match
      case EntityProtocol.NotYet(_) =>
        val took = (System.nanoTime() - started).nanos
        assert(took >= 4.seconds && took < 7.seconds, s"held $took")
      case other => fail(s"expected not yet, got $other")
  }

  test("a wait journals nothing and changes nothing the lifecycle query says") {
    start("e7", StepScript(durations = Map("rates" -> 3.seconds)))
    val before = lifecycle("e7")
    answer("e7", 100.millis): Unit
    assertEquals(lifecycle("e7"), before)
    ended("e7")
  }

  test("a wait whose payload names no hold is refused, and one for an unknown method is not") {
    assertEquals(
      Await.result(
        ClusterSharding(kit.service.system)
          .entityRefFor(EntityKeys.forComponent(QuoteWorkflow.componentId), EntityId("e8"))
          .ask[EntityProtocol.Reply](r =>
            EntityProtocol
              .Invoke(WorkflowLifecycle.AwaitEnd, "soon".getBytes(UTF_8), Vector.empty, r)
          )(using Timeout(5.seconds)),
        10.seconds
      ) match
        case rejected: EntityProtocol.Rejected => rejected.code
        case other                             => other.toString,
      "BadRequest"
    )
  }

  test(
    "a failed and a not-yet reply cross the wire, and an old snapshot reads as no step, not deleted"
  ) {
    val serialization = SerializationExtension(kit.service.system.toClassic)
    Seq(
      EntityProtocol.NotYet(1234L),
      EntityProtocol.WorkflowFailed(Some("margin"), "no rates", deleted = false),
      EntityProtocol.WorkflowFailed(None, "the workflow was deleted", deleted = true)
    ).foreach { reply =>
      val bytes = serialization.serialize(reply).get
      val back  = serialization.deserialize(bytes, reply.getClass).get
      assertEquals(back, reply)
    }

    val old = SnapshotBeforeWaiting(
      Array[Byte](1),
      4,
      "",
      Array.emptyByteArray,
      Vector.empty,
      1L,
      0L,
      "",
      "boom"
    )
    val serializer = serialization.findSerializerFor(old).asInstanceOf[SerializerWithStringManifest]
    val read = serializer
      .fromBinary(serializer.toBinary(old), classOf[WorkflowSnapshot].getName)
      .asInstanceOf[WorkflowSnapshot]
    assertEquals(read.failure, "boom")
    assert(read.failedStep == null || read.failedStep.isEmpty, s"failedStep ${read.failedStep}")
    assert(!read.deleted)
  }

  test("a wait answered is one handled call to the workflow under the wait's own name") {
    start("e9")
    ended("e9")
    val observability = Observability(kit.service.system)
    def handled = observability.calls
      .snapshot(System.currentTimeMillis())
      .pairs
      .filter(p => observability.names.nameOf(p.calleeHandler).contains(WorkflowLifecycle.AwaitEnd))
      .map(_.ok)
      .sum
    val before = handled
    answer("e9", 1.second): Unit
    assertEquals(handled, before + 1)
  }
