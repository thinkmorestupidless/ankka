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
    onDelete = row => ViewAnswer.UpdateRow(s"""{"count":${countOf(row)},"deleted":true}"""),
    declared = Vector(
      "at-least" ->
        (s"SELECT payload FROM ${ViewDescriptor.tableFor(ComponentId("recorder-rows"))} " +
          "WHERE (payload::jsonb->>'count')::int >= (:count)::int ORDER BY row_key")
    )
  )

  /** Keyed over the recorder's events and the profile's state: one row per source and subject. */
  private val keyedRows = KeyedViewOf(
    "keyed-rows",
    Vector((Kind.EVENT_SOURCED_ENTITY, "conformance"), (Kind.KEY_VALUE_ENTITY, "profile")),
    (source, event, metadata) =>
      val subject = subjectOf(metadata)
      Vector(s"$source:$subject" -> Some(s"""{"source":"$source","seen":${event.isDefined}}"""))
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
  // From an SDK that could not declare where a topic source starts (the double speaks 1.0), over a
  // topic that holds messages before the service starts.
  private val legacyRead = java.util.concurrent.ConcurrentLinkedQueue[String]()
  private val legacyReader = ConsumerOf(
    "legacy-reader",
    None,
    Some("legacy-topic"),
    None,
    (_, metadata) =>
      legacyRead.add(subjectOf(metadata)): Unit
      ConsumerAnswer.Done
  )
  private val log = LogLines()

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
        keyedViews = Vector(keyedRows),
        consumers = Vector(notifier, profileWatcher, fanout, oversize, legacyReader),
        actions = Vector(ticker)
      )
    )
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    conversation = GrpcConversation(channel, settings)
    val descriptors =
      Discovery
        .validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
        .fold(p => fail(p.mkString("; ")), _.descriptors)
    (1 to 3).foreach { n =>
      broker.publish(
        "legacy-topic",
        s"old-$n".getBytes,
        com.thinkmorestupidless.ankka.core.Metadata.empty.withSubject(s"old-$n")
      ): Unit
    }
    log.start()
    kit = AnkkaTestKit.start(
      descriptors,
      Seq(projections, timers),
      60.seconds,
      _.withConversation(conversation)
    )

  override def afterAll(): Unit =
    log.stop()
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
    ViewQueries(
      view,
      ViewDescriptor.tableFor(ComponentId(view)),
      Serializer.bytes,
      Database(),
      5.seconds
    )
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

  private def query(view: String, name: String, values: Map[String, String] = Map.empty) =
    given ActorSystem[?] = kit.service.system
    val settings =
      Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)
    Await.result(
      ClientLogic(kit.service, settings, () => None)
        .query(ankka.protocol.v1.client.QueryRequest(view, name, values = values)),
      30.seconds
    )

  test("P1b a process asks a view's declared query by name, with its values") {
    record("q1", "one")
    record("q1", "two")
    record("q1", "three")
    eventually()(row("recorder-rows", "q1").filter(_.contains("\"count\":3")))
    val rows = query("recorder-rows", "at-least", Map("count" -> "3")).result.rows
      .map(_.data.toStringUtf8)
      .getOrElse(fail("no rows"))
    assert(rows.contains("\"count\":3"), rows)
    assert(!rows.contains("\"count\":2"), rows)
  }

  test("P1c a query the view does not declare, or one not given its value, is refused") {
    val undeclared = query("recorder-rows", "at-most").result.error.get
    assertEquals(undeclared.code, pb.ErrorCode.NOT_FOUND)
    assert(
      undeclared.message.contains("'at-most'") && undeclared.message.contains("'recorder-rows'"),
      undeclared.message
    )
    val missing = query("recorder-rows", "at-least").result.error.get
    assertEquals(missing.code, pb.ErrorCode.BAD_REQUEST)
    assert(missing.message.contains("'count'"), missing.message)
  }

  test("P1d a keyed view in a process is sent each source's changes, and its rows are written") {
    record("k1", "one")
    eventually()(row("keyed-rows", "conformance:k1"))
    val sent = double
      .messagesOf {
        case r: ankka.protocol.v1.view.ViewRequest if r.componentId == "keyed-rows" => r
      }
    assert(sent.nonEmpty)
    assert(sent.forall(r => r.sourceId.isDefined && r.row.isEmpty), sent.toString)
    assert(sent.exists(_.sourceId.contains("conformance")))
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
      assertEquals(
        entries.get(WireProtocol.MetadataKey),
        Some(WireProtocol.Version),
        request.toString
      )
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

  test("a consumer whose service cannot declare a start position starts at the earliest message") {
    val _ = eventually()(Option.when(legacyRead.size >= 3)(()))
    assertEquals(
      scala.jdk.CollectionConverters
        .IteratorHasAsScala(legacyRead.iterator)
        .asScala
        .toVector
        .sorted,
      Vector("old-1", "old-2", "old-3")
    )
    val warning = log.containing("consumer 'legacy-reader' reads topic 'legacy-topic'", "WARN")
    assertEquals(warning.size, 1, log.lines("WARN").mkString("\n"))
    assert(warning.head.contains("declares no start position"), warning.head)
    assert(warning.head.contains("protocol 1.7 or later"), warning.head)
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

  test("P7b a remote timed action is told its due time, and a retry is told the same one") {
    def dueOf(request: PbTimedActionRequest): Option[Long] =
      request.metadata.flatMap(_.entries.find(_.key == TimerSweeper.DueKey)).map(_.value.toLong)
    val before = java.time.Instant.now().toEpochMilli
    schedule("t3b", "flaky", "x", delay = 300.millis)
    val after    = java.time.Instant.now().toEpochMilli
    val attempts = eventually(20.seconds)(Some(fired("t3b")).filter(_.sizeIs >= 2))
    val due      = dueOf(attempts.head).getOrElse(fail(s"no ${TimerSweeper.DueKey}"))
    assert(due >= before + 300 && due <= after + 300, s"due $due is not 300 ms after it was set")
    assertEquals(attempts.take(2).map(dueOf), Vector(Some(due), Some(due)))
    assertEquals(attempts.take(2).map(r => attemptsOf(r.metadata.get)), Vector(0, 1))
    eventually()(if timers.timerScheduler.exists("t3b") then None else Some(()))
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

  // ── The trace a published message carries, and continues ───────────────────

  private def observability =
    com.thinkmorestupidless.ankka.runtime.Observability(kit.service.system)

  private def spansNamed(component: String) =
    observability.recorder
      .snapshot()
      .filter(s => observability.names.nameOf(s.componentRef).contains(component))

  private def carried(message: com.thinkmorestupidless.ankka.runtime.IncomingMessage) =
    message.metadata
      .get(com.thinkmorestupidless.ankka.runtime.Traceparent.Name)
      .flatMap(com.thinkmorestupidless.ankka.runtime.Traceparent.parse)

  test(
    "P10 a remote consumer's message carries its span, and the remote view that reads it continues it"
  ) {
    record("t1", "a")
    val published = eventually()(
      broker.publishedTo("notified").find(_.message.subject.contains("t1"))
    )
    val context  = carried(published.message).getOrElse(fail("no trace context on the message"))
    val consumer = eventually()(spansNamed("notifier").find(_.spanId == context.spanId))
    assertEquals((consumer.traceIdHigh, consumer.traceId), (context.traceIdHigh, context.traceId))
    // The view reading "notified" handled that message under the consumer's span.
    val view = eventually()(spansNamed("notified-rows").find(_.parentSpanId == context.spanId))
    assertEquals(view.traceId, context.traceId)
    assertEquals(view.kind, com.thinkmorestupidless.ankka.runtime.SpanKind.Consumer)
  }

  test("P10b each of a remote consumer's several messages carries the span that published them") {
    record("f10", "a")
    val records  = eventually()(Some(fanned("f10")).filter(_.sizeIs >= 3))
    val contexts = records.map(r => carried(r.message))
    assert(contexts.forall(_.isDefined), contexts.toString)
    assertEquals(contexts.distinct.size, 1)
    assert(spansNamed("fanout").exists(_.spanId == contexts.head.get.spanId))
  }

  test("P10c a remote consumer reading a topic continues the trace its message carries") {
    val parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    val _ = Try(
      Await.result(
        broker.publish(
          "never-fed",
          "{}".getBytes,
          Metadata.empty.withSubject("t3").set("traceparent", parent)
        ),
        10.seconds
      )
    )
    val span = eventually()(
      spansNamed("oversize").find(
        _.parentSpanId == java.lang.Long.parseUnsignedLong("b7ad6b7169203331", 16)
      )
    )
    assertEquals(span.kind, com.thinkmorestupidless.ankka.runtime.SpanKind.Consumer)
  }
