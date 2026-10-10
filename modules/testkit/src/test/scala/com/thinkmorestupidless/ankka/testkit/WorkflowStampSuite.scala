package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment, WorkflowRecord}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.serialization.SerializationExtension

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * What the engine stamps on a recorded state: where the workflow stands once the *whole* effect
 * that recorded it is applied, read back from the journal itself.
 *
 * The stamp is of the batch, not of the state: `updateState(s).thenEnd` is one effect, and a view
 * of the workflow must be told it completed, not that it was still running when `s` was recorded.
 */
class WorkflowStampSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: Duration = 3.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    super.beforeAll()
    kit = AnkkaTestKit.start(StampWorkflow.descriptor)

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  /** The workflow's journal, every record decoded as the runtime stored it. */
  private def journal(id: String): Vector[WorkflowRecord] =
    given system: ActorSystem[?] = kit.service.system
    val serialization            = SerializationExtension(system.classicSystem)
    Await.result(
      Database().query(
        SqlFragment.raw(
          s"SELECT event_payload FROM event_journal WHERE persistence_id = 'stamped|$id' ORDER BY seq_nr"
        )
      )(r =>
        serialization
          .deserialize(r.get("event_payload", classOf[Array[Byte]]), classOf[WorkflowRecord])
          .get
      ),
      10.seconds
    )

  private def start(id: String, mode: String): Unit =
    val _ = kit.componentClient.forWorkflow(EntityId(id)).call(StampWorkflow.start).invoke(mode)

  private def states(id: String): Vector[WorkflowRecord] =
    journal(id).filter(_.kind == WorkflowRecord.KindStateUpdated)

  test("a state recorded with an end is stamped completed, and the end itself is not stamped") {
    start("s-end", "end")
    val records = kit.eventually("the end is journalled")(
      Some(journal("s-end")).filter(_.exists(_.kind == WorkflowRecord.KindEnded))
    )
    val last = states("s-end").last
    assertEquals(last.standing.map(_.status), Some("Completed"))
    assert(
      records.filter(_.kind != WorkflowRecord.KindStateUpdated).forall(_.standing.isEmpty),
      "only a state record carries a standing"
    )
  }

  test("a command that records a state and moves to a step is stamped running, on that step") {
    start("s-run", "end")
    kit.eventually("the start is journalled")(Some(states("s-run")).filter(_.nonEmpty))
    val first = states("s-run").head
    assertEquals(first.standing.map(s => s.status -> s.step), Some("Running" -> "go"))
  }

  test("a step that records a state and pauses is stamped paused, naming the timeout step") {
    start("s-pause", "pause")
    kit.eventually("the pause is journalled")(
      Some(journal("s-pause")).filter(_.exists(_.kind == WorkflowRecord.KindPaused))
    )
    val last = states("s-pause").last
    assertEquals(last.standing.map(s => s.status -> s.step), Some("Paused" -> "after"))
  }

  test("a step that records a state and pauses with no timeout names the step it paused after") {
    start("s-wait", "wait")
    kit.eventually("the pause is journalled")(
      Some(journal("s-wait")).filter(_.exists(_.kind == WorkflowRecord.KindPaused))
    )
    val last = states("s-wait").last
    assertEquals(last.standing.map(s => s.status -> s.step), Some("Paused" -> "go"))
  }

  test("a step that records a state and fails is stamped failed with the reason") {
    start("s-fail", "fail")
    kit.eventually("the failure is journalled")(
      Some(journal("s-fail")).filter(_.exists(_.kind == WorkflowRecord.KindFailed))
    )
    val last = states("s-fail").last
    assertEquals(
      last.standing.map(s => s.status -> s.failure),
      Some("Failed" -> "declined")
    )
  }

final case class Stamp(note: String)

final class StampWorkflow extends Workflow[Stamp]:
  def emptyState: Stamp = Stamp("")

  def start(mode: String): Effect[String] =
    effects.updateState(Stamp(mode)).transitionTo(StampWorkflow.go.ref).thenReply("started")

  def goStep: StepEffect = currentState.note match
    case "pause" =>
      stepEffects.updateState(Stamp("paused")).thenPause(1.hour, StampWorkflow.after.ref)
    case "wait" => stepEffects.updateState(Stamp("waiting")).thenPause()
    case "fail" => stepEffects.updateState(Stamp("failing")).thenFail("declined")
    case _      => stepEffects.updateState(Stamp("done")).thenEnd

  def afterStep: StepEffect = stepEffects.thenEnd

object StampWorkflow
    extends Workflow.Companion[StampWorkflow, Stamp](
      ComponentId("stamped"),
      Codecs.serializer[Stamp]("stamp")
    ):
  def create(ctx: WorkflowContext) = new StampWorkflow
  val go                           = step("go")(_.goStep)
  val after                        = step("after")(_.afterStep)
  val start                        = command("start")(_.start)
