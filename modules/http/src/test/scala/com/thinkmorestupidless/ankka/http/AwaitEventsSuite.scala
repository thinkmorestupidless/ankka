package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys
import com.thinkmorestupidless.ankka.sdk.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}

final case class Report(pages: Int)

final class ReportWorkflow extends Workflow[Report]:
  def emptyState: Report = Report(0)

object ReportWorkflow
    extends Workflow.Companion[ReportWorkflow, Report](
      ComponentId("report"),
      Codecs.serializer[Report]("report")
    ):
  def create(context: WorkflowContext) = new ReportWorkflow

/**
 * A wait served as events: what each event says, how often the heartbeat comes, and that it ends.
 */
class AwaitEventsSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "await-events",
      ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  /** A transport whose wait answers when the test says so. */
  private final class Waiting(answer: Future[(Array[Byte], Metadata)]) extends CallTransport:
    def askTimeout: FiniteDuration                                                    = 1.second
    def ask(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) = Future.never
    def tell(c: ComponentId, e: EntityId, message: Any): Unit                         = ()
    override def awaitEnd(c: ComponentId, e: EntityId, timeout: FiniteDuration, md: Metadata) =
      answer

  private def clients(answer: Future[(Array[Byte], Metadata)], heartbeat: FiniteDuration) =
    EndpointClients(ComponentClient(Waiting(answer)), null, heartbeat = heartbeat)

  private def events(
      answer: Future[(Array[Byte], Metadata)],
      heartbeat: FiniteDuration = 100.millis
  ) =
    clients(answer, heartbeat)
      .awaitEnd(EntityId("r1"), ReportWorkflow, 10.seconds)
      .runWith(Sink.seq)

  private val state = (
    ReportWorkflow.stateSerializer.toBytes(Report(3)),
    Metadata.empty.set(PayloadKeys.ContentType, "application/json")
  )

  test("heartbeats while the wait goes on, then the state, then the stream ends") {
    val answer = Promise[(Array[Byte], Metadata)]()
    val seen   = events(answer.future)
    Thread.sleep(450)
    answer.success(state)
    val all   = Await.result(seen, 5.seconds)
    val beats = all.takeWhile(_.name.contains("heartbeat"))
    assert(beats.size >= 3 && beats.size <= 5, all.toString)
    assertEquals(all.last.name, Some("ended"))
    assertEquals(all.last.data, """{"pages":3}""")
    assertEquals(all.count(e => SseEvent.endsAWait(e)), 1)
  }

  test("a failed workflow ends the stream with its step and reason") {
    val failure = WorkflowEnd.Failure(Some("render"), "out of paper", deleted = false)
    val all = Await.result(
      events(Future.failed(CommandError("failed", ErrorCode.WorkflowFailed, failure.details))),
      5.seconds
    )
    assertEquals(all.map(_.name), Vector(Some("failed")))
    assertEquals(
      all.head.data,
      """{"code":"WorkflowFailed","message":"failed","step":"render","reason":"out of paper","deleted":false}"""
    )
  }

  test("a wait that times out ends the stream with timed-out, naming the caller's time") {
    val all =
      Await.result(events(Future.failed(CommandError("late", ErrorCode.Timeout))), 5.seconds)
    assertEquals(all.map(_.name), Vector(Some("timed-out")))
    assertEquals(all.head.data, """{"timeout":"PT10S"}""")
  }

  test("a text state is sent as a JSON string") {
    assertEquals(
      EndpointClients
        .asJson("plain words".getBytes, Metadata.empty.set(PayloadKeys.ContentType, "text/plain")),
      "\"plain words\""
    )
  }

  test("the heartbeat is a third of the idle timeout, and twenty seconds when there is none") {
    def interval(setting: String) =
      Heartbeat.interval(ConfigFactory.parseString(s"pekko.http.server.idle-timeout = $setting"))
    assertEquals(interval("60s"), 20.seconds)
    assertEquals(interval("3s"), 1.second)
    assertEquals(interval("infinite"), 20.seconds)
    assertEquals(Heartbeat.interval(ConfigFactory.empty()), 20.seconds)
  }
