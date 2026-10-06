package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.{EntityKeys, EntityProtocol, ShardingTransport}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A call lost on its way to an instance, as cluster sharding loses one during a hand-off: the
 * region of a node that is leaving drops what reached it after the hand-off began, and nobody
 * answers. A query is sent again and answered; a command is not, and times out.
 *
 * The instances here are sharded on a real cluster of one, under the transport's own type keys; the
 * first message each receives is ignored, which is what a dropped message looks like from the
 * caller's side.
 */
class QueryResendSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = scala.compiletime.uninitialized
  private val received          = ConcurrentHashMap[String, Integer]()
  private val Component         = ComponentId("loses-the-first")

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Seq.empty)
    ClusterSharding(kit.service.system).init(
      Entity(EntityKeys.forComponent(Component)) { ctx =>
        Behaviors.receiveMessage[EntityProtocol.Command] {
          case invoke: EntityProtocol.Invoke =>
            val seen = received.merge(ctx.entityId, 1, (a, b) => a + b)
            if seen > 1 && !ctx.entityId.startsWith("never") then
              invoke.replyTo ! EntityProtocol.Succeeded("answered".getBytes, Vector.empty)
            Behaviors.same
          case _ => Behaviors.same
        }
      }
    ): Unit

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def transport(askTimeout: FiniteDuration): ShardingTransport =
    given ActorSystem[?] = kit.service.system
    ShardingTransport(ClusterSharding(kit.service.system), askTimeout, resendAfter = 300.millis)

  private def query(t: ShardingTransport, id: String) =
    t.askQuery(Component, EntityId(id), MethodName("read"), Array.emptyByteArray, Metadata.empty)

  private def command(t: ShardingTransport, id: String) =
    t.ask(Component, EntityId(id), MethodName("write"), Array.emptyByteArray, Metadata.empty)

  test("a query whose first message is lost is sent again and answered") {
    val reply = Await.result(query(transport(5.seconds), "query-1"), 10.seconds)
    assertEquals(String(reply), "answered")
    assertEquals(received.get("query-1").intValue, 2)
  }

  test("a command whose first message is lost is not sent again, and times out") {
    val outcome = Try(Await.result(command(transport(2.seconds), "command-1"), 10.seconds))
    outcome.failed.get match
      case e: CommandError => assertEquals(e.code, ErrorCode.Timeout, e.message)
      case other           => fail(s"expected a timeout, got $other")
    assertEquals(received.get("command-1").intValue, 1)
  }

  test("a query nobody answers times out at the ask timeout, not one attempt later") {
    val started = System.nanoTime()
    val outcome = Try(Await.result(query(transport(2.seconds), "never-1"), 10.seconds))
    val took    = (System.nanoTime() - started).nanos
    outcome.failed.get match
      case e: CommandError => assertEquals(e.code, ErrorCode.Timeout, e.message)
      case other           => fail(s"expected a timeout, got $other")
    assert(took >= 1900.millis && took < 3.seconds, s"took $took")
    assert(received.get("never-1").intValue > 1, "the query was sent once")
  }
