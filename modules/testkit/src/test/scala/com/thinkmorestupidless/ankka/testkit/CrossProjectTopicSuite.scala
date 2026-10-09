package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.runtime.{
  InMemoryBroker,
  KafkaConnection,
  ProjectDeclarations,
  ProjectionRuntime,
  StartRefusal
}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  Consumer,
  ConsumerContext,
  Publication,
  StartFrom,
  TopicOptions,
  View,
  ViewComponentContext
}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

/**
 * Another project's topic, read and published to (feature 040), with the broker in memory. The
 * component names the project; the runtime carries the topic as `<project>/<name>`, which the
 * in-memory broker holds as it is given and a Kafka connection turns into `<project>.<name>`. This
 * project's declarations are not consulted for it, and the consumer group stays this service's own.
 * Whether the broker serves it is the broker's: `CrossProjectTopicGrantsFeatures`.
 */
class CrossProjectTopicSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val eventSerializer = Codecs.serializer[StockEvent]("stock-event")
  private val lineSerializer  = Codecs.serializer[FanLine]("fan-line")

  private val players =
    new View.Companion[StockLevelsView, StockEvent, StockRow](
      ComponentId("players"),
      ChangeSource.fromTopic("spinvibe", "casino.players", eventSerializer, StartFrom.Earliest),
      Codecs.serializer[StockRow]("stock-row")
    ):
      def create(ctx: ViewComponentContext) = new StockLevelsView

  private def notifier(broker: Option[String] = None) =
    new Consumer.Companion[Relay, StockEvent, FanLine](
      ComponentId("notifier"),
      ChangeSource.fromTopic("events", eventSerializer, StartFrom.Earliest)
    ):
      def create(ctx: ConsumerContext)                           = new Relay
      override val outputSerializer: Option[Serializer[FanLine]] = Some(lineSerializer)
      override def produces: Option[Publication] =
        if broker.isEmpty then produceTo("spinvibe", "payments.deposits")
        else Some(Publication("payments.deposits", broker = broker, project = Some("spinvibe")))

  /** The project's declarations, which hold neither topic: another project's are not its own. */
  private val declared = ProjectDeclarations(
    "payments",
    Map.empty,
    Map("legacy" -> ProjectDeclarations.Broker("legacy", "legacy:9093", "certificate"))
  )

  private val broker                      = InMemoryBroker()
  private val terminationLog              = Files.createTempFile("termination", ".log")
  private var hadProperty: Option[String] = None
  private val http                        = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    hadProperty = sys.props.get(StartRefusal.PathProperty)
    sys.props(StartRefusal.PathProperty) = terminationLog.toString

  override def afterAll(): Unit =
    hadProperty.fold(sys.props.remove(StartRefusal.PathProperty): Unit)(
      sys.props(StartRefusal.PathProperty) = _
    )

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("a view over another project's topic reads it under a group of this service's own") {
    val kit = AnkkaTestKit.start(
      Seq(players.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker).withDeclarations(declared))
    )
    try
      broker.publish(
        "spinvibe/casino.players",
        eventSerializer.toBytes(StockEvent("p-1", 3, "w1")),
        Metadata.empty.withSubject("p-1")
      ): Unit
      val groups = eventually("the view reading the topic")(
        Option(broker.positions("spinvibe/casino.players")).filter(_.values.exists(_ > 0))
      )
      assert(groups.keys.forall(g => g.contains("players") && !g.contains("spinvibe")), groups)

      // The topology names the topic by its project.
      val address = kit.service.observabilityAddress.getOrElse(fail("no local console endpoint"))
      val response = http.send(
        HttpRequest.newBuilder(URI.create(s"$address/observability/topology")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assert(response.body.contains("\"topic:spinvibe/casino.players\""), response.body)
    finally kit.stop()
  }

  test("a consumer publishing to another project's topic publishes to it, by its project") {
    val kit = AnkkaTestKit.start(
      Seq(notifier().descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker).withDeclarations(declared))
    )
    try
      broker.publish(
        "events",
        eventSerializer.toBytes(StockEvent("sku-1", -1, "w1")),
        Metadata.empty.withSubject("sku-1")
      ): Unit
      eventually("the notifier publishing")(
        broker.publishedTo("spinvibe/payments.deposits").headOption
      ): Unit
      assert(broker.publishedTo("payments.deposits").isEmpty)
    finally kit.stop()
  }

  test("a Kafka connection names another project's topic by that project's prefix, not its own") {
    val connection = KafkaConnection("kafka:9093", topicPrefix = "payments.")
    assertEquals(connection.qualified("spinvibe/casino.players"), "spinvibe.casino.players")
    assertEquals(connection.qualified("deposits"), "payments.deposits")
    assertEquals(KafkaConnection("localhost:9092").qualified("spinvibe/x"), "spinvibe.x")
  }

  test("a topic naming both a declared broker and a project is refused at start") {
    Files.deleteIfExists(terminationLog): Unit
    val started = Try(
      AnkkaTestKit.start(
        Seq(notifier(broker = Some("legacy")).descriptor),
        Seq(ProjectionRuntime.withBroker(broker, broker).withDeclarations(declared))
      )
    )
    started.foreach(_.stop())
    assert(started.isFailure, "the service started")
    val message = Files.readString(terminationLog)
    assert(
      message.contains(
        "'notifier' publishes to topic 'payments.deposits' of project 'spinvibe' on broker " +
          "'legacy': another project's topic is on the installation's broker"
      ),
      message
    )
  }

  test("the options a component states carry the project, and the source describes it") {
    val source = ChangeSource.fromTopic("spinvibe", "casino.players", eventSerializer)
    source match
      case t: ChangeSource.Topic[?] =>
        assertEquals(t.options, TopicOptions(project = Some("spinvibe")))
        assertEquals(t.address, "spinvibe/casino.players")
        assertEquals(t.describe, "topic(spinvibe/casino.players)")
      case other => fail(s"not a topic source: $other")
  }
