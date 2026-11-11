package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.{
  LifecycleReply,
  LifecycleRequest,
  ReplicationReply,
  ReplicationRequest,
  WorkflowStatus
}
import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sidecar.conformance.ConformanceTarget
import com.thinkmorestupidless.ankka.sidecar.wasm.HostImports
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.Await
import scala.util.Try

final case class Errand(id: String, done: Vector[String])

/** Counts every handler the suite's components run, so a read can be shown to run none. */
object Handled:
  val count = AtomicInteger()

  /** The latches `held-*` workflows wait on in their step, released at the end of the suite. */
  val latches = ConcurrentHashMap[String, CountDownLatch]()

/**
 * An errand over one step, chosen by its id: `held-*` waits in `hold`, `pause-*` pauses after `go`,
 * `burnt-*` fails `go` past two retries, anything else completes.
 */
final class ErrandWorkflow(context: WorkflowContext) extends Workflow[Errand]:
  private val id = context.workflowId.toString

  def emptyState: Errand = Errand(id, Vector.empty)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .stepRecovery(ErrandWorkflow.go, RecoverStrategy.maxRetries(2))
      .build

  def start: Effect[Done] =
    Handled.count.incrementAndGet(): Unit
    if id.startsWith("held") then effects.transitionTo(ErrandWorkflow.hold).thenReply(Done)
    else effects.transitionTo(ErrandWorkflow.go).thenReply(Done)

  def remove: Effect[Done] =
    Handled.count.incrementAndGet(): Unit
    effects.delete().thenReply(Done)

  def goStep: StepEffect =
    Handled.count.incrementAndGet(): Unit
    if id.startsWith("burnt") then throw RuntimeException("the shop was shut")
    val next = stepEffects.updateState(currentState.copy(done = currentState.done :+ "go"))
    if id.startsWith("pause") then next.thenPause() else next.thenEnd

  def holdStep: StepEffect =
    Handled.latches.computeIfAbsent(id, _ => CountDownLatch(1)).await(60, TimeUnit.SECONDS): Unit
    stepEffects.updateState(currentState.copy(done = currentState.done :+ "hold")).thenEnd

object ErrandWorkflow
    extends Workflow.Companion[ErrandWorkflow, Errand](
      ComponentId("errand"),
      Codecs.serializer[Errand]("errand")
    ):
  def create(context: WorkflowContext) = new ErrandWorkflow(context)
  val go                               = step("go")(_.goStep)
  val hold                             = step("hold")(_.holdStep)
  val start                            = command("start")(_.start)
  val remove                           = command("remove")(_.remove)

final case class Tally(n: Int)
final case class Ticked(by: Int)

/** An event sourced entity, replicated or not, whose handlers count themselves. */
final class TallyEntity extends EventSourcedEntity[Tally, Ticked]:
  def emptyState: Tally                = Tally(0)
  def applyEvent(event: Ticked): Tally =
    Handled.count.incrementAndGet(): Unit
    Tally(currentState.n + event.by)
  def tick(by: Int): Effect[Tally] =
    Handled.count.incrementAndGet(): Unit
    effects.persist(Ticked(by)).thenReply(identity)
  def get(): ReadOnlyEffect[Tally] = effects.reply(currentState)

abstract class TallyCompanion(id: String, isReplicated: Boolean)
    extends EventSourcedEntity.Companion[TallyEntity, Tally, Ticked](
      componentId = ComponentId(id),
      stateSerializer = Codecs.serializer[Tally]("tally"),
      eventSerializer = Codecs.serializer[Ticked]("ticked")
    ):
  given Serializer[Tally]                        = Codecs.serializer[Tally]("tally")
  override def replicated: Boolean               = isReplicated
  def create(context: EventSourcedEntityContext) = new TallyEntity
  val tick                                       = command("tick")(_.tick)
  val get                                        = query("get")(_.get())

object Tally       extends TallyCompanion("tally", false)
object CopiedTally extends TallyCompanion("copied-tally", true)

/**
 * The two reads a process and a module make through the sidecar (protocol 1.24): a workflow's
 * standing and how a replicated entity's copy stands, through `ClientLogic`, which the gRPC service
 * and the module's imports delegate to. Each answer is held to the Scala client's read of the same
 * id; each refusal to the code the Scala client is refused with. The service runs at installation
 * `a`, beside a `b` nothing reaches, as the conformance targets do.
 */
class ClientReadsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ErrandWorkflow.descriptor, Tally.descriptor, CopiedTally.descriptor),
      configure = _.withReplicationTransport((_, _, _) => ConformanceTarget.Unreachable),
      settings = ConformanceTarget.installation
    )

  override def afterAll(): Unit =
    Handled.latches.values.forEach(_.countDown())
    if testKit != null then testKit.stop()

  private def logic: ClientLogic =
    given ActorSystem[?] = testKit.service.system
    ClientLogic(testKit.service, settings, () => None)

  private def client = testKit.componentClient

  private def start(id: String): Unit =
    client.forWorkflow(EntityId(id)).call(ErrandWorkflow.start).invoke(): Unit

  private def scala(id: String): WorkflowLifecycle =
    client.forWorkflow(EntityId(id)).lifecycle(ErrandWorkflow).invoke()

  private def standing(id: String, component: String = "errand"): LifecycleReply =
    Await.result(logic.lifecycle(LifecycleRequest(component, id)), 30.seconds)

  private def copy(id: String, component: String): ReplicationReply =
    Await.result(logic.replication(ReplicationRequest(component, id)), 30.seconds)

  /** Asks until the Scala client's read satisfies `until`, and answers it. */
  private def settled(id: String)(until: WorkflowLifecycle => Boolean): WorkflowLifecycle =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var seen     = scala(id)
    while !until(seen) && System.nanoTime() < deadline do
      Thread.sleep(100)
      seen = scala(id)
    assert(until(seen), s"$id never settled: $seen")
    seen

  /** The rpc's answer, mapped back to the runtime's value, equal to the Scala client's. */
  private def assertSame(id: String, expected: WorkflowLifecycle): Unit =
    standing(id).result match
      case LifecycleReply.Result.Lifecycle(l) =>
        val status = l.status match
          case WorkflowStatus.NOT_STARTED => "NotStarted"
          case WorkflowStatus.RUNNING     => "Running"
          case WorkflowStatus.PAUSED      => "Paused"
          case WorkflowStatus.COMPLETED   => "Completed"
          case WorkflowStatus.FAILED      => "Failed"
          case other                      => fail(s"an unexpected status $other")
        assertEquals(WorkflowLifecycle(status, l.pendingStep, l.retries, l.failure), expected)
      case other => fail(s"expected a standing, got $other")

  private def scalaRefusal(call: => Any): ErrorCode =
    Try(call).failed.get match
      case e: CommandError => e.code
      case other           => fail(s"expected a refusal, got $other")

  test("a workflow never started answers not started, as the Scala client's read does") {
    val expected = scala("never-1")
    assertEquals(expected.status, "NotStarted")
    assertSame("never-1", expected)
  }

  test("a running workflow answers running and the step it is on") {
    start("held-1")
    val expected = settled("held-1")(_.pendingStep.contains("hold"))
    assert(expected.isRunning, expected.toString)
    assertSame("held-1", expected)
    assertEquals(standing("held-1").getLifecycle.status, WorkflowStatus.RUNNING)
  }

  test("a paused workflow answers paused") {
    start("pause-1")
    val expected = settled("pause-1")(_.isPaused)
    assertSame("pause-1", expected)
  }

  test("a completed workflow answers completed") {
    start("done-1")
    val expected = settled("done-1")(_.isCompleted)
    assertSame("done-1", expected)
  }

  test("a failed workflow answers failed, the reason and the retries") {
    start("burnt-1")
    val expected = settled("burnt-1")(_.isFailed)
    assertEquals(expected.retries.get("go"), Some(2))
    assert(expected.failure.exists(_.nonEmpty), expected.toString)
    assertSame("burnt-1", expected)
    val l = standing("burnt-1").getLifecycle
    assertEquals(l.status, WorkflowStatus.FAILED)
    assertEquals(l.retries.get("go"), Some(2))
  }

  test("a workflow deleted after it ended answers as the Scala client's read does") {
    start("done-gone")
    settled("done-gone")(_.isCompleted): Unit
    client.forWorkflow(EntityId("done-gone")).call(ErrandWorkflow.remove).invoke(): Unit
    assertSame("done-gone", scala("done-gone"))
  }

  test("a replicated copy answers its home, its sorted set and its position") {
    val tick = client.forEventSourcedEntity(EntityId("c1"))
    tick.call(CopiedTally.tick).invoke(1): Unit
    tick.call(CopiedTally.tick).invoke(2): Unit
    val expected = tick.replication(CopiedTally)
    assertEquals(expected.home, Some("a"))
    copy("c1", "copied-tally").result match
      case ReplicationReply.Result.Status(s) =>
        assertEquals(s.home, expected.home)
        assertEquals(s.replicationSet, expected.replicationSet.toVector.sorted)
        assertEquals(s.pendingHandover, expected.pendingHandover)
        assertEquals(s.conflict, expected.conflict)
        assertEquals(s.position, expected.position)
      case other => fail(s"expected a status, got $other")
  }

  test("the set crosses sorted") {
    val status = ClientLogic.replicationOf(
      ReplicationStatus(Some("b"), Set("c", "a", "b"), None, None, 3L)
    )
    assertEquals(status.replicationSet, Vector("a", "b", "c"))
  }

  test("a copy read of an unreplicated entity is refused with the Scala client's code") {
    client.forEventSourcedEntity(EntityId("u1")).call(Tally.tick).invoke(1): Unit
    val expected = scalaRefusal(client.forEventSourcedEntity(EntityId("u1")).replication(Tally))
    val error    = copy("u1", "tally").result.error.getOrElse(fail("expected a refusal"))
    assertEquals(error.code, Translate.toCode(expected))
  }

  test("a standing of an entity is refused with the code the runtime refuses the same read with") {
    client.forEventSourcedEntity(EntityId("u2")).call(Tally.tick).invoke(1): Unit
    val expected = scalaRefusal(
      Await.result(
        testKit.service.componentClient.transportRef.askQuery(
          ComponentId("tally"),
          EntityId("u2"),
          WorkflowLifecycle.Method,
          Array.emptyByteArray,
          Metadata.empty
        ),
        30.seconds
      )
    )
    val error = standing("u2", "tally").result.error.getOrElse(fail("expected a refusal"))
    assertEquals(error.code, Translate.toCode(expected))
  }

  test("an id that does not parse is a bad request") {
    assertEquals(standing("", "errand").result.error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
    assertEquals(
      copy("x", "Not A Component!").result.error.map(_.code),
      Some(pb.ErrorCode.BAD_REQUEST)
    )
  }

  test("a standing the protocol has no value for is INTERNAL, naming it") {
    val refused = ClientLogic.lifecycleOf(WorkflowLifecycle.unknown).left.toOption.get
    assertEquals(refused.code, pb.ErrorCode.INTERNAL)
    assert(refused.message.contains("Unknown"), refused.message)
  }

  test("neither read runs a handler or journals a record") {
    start("done-2")
    settled("done-2")(_.isCompleted): Unit
    client.forEventSourcedEntity(EntityId("c2")).call(CopiedTally.tick).invoke(1): Unit
    // Each target is in memory, its state recovered: reading now runs nothing the recovery would.
    val before  = Handled.count.get()
    val records = journal("errand|done-2") + journal("copied-tally|c2")
    (1 to 3).foreach { _ =>
      assert(standing("done-2").result.isLifecycle)
      assert(copy("c2", "copied-tally").result.isStatus)
    }
    assertEquals(Handled.count.get(), before, "a handler ran")
    assertEquals(journal("errand|done-2") + journal("copied-tally|c2"), records)
  }

  test("a module reads through its imports and is answered as a process is") {
    start("done-3")
    settled("done-3")(_.isCompleted): Unit
    client.forEventSourcedEntity(EntityId("c3")).call(CopiedTally.tick).invoke(1): Unit
    val host = HostImports(5.seconds, 5.seconds, _ => None)
    host.bind(logic)
    val standing =
      LifecycleReply.parseFrom(host.lifecycle(LifecycleRequest("errand", "done-3").toByteArray))
    assertEquals(standing.getLifecycle.status, WorkflowStatus.COMPLETED)
    val status =
      ReplicationReply.parseFrom(
        host.replication(ReplicationRequest("copied-tally", "c3").toByteArray)
      )
    assertEquals(status.getStatus.home, Some("a"))
  }

  private def journal(persistenceId: String): Int =
    given ActorSystem[?] = testKit.service.system
    Await
      .result(
        Database().query(
          SqlFragment.raw(
            s"SELECT count(*) AS n FROM event_journal WHERE persistence_id = '$persistenceId'"
          )
        )(_.get("n", classOf[java.lang.Long]).intValue),
        10.seconds
      )
      .head
