package com.thinkmorestupidless.ankka.testkit.awaiting

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  EntityKeys,
  EntityProtocol,
  Observability,
  ShardingTransport,
  SpanKind,
  SpanOutcome,
  WorkflowEngine
}
import com.thinkmorestupidless.ankka.sdk.WorkflowLifecycle
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The asking side of a wait: the transport asks the workflow to hold, and asks again on "not yet"
 * and on no answer at all, until the caller's deadline. The workflow here is a scripted stand-in,
 * sharded under the transport's own type key, which answers each ask by its id as the script says.
 */
class AwaitTransportSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = scala.compiletime.uninitialized
  private val asked             = ConcurrentHashMap[String, Integer]()
  private val Component         = ComponentId("scripted-wait")

  /** What the stand-in answers the n-th ask for an id, by the id's prefix. */
  private def script(
      id: String,
      n: Int,
      replyTo: org.apache.pekko.actor.typed.ActorRef[EntityProtocol.Reply]
  ): Unit =
    val state = EntityProtocol.Succeeded("the end".getBytes, Vector.empty)
    id.takeWhile(_ != '-') match
      case "notyet"  => replyTo ! (if n < 3 then EntityProtocol.NotYet(10L) else state)
      case "dropped" => if n > 1 then replyTo ! state
      case "silent"  => ()
      case "old" =>
        replyTo ! EntityProtocol.Rejected(
          CommandError(
            WorkflowEngine.noHandler(WorkflowLifecycle.AwaitEnd, Component),
            ErrorCode.NotFound
          )
        )
      case "failed" =>
        replyTo ! EntityProtocol.WorkflowFailed(Some("margin"), "no rates", deleted = false)
      case "refused" =>
        replyTo ! EntityProtocol.Rejected(CommandError("not yours", ErrorCode.Forbidden))
      case _ => replyTo ! state

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Seq.empty)
    ClusterSharding(kit.service.system).init(
      Entity(EntityKeys.forComponent(Component)) { ctx =>
        Behaviors.receiveMessage[EntityProtocol.Command] {
          case invoke: EntityProtocol.Invoke =>
            script(ctx.entityId, asked.merge(ctx.entityId, 1, (a, b) => a + b), invoke.replyTo)
            Behaviors.same
          case _ => Behaviors.same
        }
      }
    ): Unit

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def transport(askTimeout: FiniteDuration = 2.seconds): ShardingTransport =
    given ActorSystem[?] = kit.service.system
    ShardingTransport(ClusterSharding(kit.service.system), askTimeout)

  private def await(id: String, timeout: FiniteDuration, t: ShardingTransport = transport()) =
    Try(
      Await.result(
        t.awaitEnd(Component, EntityId(id), timeout, Metadata.empty),
        timeout + 10.seconds
      )
    )

  private def failure(outcome: Try[?]): CommandError =
    outcome.failed.get match
      case e: CommandError => e
      case other           => fail(s"expected a CommandError, got $other")

  test("not yet, and not yet, and then the end: answered with the end, in one call") {
    val (bytes, _) = await("notyet-1", 10.seconds).get
    assertEquals(String(bytes), "the end")
    assertEquals(asked.get("notyet-1").intValue, 3)
  }

  test("an ask nobody answers is asked again, and the wait is answered") {
    val started    = System.nanoTime()
    val (bytes, _) = await("dropped-1", 20.seconds).get
    assertEquals(String(bytes), "the end")
    assertEquals(asked.get("dropped-1").intValue, 2)
    assert((System.nanoTime() - started).nanos < 5.seconds, "one ask timeout, then the answer")
  }

  test("a wait nobody answers ends at the caller's deadline, timed out, not an ask later") {
    val started = System.nanoTime()
    val error   = failure(await("silent-1", 3.seconds))
    val took    = (System.nanoTime() - started).nanos
    assertEquals(error.code, ErrorCode.Timeout, error.message)
    assert(took >= 2900.millis && took < 4.seconds, s"took $took")
    assert(asked.get("silent-1").intValue >= 2, "asked again within the deadline")
  }

  test("a failed workflow's end is a WorkflowFailed error naming the step and the reason") {
    val error = failure(await("failed-1", 5.seconds))
    assertEquals(error.code, ErrorCode.WorkflowFailed)
    assertEquals(
      WorkflowEnd.failure(error),
      Some(WorkflowEnd.Failure(Some("margin"), "no rates", deleted = false))
    )
    assertEquals(error.message, "workflow scripted-wait 'failed-1' failed: no rates")
  }

  test("an instance from before waiting is reported as such, at once") {
    val started = System.nanoTime()
    val error   = failure(await("old-1", 10.seconds))
    assertEquals(error.code, ErrorCode.Unavailable)
    assert(error.message.contains("from before waiting"), error.message)
    assert((System.nanoTime() - started).nanos < 2.seconds, "not waited out")
  }

  test("the transport reads the very words the engine answers an unknown method with") {
    val engine = EntityProtocol.Rejected(
      CommandError(
        WorkflowEngine.noHandler(WorkflowLifecycle.AwaitEnd, Component),
        ErrorCode.NotFound
      )
    )
    assert(ShardingTransport.fromBeforeAwaiting(engine, Component))
    // The words of the release before waiting, verbatim: what an old instance actually sends.
    assertEquals(engine.message, "no handler 'ankka:await-end' on workflow 'scripted-wait'")
    val other = EntityProtocol.Rejected(
      CommandError(WorkflowEngine.noHandler("start", Component), ErrorCode.NotFound)
    )
    assert(!ShardingTransport.fromBeforeAwaiting(other, Component))
  }

  test("any other refusal passes through as itself") {
    assertEquals(failure(await("refused-1", 5.seconds)).code, ErrorCode.Forbidden)
  }

  test("a timeout of zero is refused before anything is sent") {
    assertEquals(failure(await("never-sent", Duration.Zero)).code, ErrorCode.BadRequest)
    assertEquals(asked.get("never-sent"), null)
  }

  test("a wait is one client span, recorded whole when it ends, however many asks it took") {
    val observability = Observability(kit.service.system)
    def waits = observability.recorder
      .snapshot()
      .filter(s => observability.names.nameOf(s.handlerRef).contains(WorkflowLifecycle.AwaitEnd))
    val before = waits.map(_.spanId).toSet
    await("notyet-2", 10.seconds).get: Unit
    failure(await("silent-2", 1.second)): Unit
    val after = waits.filterNot(s => before(s.spanId))
    assertEquals(after.size, 2, after.toString)
    assert(after.forall(_.kind == SpanKind.Client))
    assertEquals(after.map(_.outcome).toSet, Set(SpanOutcome.Ok, SpanOutcome.TimedOut))
    val timedOut = after.find(_.outcome == SpanOutcome.TimedOut).get
    assert(timedOut.durationNanos >= 900.millis.toNanos, s"${timedOut.durationNanos}")
  }
