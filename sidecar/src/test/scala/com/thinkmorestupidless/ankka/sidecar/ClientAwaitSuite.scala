package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sidecar.wasm.HostImports
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters.*

final case class Brew(id: String, steps: Vector[String])

/**
 * A brew over two steps; one whose id starts `burnt` fails at `boil`, one whose id starts `slow`
 * takes two seconds to boil.
 */
final class BrewWorkflow(context: WorkflowContext) extends Workflow[Brew]:
  private val id = context.workflowId.toString

  def emptyState: Brew = Brew(id, Vector.empty)

  def start: Effect[Done] = effects.transitionTo(BrewWorkflow.boil).thenReply(Done)

  def boilStep: StepEffect =
    if id.startsWith("burnt") then throw RuntimeException("the kettle boiled dry")
    if id.startsWith("slow") then Thread.sleep(2000)
    stepEffects
      .updateState(currentState.copy(steps = currentState.steps :+ "boil"))
      .thenTransitionTo(BrewWorkflow.pour)

  def pourStep: StepEffect =
    stepEffects.updateState(currentState.copy(steps = currentState.steps :+ "pour")).thenEnd

object BrewWorkflow
    extends Workflow.Companion[BrewWorkflow, Brew](
      ComponentId("brew"),
      Codecs.serializer[Brew]("brew")
    ):
  def create(context: WorkflowContext) = new BrewWorkflow(context)
  val boil                             = step("boil")(_.boilStep)
  val pour                             = step("pour")(_.pourStep)
  val start                            = command("start")(_.start)

/**
 * A wait for a workflow's end as a process and a module make one: `ClientLogic.awaitEnd` and
 * `awaitEndStream`, which the gRPC service and the module's import delegate to, against a real
 * workflow. What crosses is the protocol's: the state as a payload, a failure as an `Error` with
 * details, a stream of heartbeats and one end.
 */
class ClientAwaitSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(BrewWorkflow.descriptor),
      // A heartbeat every half second: a third of this.
      settings = ConfigFactory.parseString("pekko.http.server.idle-timeout = 1500ms")
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def logic: ClientLogic =
    given ActorSystem[?] = testKit.service.system
    ClientLogic(testKit.service, settings, () => None)

  private def start(id: String): Unit =
    testKit.componentClient.forWorkflow(EntityId(id)).call(BrewWorkflow.start).invoke(): Unit

  private def wait(id: String, timeout: Long = 10_000L) =
    AwaitEndRequest(componentId = "brew", entityId = id, timeoutMillis = timeout)

  private def answer(request: AwaitEndRequest): InvokeReply =
    Await.result(logic.awaitEnd(request), 30.seconds)

  test("a completed workflow's state crosses as the payload its serializer wrote") {
    start("b1")
    answer(wait("b1")).result match
      case InvokeReply.Result.Reply(reply) =>
        val payload = reply.payload.get
        assertEquals(payload.manifest, "brew")
        assertEquals(payload.contentType, "application/json")
        assertEquals(
          BrewWorkflow.stateSerializer.fromBytes(payload.data.toByteArray),
          Brew("b1", Vector("boil", "pour"))
        )
      case other => fail(s"expected the state, got $other")
  }

  test("a failed workflow crosses as WORKFLOW_FAILED with the step and the reason in its details") {
    start("burnt-1")
    val error = answer(wait("burnt-1")).result.error.getOrElse(fail("expected a failure"))
    assertEquals(error.code, pb.ErrorCode.WORKFLOW_FAILED)
    assertEquals(error.details.get("step"), Some("boil"))
    assert(error.details("reason").contains("boiled dry"), error.details.toString)
    // And back again, as an SDK reads it.
    val read = Translate.fromError(error)
    assertEquals(WorkflowEnd.failure(read).flatMap(_.step), Some("boil"))
  }

  test("a timeout of zero is a bad request, and a wait not answered in time is a timeout") {
    assertEquals(answer(wait("never", 0L)).result.error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
    start("slow-1")
    assertEquals(answer(wait("slow-1", 300L)).result.error.map(_.code), Some(pb.ErrorCode.TIMEOUT))
  }

  test("as a stream: heartbeats while it waits, then the end, then nothing") {
    start("slow-2")
    val tokens = CopyOnWriteArrayList[StreamToken]()
    val done   = Promise[Unit]()
    logic.awaitEndStream(
      wait("slow-2"),
      token =>
        tokens.add(token)
        if token.token.isEnded || token.token.isFailed then done.trySuccess(()): Unit
    ): Unit
    Await.result(done.future, 30.seconds)
    Thread.sleep(1200)
    val seen  = tokens.asScala.toVector
    val beats = seen.count(_.token.isHeartbeat)
    assert(beats >= 2 && beats <= 5, s"$beats heartbeats in $seen")
    assert(seen.last.token.isEnded, seen.last.toString)
    assertEquals(
      seen.count(t => t.token.isEnded || t.token.isFailed),
      1,
      "one end, and nothing after it"
    )
    val state = BrewWorkflow.stateSerializer.fromBytes(seen.last.getEnded.data.toByteArray)
    assertEquals(state.steps, Vector("boil", "pour"))
  }

  test("as a stream, a failed workflow ends with a failed token carrying the details") {
    start("burnt-2")
    val tokens = CopyOnWriteArrayList[StreamToken]()
    val done   = Promise[Unit]()
    logic.awaitEndStream(
      wait("burnt-2"),
      t => { tokens.add(t); if t.token.isFailed then done.trySuccess(()): Unit }
    ): Unit
    Await.result(done.future, 30.seconds)
    val failed = tokens.asScala.last.getFailed
    assertEquals(failed.code, pb.ErrorCode.WORKFLOW_FAILED)
    assertEquals(failed.details.get("step"), Some("boil"))
  }

  test("a module waits through its import and is answered as a process is") {
    start("b3")
    val host = HostImports(5.seconds, 5.seconds, _ => None)
    host.bind(logic)
    val reply = InvokeReply.parseFrom(host.awaitEnd(wait("b3").toByteArray))
    assertEquals(reply.result.reply.flatMap(_.payload).map(_.manifest), Some("brew"))
  }
