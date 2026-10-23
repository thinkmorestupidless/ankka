package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{EntityKeys, EntityProtocol, PauseWake}
import com.thinkmorestupidless.ankka.sdk.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Await
import scala.concurrent.duration.*

final case class NapRequest(pauseMillis: Long)
final case class Nap(id: String, pauseMillis: Long, steps: Vector[String])

/**
 * Which step ended a nap's pause, and the identity of the context the step that paused and the one
 * that ended it ran in. A workflow's context is made once per incarnation of its entity, so two
 * identities say the entity was stopped and started between them. Read by the tests instead of a
 * query, because any message to the workflow would start a stopped one and so hide what is tested.
 */
object Naps:
  private val ended  = ConcurrentHashMap[String, String]()
  private val paused = ConcurrentHashMap[String, Integer]()
  private val woken  = ConcurrentHashMap[String, Integer]()

  def pausedIn(id: String, context: WorkflowContext): Unit =
    paused.put(id, System.identityHashCode(context)): Unit

  def endedBy(id: String, how: String, context: WorkflowContext): Unit =
    woken.put(id, System.identityHashCode(context))
    ended.put(id, how): Unit

  def how(id: String): Option[String] = Option(ended.get(id))

  /** Whether the step that ended the pause ran on another incarnation than the step that paused. */
  def restartedBetween(id: String): Boolean =
    Option(paused.get(id)).zip(Option(woken.get(id))).exists((a, b) => a.intValue != b.intValue)

/**
 * A workflow that pauses for as long as it is asked to, then ends at its timeout or at `resume`.
 */
final class NapWorkflow(context: WorkflowContext) extends Workflow[Nap]:
  private val id = context.workflowId.toString

  def emptyState: Nap = Nap(id, 0L, Vector.empty)

  def start(request: NapRequest): Effect[Done] =
    effects
      .updateState(currentState.copy(pauseMillis = request.pauseMillis))
      .transitionTo(NapWorkflow.nap)
      .thenReply(Done)

  def resume: Effect[Done] = effects.transitionTo(NapWorkflow.resumed).thenReply(Done)

  def get: ReadOnlyEffect[Nap] = effects.reply(currentState)

  def napStep: StepEffect =
    Naps.pausedIn(id, context)
    stepEffects
      .updateState(currentState.copy(steps = currentState.steps :+ "nap"))
      .thenPause(currentState.pauseMillis.millis, NapWorkflow.woke)

  def wokeStep: StepEffect =
    Naps.endedBy(id, "timeout", context)
    stepEffects.updateState(currentState.copy(steps = currentState.steps :+ "woke")).thenEnd

  def resumedStep: StepEffect =
    Naps.endedBy(id, "resume", context)
    stepEffects.updateState(currentState.copy(steps = currentState.steps :+ "resumed")).thenEnd

object NapWorkflow
    extends Workflow.Companion[NapWorkflow, Nap](
      ComponentId("nap"),
      Codecs.serializer[Nap]("nap")
    ):
  given Serializer[NapRequest] = Codecs.serializer[NapRequest]("nap-request")

  def create(context: WorkflowContext) = new NapWorkflow(context)

  val nap     = step("nap")(_.napStep)
  val woke    = step("woke")(_.wokeStep)
  val resumed = step("resumed")(_.resumedStep)

  val start  = command("start")(_.start)
  val resume = command("resume")(_.resume)
  val get    = query("get")(_.get)

/**
 * A paused workflow ends its pause on time when nothing else reaches it: after sharding passivated
 * its entity, and after the service restarted. Sharding's idle timeout is a second here, so a pause
 * of a few seconds outlives the entity, as a pause of minutes does under the default two.
 */
class WorkflowPauseWakeSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = scala.compiletime.uninitialized
  private val rows              = TimerProbe()

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(NapWorkflow.descriptor),
      settings = ConfigFactory.parseString(
        "pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout = 1s"
      )
    )
    rows.bind(kit): Unit

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def nap(id: String) = kit.componentClient.forWorkflow(EntityId(id))

  private def row(id: String) =
    rows.scheduled(PauseWake.timerName(NapWorkflow.componentId, EntityId(id)))

  /** Waits for the side channel, never for the workflow: asking it would start it. */
  private def endedWithin(id: String, bound: FiniteDuration): Option[String] =
    val deadline = bound.fromNow
    while Naps.how(id).isEmpty && deadline.hasTimeLeft() do Thread.sleep(100)
    Naps.how(id)

  private def eventually(what: String, bound: FiniteDuration = 10.seconds)(
      check: => Boolean
  ): Unit =
    val deadline = bound.fromNow
    while !check && deadline.hasTimeLeft() do Thread.sleep(100)
    assert(check, what)

  test("a pause longer than the idle timeout ends on time after its entity was passivated") {
    nap("passivated").call(NapWorkflow.start).invoke(NapRequest(4_000L)): Unit
    // The pause, the idle timeout, a sweep and a margin: at main this stays empty for ever.
    assertEquals(endedWithin("passivated", 15.seconds), Some("timeout"))
    assert(
      Naps.restartedBetween("passivated"),
      "the pause ended in the incarnation that paused: the entity was never passivated, so " +
        "this test did not test passivation"
    )
    eventually("the nap ended")(nap("passivated").lifecycle(NapWorkflow).invoke().isTerminal)
    assertEquals(nap("passivated").call(NapWorkflow.get).invoke().steps, Vector("nap", "woke"))
    eventually("the wake row is gone")(row("passivated").isEmpty)
  }

  test("a pause ends on time after the service restarted during it") {
    nap("restarted").call(NapWorkflow.start).invoke(NapRequest(6_000L)): Unit
    eventually("the pause has its wake row")(row("restarted").isDefined)
    kit.restartService()
    assertEquals(endedWithin("restarted", 20.seconds), Some("timeout"))
    eventually("the nap ended")(nap("restarted").lifecycle(NapWorkflow).invoke().isTerminal)
    assertEquals(nap("restarted").call(NapWorkflow.get).invoke().steps, Vector("nap", "woke"))
  }

  test("a pause ended early by a command removes its wake row, and the deadline does nothing") {
    nap("resumed").call(NapWorkflow.start).invoke(NapRequest(8_000L)): Unit
    eventually("the pause has its wake row")(row("resumed").isDefined)
    val target = row("resumed").get.target
    assertEquals(target.componentId, NapWorkflow.componentId)
    assertEquals(target.method, PauseWake.Method)

    nap("resumed").call(NapWorkflow.resume).invoke(): Unit
    // Well before the deadline, so the row is gone because the command removed it, not because
    // the sweeper ran it.
    eventually("the wake row is gone", 3.seconds)(row("resumed").isEmpty)
    // Past the deadline: the timeout step never runs.
    Thread.sleep(8_000)
    assertEquals(Naps.how("resumed"), Some("resume"))
    assertEquals(nap("resumed").call(NapWorkflow.get).invoke().steps, Vector("nap", "resumed"))
  }

  test("a wake for another deadline than the workflow's pause is answered and changes nothing") {
    nap("stale").call(NapWorkflow.start).invoke(NapRequest(60_000L)): Unit
    eventually("the pause has its wake row")(row("stale").isDefined)
    val reply = Await.result(
      ClusterSharding(kit.service.system)
        .entityRefFor(EntityKeys.forComponent(NapWorkflow.componentId), EntityId("stale"))
        .ask[EntityProtocol.Reply](replyTo =>
          EntityProtocol.Invoke(
            PauseWake.Method,
            PauseWake.callPayload(System.currentTimeMillis() - 1_000L),
            Vector.empty,
            replyTo
          )
        )(using Timeout(10.seconds)),
      15.seconds
    )
    assert(reply.isInstanceOf[EntityProtocol.Succeeded], s"answered $reply")
    assertEquals(nap("stale").lifecycle(NapWorkflow).invoke().status, "Paused")
    assertEquals(Naps.how("stale"), None)
    assert(row("stale").isDefined, "a stale wake removed the pause's row")
  }
