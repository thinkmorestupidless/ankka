package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.runtime.{
  InMemoryBroker,
  Observability,
  ProjectionRuntime,
  RecordedSpan,
  SpanKind,
  TraceContext,
  Traceparent
}
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, Consumer, ConsumerContext}

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Publishes what it reads, carrying a trace context of its own: which the platform replaces. */
final class ForgingRelay extends Consumer[StockEvent, LowStockAlert]:
  def onMessage(event: StockEvent): Effect =
    effects.produce(
      LowStockAlert(event.sku, event.delta),
      Metadata.empty.set(Traceparent.Name, ForgingRelay.Forged)
    )

object ForgingRelay
    extends Consumer.Companion[ForgingRelay, StockEvent, LowStockAlert](
      componentId = ComponentId("forging-relay"),
      source = ChangeSource.fromTopic("relay-events", Codecs.serializer[StockEvent]("stock-event"))
    ):
  val Forged                       = "00-11111111111111111111111111111111-2222222222222222-01"
  def create(ctx: ConsumerContext) = new ForgingRelay
  override val outputSerializer: Option[Serializer[LowStockAlert]] =
    Some(Codecs.serializer[LowStockAlert]("low-stock-alert"))
  override val produceTo: Option[String] = Some("relay-alerts")

/**
 * A message published to a topic carries the trace of what published it, and what reads the topic
 * continues it: the topic scenarios of `features/observability/trace-across-services.feature`, on
 * the in-memory broker. The consumers here are one service's; what crosses a topic is the message
 * and its metadata, which is the same between two services, and Kafka carries that metadata as
 * record headers (`KafkaSuite`).
 */
class TopicTraceSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private val broker                = InMemoryBroker()
  private val events                = Codecs.serializer[StockEvent]("stock-event")

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(
        StockLevels.descriptor,
        LowStockNotifier.descriptor,
        StockFanout.descriptor,
        ForgingRelay.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit = broker.clear()

  private def observability = Observability(testKit.service.system)

  /** A publishing consumer's span in another service: what a message arriving here names. */
  private val publisher =
    TraceContext(0x0af7651916cd43ddL, 0x02f2b9ee8a6d1c5dL, 0x00f067aa0ba902b7L)

  private def deliver(topic: String, event: StockEvent, metadata: Metadata): Unit =
    Await.result(
      broker.publish(topic, events.toBytes(event), metadata.withSubject(event.sku)),
      10.seconds
    ): Unit

  /** The spans one component recorded while `body` ran. */
  private def spansOf(component: String)(body: => Unit): Vector[RecordedSpan] =
    val before = observability.recorder.snapshot().map(_.spanId).toSet
    body
    eventually(s"a span of $component") {
      Option(
        observability.recorder
          .snapshot()
          .filterNot(s => before(s.spanId))
          .filter(s => observability.names.nameOf(s.componentRef).contains(component))
      ).filter(_.nonEmpty)
    }

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(50)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def carriedBy(topic: String): Vector[TraceContext] =
    broker
      .publishedTo(topic)
      .toVector
      .flatMap(_.message.metadata.get(Traceparent.Name))
      .flatMap(Traceparent.parse)

  test("a message published to a topic continues the trace of what published it") {
    // The reading side: the consumer's span is under the publishing consumer's span.
    val spans = spansOf("low-stock-notifier") {
      deliver(
        "stock-events",
        StockEvent("t-1", -20, "w1"),
        Metadata.empty.set(Traceparent.Name, Traceparent.render(publisher))
      )
    }
    assertEquals(spans.size, 1)
    val reader = spans.head
    assertEquals((reader.traceIdHigh, reader.traceId), (publisher.traceIdHigh, publisher.traceId))
    assertEquals(reader.parentSpanId, publisher.spanId)
    assertEquals(reader.kind, SpanKind.Consumer)
    // The publishing side: what it published names its own span, in the same trace.
    val published =
      eventually("the alert is published")(Option(carriedBy("stock-alerts")).filter(_.nonEmpty))
    assertEquals(published, Vector(TraceContext(reader.traceIdHigh, reader.traceId, reader.spanId)))
  }

  test("several messages published from one change each carry the span that published them") {
    val spans = spansOf("stock-fanout") {
      deliver("fanout-events", StockEvent("t-2", 5, "w1"), Metadata.empty)
    }
    val fanout = spans.head
    val lines =
      eventually("the lines are published")(Option(carriedBy("fanout-lines")).filter(_.size == 3))
    assertEquals(
      lines.distinct,
      Vector(TraceContext(fanout.traceIdHigh, fanout.traceId, fanout.spanId))
    )
  }

  test("a message that carries no trace context starts a new trace") {
    val spans = spansOf("low-stock-notifier")(
      deliver("stock-events", StockEvent("t-3", 1, "w1"), Metadata.empty)
    )
    assertEquals(spans.head.parentSpanId, 0L)
    assertNotEquals(spans.head.traceId, publisher.traceId)
  }

  test("a message whose trace context cannot be read starts a new trace") {
    val spans = spansOf("low-stock-notifier") {
      deliver(
        "stock-events",
        StockEvent("t-4", 1, "w1"),
        Metadata.empty.set(Traceparent.Name, "00-junk")
      )
    }
    assertEquals(spans.head.parentSpanId, 0L)
  }

  test("a trace context a handler set on a message is replaced by the platform's") {
    val spans = spansOf("forging-relay") {
      deliver("relay-events", StockEvent("t-5", 1, "w1"), Metadata.empty)
    }
    val relay = spans.head
    val published =
      eventually("the relay publishes")(Option(carriedBy("relay-alerts")).filter(_.nonEmpty))
    assertEquals(published, Vector(TraceContext(relay.traceIdHigh, relay.traceId, relay.spanId)))
  }

  test("a view reading a topic continues the trace the message carries") {
    val spans = spansOf("stock-levels") {
      deliver(
        "stock-events",
        StockEvent("t-6", 1, "w1"),
        Metadata.empty.set(Traceparent.Name, Traceparent.render(publisher))
      )
    }
    assertEquals(spans.head.parentSpanId, publisher.spanId)
    assertEquals(spans.head.traceId, publisher.traceId)
  }

  test("a message delivered twice is two spans under the same parent, in the same trace") {
    val parent = Metadata.empty.set(Traceparent.Name, Traceparent.render(publisher))
    val spans = spansOf("low-stock-notifier") {
      deliver("stock-events", StockEvent("t-7", 1, "w1"), parent)
      deliver("stock-events", StockEvent("t-7", 1, "w1"), parent)
    }
    // The in-memory broker hands a message to its readers before `publish` returns.
    val twice = spans
    assertEquals(twice.size, 2)
    assertEquals(twice.map(_.parentSpanId).distinct, Vector(publisher.spanId))
    assertEquals(twice.map(_.traceId).distinct, Vector(publisher.traceId))
  }
