package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.consumer.ConsumerRequest as PbConsumerRequest
import ankka.protocol.v1.discovery.Kind
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.timed_action.TimedActionRequest as PbTimedActionRequest
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName,
  Serializer
}
import com.thinkmorestupidless.ankka.runtime.remote.{Payload, PayloadKeys}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  ProjectionRuntime,
  TimerRuntime,
  TimerSweeper,
  ViewQueries
}
import com.thinkmorestupidless.ankka.sdk.{DeferredCall, ViewDescriptor}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * The stateless kinds and the key value host on a real journal: a remote view and consumer over a
 * remote entity's events, a remote view over a topic the consumer produces to, a remote timed
 * action fired by the sweeper, and a key value entity set, read, deleted and recovered.
 */
class RemoteProjectionSuite extends munit.FunSuite:
  import ProcessDouble.*

  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  private def countOf(row: Option[String]): Int =
    row.flatMap(r => """"count":(\d+)""".r.findFirstMatchIn(r).map(_.group(1).toInt)).getOrElse(0)

  private def attemptsOf(metadata: pb.Metadata): Int =
    metadata.entries.find(_.key == TimerSweeper.AttemptsKey).map(_.value.toInt).getOrElse(-1)

  private val recorder = ProcessDouble.recorder("conformance")
  private val rows = ViewOf(
    "recorder-rows",
    Some((Kind.EVENT_SOURCED_ENTITY, "conformance")),
    None,
    (row, event, _) =>
      if event.contains("\"silent\":true") then ViewAnswer.DeleteRow
      else ViewAnswer.UpdateRow(s"""{"count":${countOf(row) + 1}}"""),
    // A deleted source leaves a tombstone: the row stays, marked, as an order history wants.
    onDelete = row => ViewAnswer.UpdateRow(s"""{"count":${countOf(row)},"deleted":true}""")
  )
  private val notifier = ConsumerOf(
    "notifier",
    Some((Kind.EVENT_SOURCED_ENTITY, "conformance")),
    None,
    Some("notified"),
    (event, _) => ConsumerAnswer.Produce(event)
  )
  private val notifiedRows = ViewOf(
    "notified-rows",
    None,
    Some("notified"),
    (row, _, _) => ViewAnswer.UpdateRow(s"""{"count":${countOf(row) + 1}}""")
  )
  private val ticker = Action(
    "ticker",
    Map(
      "tick"  -> ((_, _) => Right(())),
      "flaky" -> ((_, metadata) => if attemptsOf(metadata) == 0 then Left("not yet") else Right(()))
    )
  )

  private val broker                  = InMemoryBroker()
  private val projections             = ProjectionRuntime.withBroker(broker, broker)
  private val timers                  = TimerRuntime(200.millis)
  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(
      DoubleSpec(
        entities = Vector(recorder),
        keyValues = Vector(profile()),
        views = Vector(rows, notifiedRows),
        consumers = Vector(notifier),
        actions = Vector(ticker)
      )
    )
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    val conversation = GrpcConversation(channel, settings)
    val descriptors =
      Discovery.validate(double.toSpec).fold(p => fail(p.mkString("; ")), _.descriptors)
    kit = AnkkaTestKit.start(
      descriptors,
      Seq(projections, timers),
      60.seconds,
      _.withConversation(conversation)
    )

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  private def ask(component: String, id: String, name: String, input: String): Future[String] =
    kit.componentClient.transportRef
      .ask(
        ComponentId(component),
        EntityId(id),
        MethodName(name),
        input.getBytes,
        Metadata.empty
          .set(PayloadKeys.Manifest, "string")
          .set(PayloadKeys.ContentType, Payload.Text)
      )
      .map(bytes => String(bytes))

  private def invoke(
      component: String,
      id: String,
      name: String,
      input: String = ""
  ): Either[CommandError, String] =
    Try(Await.result(ask(component, id, name, input), 10.seconds)).toEither.left.map {
      case e: CommandError => e
      case other           => CommandError(other.getMessage, ErrorCode.Internal)
    }

  private def record(id: String, input: String): Unit =
    assertEquals(invoke("conformance", id, "record", input), Right("done"))

  private def row(view: String, key: String): Option[String] =
    given ActorSystem[?] = kit.service.system
    ViewQueries(ViewDescriptor.tableFor(ComponentId(view)), Serializer.bytes, Database(), 5.seconds)
      .get(key)
      .map(bytes => String(bytes, "UTF-8"))

  private def eventually[A](timeout: FiniteDuration = 20.seconds)(check: => Option[A]): A =
    val deadline        = System.nanoTime() + timeout.toNanos
    var last: Option[A] = check
    while last.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(100)
      last = check
    last.getOrElse(fail(s"not observed within $timeout"))

  private def consumed(subject: String): Vector[String] =
    double
      .messagesOf { case r: PbConsumerRequest => r }
      .filter(
        _.metadata.exists(_.entries.exists(e => e.key == Metadata.CeSubject && e.value == subject))
      )
      .map(_.message.map(_.data.toStringUtf8).getOrElse(""))

  private def fired(timer: String): Vector[PbTimedActionRequest] =
    double
      .messagesOf { case r: PbTimedActionRequest => r }
      .filter(
        _.metadata
          .exists(_.entries.exists(e => e.key == TimerSweeper.TimerNameKey && e.value == timer))
      )

  private def schedule(
      name: String,
      method: String,
      payload: String,
      delay: FiniteDuration = 100.millis
  ): Unit =
    timers.timerScheduler.createSingleTimer(
      name,
      delay,
      DeferredCall(
        ComponentId("ticker"),
        MethodName(method),
        pb.Payload("text/plain", "string", ByteString.copyFromUtf8(payload)).toByteArray
      )
    )

  test("P1 a remote view's row is updated from the current row, and queried by key") {
    record("v1", "one")
    record("v1", "two")
    val found = eventually()(row("recorder-rows", "v1").filter(_.contains("\"count\":2")))
    assertEquals(found, """{"count":2}""")
    assertEquals(row("recorder-rows", "missing"), None)
  }

  test("P2 the process deletes a row") {
    // A no-reply handler answers the caller nothing, as in-process; the event is still journaled.
    val _ = ask("conformance", "v1", "no-reply", "")
    eventually()(if row("recorder-rows", "v1").isEmpty then Some(()) else None)
  }

  test("P2b the process is told of the source's deletion and may keep the row") {
    record("v2", "one")
    eventually()(row("recorder-rows", "v2").filter(_.contains("\"count\":1")))
    assertEquals(invoke("conformance", "v2", "delete"), Right("done"))
    val tombstone = eventually()(row("recorder-rows", "v2").filter(_.contains("deleted")))
    assertEquals(tombstone, """{"count":1,"deleted":true}""")
  }

  test("P3 a remote consumer sees each entity's events in order and produces to a topic") {
    record("c1", "a")
    record("c1", "b")
    record("c1", "c")
    val seen = eventually()(Some(consumed("c1")).filter(_.sizeIs >= 3))
    assertEquals(
      seen.take(3).map(e => """"input":"(\w)"""".r.findFirstMatchIn(e).map(_.group(1))),
      Vector(Some("a"), Some("b"), Some("c"))
    )
    val published = eventually()(
      Some(broker.publishedTo("notified").filter(_.message.subject.contains("c1")))
        .filter(_.sizeIs >= 3)
    )
    assert(published.forall(_.message.metadata.get(PayloadKeys.Manifest).contains("double-out")))
    // The topic-sourced remote view counted what the remote consumer produced.
    val counted = eventually()(row("notified-rows", "c1").filter(_.contains("\"count\":3")))
    assertEquals(counted, """{"count":3}""")
  }

  test("P4 offsets survive a restart of the service") {
    kit.restartService()
    record("c1", "d")
    val seen = eventually()(Some(consumed("c1")).filter(_.exists(_.contains("\"input\":\"d\""))))
    // At-least-once: what came before may be delivered again, but never out of order.
    val letters = seen.flatMap(e => """"input":"(\w)"""".r.findFirstMatchIn(e).map(_.group(1)))
    assertEquals(letters.distinct, Vector("a", "b", "c", "d"))
    assertEquals(letters.last, "d")
  }

  test("P5 a remote timed action fires with its payload, name and attempt count, then is gone") {
    schedule("t1", "tick", "hello")
    val request = eventually()(fired("t1").headOption)
    assertEquals(request.name, "tick")
    assertEquals(request.payload.map(_.data.toStringUtf8), Some("hello"))
    assertEquals(request.payload.map(_.manifest), Some("string"))
    assertEquals(attemptsOf(request.metadata.get), 0)
    eventually()(if timers.timerScheduler.exists("t1") then None else Some(()))
  }

  test("P6 a timer due while the process is down fires after the process restarts") {
    double.stop()
    schedule("t2", "tick", "later")
    Thread.sleep(1500) // the sweeper has tried, and rescheduled with backoff
    assertEquals(fired("t2"), Vector.empty)
    double.restart()
    val request = eventually(20.seconds)(fired("t2").headOption)
    assert(attemptsOf(request.metadata.get) >= 1, s"attempts: ${request.metadata}")
    eventually()(if timers.timerScheduler.exists("t2") then None else Some(()))
  }

  test("P7 a failed timed action is retried with the attempt count incremented") {
    schedule("t3", "flaky", "x")
    val attempts = eventually(20.seconds)(Some(fired("t3")).filter(_.sizeIs >= 2))
    assertEquals(attempts.take(2).map(r => attemptsOf(r.metadata.get)), Vector(0, 1))
    eventually()(if timers.timerScheduler.exists("t3") then None else Some(()))
  }

  test("P8 a remote key value entity is set, read, recovered after a restart, and deleted") {
    assertEquals(invoke("profile", "p1", "set", "Ada"), Right("done"))
    assertEquals(invoke("profile", "p1", "get"), Right("Ada"))
    assertEquals(invoke("profile", "p1", "set", "").left.map(_.code), Left(ErrorCode.BadRequest))
    kit.restartService()
    assertEquals(invoke("profile", "p1", "get"), Right("Ada"))
    assertEquals(invoke("profile", "p1", "delete"), Right("done"))
    assertEquals(invoke("profile", "p1", "get"), Right("none"))
    kit.restartService()
    assertEquals(invoke("profile", "p1", "get"), Right("none"))
  }

  test("P9 a key value handler that throws is a fault, not a refusal, and the next command works") {
    assertEquals(invoke("profile", "p2", "set", "Bob"), Right("done"))
    assertEquals(invoke("profile", "p2", "misbehave").left.map(_.code), Left(ErrorCode.Internal))
    assertEquals(invoke("profile", "p2", "get"), Right("Bob"))
  }
