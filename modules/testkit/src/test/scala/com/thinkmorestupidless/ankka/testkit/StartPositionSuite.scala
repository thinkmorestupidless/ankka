package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Metadata}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Where a topic source starts, over the in-memory broker: `features/topics/start-position.feature`.
 *
 * The topic holds fifty messages before the service starts, the last thirty published after
 * `Since`, each about a subject of its own, so a view's row count is the number of messages it
 * read. Cases run in order and share one service: a later case's ten more messages are the earlier
 * case's "after it starts".
 */
class StartPositionSuite extends munit.FunSuite with LogCapturing:

  import StartPositionSuite.*

  override val munitTimeout = 3.minutes

  private val broker                = InMemoryBroker()
  private val log                   = LogLines()
  private var testKit: AnkkaTestKit = null

  private def publish(body: String): Unit =
    val _ = broker.publish(
      Topic,
      serializer.toBytes(StockEvent(body, 1, "w1")),
      Metadata.empty.withSubject(body)
    )

  override def beforeAll(): Unit =
    // The background: fifty messages, twenty before `Since` and thirty after it.
    var now = Since.minusSeconds(21)
    broker.setClock(() => now)
    (1 to 50).foreach { n =>
      now = if n <= 20 then Since.minusSeconds(21 - n) else Since.plusSeconds(n - 20)
      publish(s"m$n")
    }
    broker.setClock(() => Instant.now())
    log.start()
    testKit = AnkkaTestKit.start(
      Seq(
        Earliest.descriptor,
        Latest.descriptor,
        AtSince.descriptor,
        Undeclared.descriptor,
        BeforeEverything.descriptor,
        AfterNow.descriptor,
        Recorder.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      serviceIdentity = ServiceIdentity.local("positions")
    )

  override def afterAll(): Unit =
    log.stop()
    if testKit != null then testKit.stop()

  private def rows(view: View.Companion[StockLevelsView, StockEvent, StockRow]): Long =
    testKit.service.viewClient.forView(view).count()

  private def holds(
      view: View.Companion[StockLevelsView, StockEvent, StockRow],
      expected: Long
  ): Unit =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    while rows(view) != expected && System.nanoTime() < deadline do Thread.sleep(100)
    // And stays there: a view that went on reading would pass a check that stopped at the target.
    Thread.sleep(500)
    assertEquals(rows(view), expected, view.componentId)

  test("a view starting at the earliest message holds every retained message") {
    holds(Earliest, 50)
  }

  test("a view declaring no start position starts at the earliest message") {
    holds(Undeclared, 50)
  }

  test("a view starting at a time holds every message published since that time") {
    holds(AtSince, 30)
  }

  test("a start position earlier than every retained message starts at the earliest") {
    holds(BeforeEverything, 50)
  }

  test("a topic source says in the log how it subscribed") {
    val view = log.containing("component=starts-earliest ")
    assertEquals(view.size, 1, log.containing("topic source subscribed").mkString("\n"))
    assert(
      view.head.contains(
        s"kind=view component=starts-earliest topic=$Topic " +
          s"group=ankka.local.positions.view.starts-earliest start=earliest version=1"
      ),
      view.head
    )
    val consumer = log.containing("component=records-everything ")
    assertEquals(consumer.size, 1)
    assert(
      consumer.head.contains(
        s"kind=consumer component=records-everything topic=$Topic " +
          s"group=ankka.local.positions.consumer.records-everything start=earliest version=1"
      ),
      consumer.head
    )
  }

  // ── Ten more ─────────────────────────────────────────────────────────────

  test("a view starting at the latest message holds only what is published after it starts") {
    holds(Latest, 0)
    (51 to 60).foreach(n => publish(s"m$n"))
    holds(Latest, 10)
  }

  test("a view starting at a time goes on to read what is published after it starts") {
    holds(AtSince, 40)
  }

  test("a start position later than now starts at the latest message") {
    holds(AfterNow, 10)
  }

  // ── Stopped, and started again ───────────────────────────────────────────

  test("a restarted view reads what was published while it was stopped") {
    holds(Latest, 10)
    testKit.service.terminate()
    Await.ready(testKit.service.whenTerminated, 30.seconds): Unit
    (61 to 70).foreach(n => publish(s"m$n"))
    testKit.restartService()
    // `latest` again on the way back up: had it started over rather than resumed, it would hold
    // none of these.
    holds(Latest, 20)
  }

  test("a restarted view is not delivered what it has already read") {
    holds(Earliest, 70)
    // Asserted on what was delivered, not on rows: a view that read everything again would hold
    // the same number of rows.
    val delivered = Recorder.seen.asScala.toVector
    assertEquals(delivered.size, 70, delivered.diff(delivered.distinct).toString)
    assertEquals(delivered.distinct.size, 70)
  }

object StartPositionSuite:

  val Topic: String  = "positions"
  val Since: Instant = Instant.parse("2026-10-01T12:00:00Z")

  private val serializer = Codecs.serializer[StockEvent]("stock-event")

  private def view(id: String, start: Option[StartFrom]) =
    new View.Companion[StockLevelsView, StockEvent, StockRow](
      ComponentId(id),
      ChangeSource.Topic(Topic, serializer, start),
      Codecs.serializer[StockRow]("stock-row")
    ):
      def create(ctx: ViewComponentContext) = new StockLevelsView

  val Earliest         = view("starts-earliest", Some(StartFrom.Earliest))
  val Latest           = view("starts-latest", Some(StartFrom.Latest))
  val AtSince          = view("starts-at", Some(StartFrom.At(Since)))
  val Undeclared       = view("starts-undeclared", None)
  val BeforeEverything = view("starts-before", Some(StartFrom.At(Since.minusSeconds(86_400))))
  val AfterNow = view("starts-after-now", Some(StartFrom.At(Instant.now().plusSeconds(86_400))))

  /** Every message it is handed, by subject: to tell a resumed group from one that read again. */
  final class Recording extends Consumer[StockEvent, Nothing]:
    def onMessage(event: StockEvent): Effect =
      Recorder.seen.add(event.sku): Unit
      effects.ignore()

  object Recorder
      extends Consumer.Companion[Recording, StockEvent, Nothing](
        ComponentId("records-everything"),
        ChangeSource.fromTopic(Topic, serializer, StartFrom.Earliest)
      ):
    val seen: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String]()
    def create(ctx: ConsumerContext)        = new Recording
