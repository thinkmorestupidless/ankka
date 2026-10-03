package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
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
import com.thinkmorestupidless.ankka.runtime.remote.{
  Payload,
  PayloadKeys,
  RemoteConsumer,
  RemoteConsumerDescriptor,
  RemoteProjection,
  RemoteSource,
  WireProtocol
}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  Observability,
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
class RemoteProjectionSuite extends munit.FunSuite with LogCapturing:
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
  // Several messages for one event: three, the second under its own key and the third with a
  // header; none for the input "none"; one for the deletion.
  private def subjectOf(metadata: pb.Metadata): String =
    metadata.entries.find(_.key == Metadata.CeSubject).map(_.value).getOrElse("")
  private val megabyte = "x" * (1024 * 1024)
  private val fanout = ConsumerOf(
    "fanout",
    Some((Kind.EVENT_SOURCED_ENTITY, "conformance")),
    None,
    Some("fanned"),
    (event, metadata) =>
      val subject = subjectOf(metadata)
      if !subject.startsWith("f") then ConsumerAnswer.Ignore
      else if event.contains("\"input\":\"none\"") then ConsumerAnswer.ProduceAll(Vector.empty)
      else
        ConsumerAnswer.ProduceAll(
          Vector(
            Fanned(s"""{"s":"$subject","n":1}"""),
            Fanned(s"""{"s":"$subject","n":2}""", key = Some(s"second:$subject")),
            Fanned(s"""{"s":"$subject","n":3}""", metadata = Map("x-n" -> "3"))
          )
        )
    ,
    metadata =>
      if subjectOf(metadata).startsWith("f") then
        ConsumerAnswer.ProduceAll(Vector(Fanned(s"""{"s":"${subjectOf(metadata)}","n":-1}""")))
      else ConsumerAnswer.Ignore
  )
  // Never fed by its topic; called directly, to see what a reply too large for the transport does.
  private val oversize = ConsumerOf(
    "oversize",
    None,
    Some("never-fed"),
    Some("fanned"),
    (_, _) => ConsumerAnswer.ProduceAll(Vector.fill(5)(Fanned(megabyte)))
  )
  // Over the key value entity: a row per profile, gone when the profile is, and a consumer that
  // only listens.
  private val profileRows = ViewOf(
    "profile-rows",
    Some((Kind.KEY_VALUE_ENTITY, "profile")),
    None,
    (row, _, _) => ViewAnswer.UpdateRow(s"""{"count":${countOf(row) + 1}}""")
  )
  private val profileWatcher = ConsumerOf(
    "profile-watcher",
    Some((Kind.KEY_VALUE_ENTITY, "profile")),
    None,
    None,
    (_, _) => ConsumerAnswer.Done,
    _ => ConsumerAnswer.Done
  )
  private val ticker = Action(
    "ticker",
    Map(
      "tick"  -> ((_, _) => Right(())),
      "flaky" -> ((_, metadata) => if attemptsOf(metadata) == 0 then Left("not yet") else Right(()))
    )
  )

  private val broker                         = InMemoryBroker()
  private val projections                    = ProjectionRuntime.withBroker(broker, broker)
  private val timers                         = TimerRuntime(200.millis)
  private var double: ProcessDouble          = scala.compiletime.uninitialized
  private var channel: ManagedChannel        = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit              = scala.compiletime.uninitialized
  private var conversation: GrpcConversation = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(
      DoubleSpec(
        entities = Vector(recorder),
        keyValues = Vector(profile()),
        views = Vector(rows, notifiedRows, profileRows),
        consumers = Vector(notifier, profileWatcher, fanout, oversize),
        actions = Vector(ticker)
      )
    )
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    conversation = GrpcConversation(channel, settings)
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

  /** What `notifier` was sent about one subject; other consumers read the same events. */
  private def consumed(subject: String): Vector[String] =
    double
      .messagesOf { case r: PbConsumerRequest if r.componentId == "notifier" => r }
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

  test("P3b every consumer request says what the runtime speaks, with its subject and sequence") {
    record("c2", "a")
    eventually()(Some(consumed("c2")).filter(_.nonEmpty))
    assertEquals(invoke("conformance", "c2", "delete"), Right("done"))
    val requests = eventually() {
      val forC2 = double
        .messagesOf { case r: PbConsumerRequest if r.componentId == "notifier" => r }
        .filter(
          _.metadata.exists(_.entries.exists(e => e.key == Metadata.CeSubject && e.value == "c2"))
        )
      Some(forC2).filter(_.exists(_.deleted))
    }
    assert(requests.exists(!_.deleted) && requests.exists(_.deleted), requests.toString)
    requests.foreach { request =>
      val entries = request.metadata.toList.flatMap(_.entries).map(e => e.key -> e.value).toMap
      assertEquals(entries.get(WireProtocol.MetadataKey), Some("1.3"), request.toString)
      assert(entries.get(RemoteProjection.SequenceKey).exists(_.toLong >= 1), request.toString)
    }
    // The deletion is a change after the event it follows.
    val sequences = requests.sortBy(_.deleted).map { r =>
      r.metadata.get.entries.find(_.key == RemoteProjection.SequenceKey).get.value.toLong
    }
    assert(sequences.last > sequences.head, sequences.toString)
  }

  private def fanned(subject: String) =
    broker.publishedTo("fanned").toVector.filter(_.message.metadata.subject.contains(subject))

  test("P3d a remote consumer's several messages are published in order, under their keys") {
    record("f1", "a")
    val records = eventually()(Some(fanned("f1")).filter(_.sizeIs >= 3))
    assertEquals(records.map(_.text), Vector(1, 2, 3).map(n => s"""{"s":"f1","n":$n}"""))
    assertEquals(records.map(_.message.key), Vector(Some("f1"), Some("second:f1"), Some("f1")))
    assertEquals(records.map(_.message.metadata.get("x-n")), Vector(None, None, Some("3")))
    // The manifest travels with each, as it does with a single message.
    assert(records.forall(_.message.metadata.get(PayloadKeys.Manifest).contains("double-out")))
  }

  test("P3e a remote consumer's empty list publishes nothing, and its deletion may publish too") {
    record("f2", "none")
    record("f2", "b")
    val records = eventually()(Some(fanned("f2")).filter(_.sizeIs >= 3))
    assertEquals(records.size, 3, "only the second event's messages")
    assertEquals(invoke("conformance", "f2", "delete"), Right("done"))
    val gone = eventually()(fanned("f2").find(_.text.contains("\"n\":-1")))
    assertEquals(gone.message.key, Some("f2"))
  }

  test("P3f when the broker refuses one of a remote consumer's messages, the event comes again") {
    record("f3", "a")
    eventually()(Some(fanned("f3")).filter(_.sizeIs >= 3))
    broker.failNext("fanned", after = 1)
    record("f4", "a")
    val records =
      eventually(90.seconds)(Some(fanned("f4")).filter(_.exists(_.text.contains("\"n\":2"))))
    assert(records.count(_.text.contains("\"n\":1")) >= 2, records.map(_.text).toString)
    assertEquals(
      records.takeRight(3).map(_.text),
      Vector(1, 2, 3).map(n => s"""{"s":"f4","n":$n}""")
    )
  }

  test(
    "P3g a reply too large for the transport fails the change, naming the consumer and the limit"
  ) {
    val consumer = RemoteConsumer(
      RemoteConsumerDescriptor(
        ComponentId("oversize"),
        RemoteSource.Topic("never-fed"),
        Some("fanned")
      ),
      conversation,
      Some(broker),
      Observability(kit.service.system)
    )
    val before = broker.publishedTo("fanned").size
    val failure = Try(
      Await.result(
        consumer.handle("big-1", 7L, Some(Payload(Payload.Json, "double-in", "{}".getBytes))),
        20.seconds
      )
    ).failed.get
    val message = failure.getMessage
    assert(message.contains("consumer 'oversize'"), message)
    assert(message.contains("'big-1'") && message.contains("sequence 7"), message)
    // The transport's own words carry the limit: 4 MiB.
    assert(message.contains("4194304"), message)
    assertEquals(broker.publishedTo("fanned").size, before, "nothing of it was published")
  }

  /** What `profile-watcher` was told about one profile: (deleted, sequence), in arrival order. */
  private def watched(subject: String): Vector[(Boolean, Long)] =
    double
      .messagesOf { case r: PbConsumerRequest if r.componentId == "profile-watcher" => r }
      .flatMap { r =>
        val entries = r.metadata.toList.flatMap(_.entries).map(e => e.key -> e.value).toMap
        if entries.get(Metadata.CeSubject).contains(subject) then
          Some(r.deleted -> entries(RemoteProjection.SequenceKey).toLong)
        else None
      }

  test(
    "P3c a key value entity's deletion is a change: the row goes, the consumer is told, the id goes on"
  ) {
    assertEquals(invoke("profile", "kv1", "set", "Ada"), Right("done"))
    eventually()(row("profile-rows", "kv1"))
    val (_, first) = eventually()(watched("kv1").find(!_._1))
    assert(first >= 1, s"the state arrived at revision $first")

    assertEquals(invoke("profile", "kv1", "delete"), Right("done"))
    eventually()(if row("profile-rows", "kv1").isEmpty then Some(()) else None)
    val (_, deleted) = eventually()(watched("kv1").find(_._1))
    assertEquals(deleted, first + 1, "the deletion is the revision after the state")
    assertEquals(invoke("profile", "kv1", "get"), Right("none"))

    // Created again under the same id: the revisions go on from the deletion.
    assertEquals(invoke("profile", "kv1", "set", "Grace"), Right("done"))
    val (_, again) = eventually()(watched("kv1").find((gone, at) => !gone && at > deleted))
    assertEquals(again, deleted + 1)
    eventually()(row("profile-rows", "kv1"))
    assertEquals(invoke("profile", "kv1", "get"), Right("Grace"))
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
