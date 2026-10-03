package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, Metadata}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Topic-sourced views and consumers, over the in-memory broker.
 *
 * Everything except the wire itself is real: CloudEvents subjects, decoding, view row upserts
 * against Postgres, consumer dispatch and republishing. The Kafka suite covers the wire.
 */
class TopicSourceSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private val broker                = InMemoryBroker()

  private val eventSerializer = Codecs.serializer[StockEvent]("stock-event")

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(
        StockLevels.descriptor,
        LowStockNotifier.descriptor,
        StockFanout.descriptor,
        TopiclessFanout.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    broker.clear()
    LowStockNotifier.seen.clear()

  private def rows = testKit.service.viewClient.forView(StockLevels)

  /** Publishes as a broker would: payload plus a ce-subject naming the entity. */
  private def publish(event: StockEvent): Unit =
    val _ = broker.publish(
      "stock-events",
      eventSerializer.toBytes(event),
      Metadata.empty.withSubject(event.sku)
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

  test("a topic message becomes a queryable view row") {
    publish(StockEvent("sku-1", 5, "w1"))

    val row = eventually("the row appears")(rows.get("sku-1"))
    assertEquals(row.sku, "sku-1")
    assertEquals(row.onHand, 5)
    assertEquals(row.updates, 1)
  }

  test("successive messages accumulate into the row") {
    publish(StockEvent("sku-2", 10, "w1"))
    val _ = eventually("first lands")(rows.get("sku-2"))
    publish(StockEvent("sku-2", -3, "w1"))

    val row = eventually("second lands")(rows.get("sku-2").filter(_.updates == 2))
    assertEquals(row.onHand, 7)
  }

  test("a view and a consumer on one topic each see every message") {
    publish(StockEvent("sku-3", -12, "w1"))

    val _ = eventually("the view saw it")(rows.get("sku-3"))
    val _ = eventually("the consumer saw it") {
      Option.when(LowStockNotifier.seen.asScala.exists(_.startsWith("sku-3:")))(())
    }
  }

  test("a consumer republishes to another topic") {
    publish(StockEvent("sku-4", -20, "w1"))

    val alerts = eventually("the alert is published") {
      Option(broker.publishedTo("stock-alerts")).filter(_.nonEmpty)
    }
    assert(alerts.head.text.contains("\"sku\":\"sku-4\""), alerts.head.text)
    // ce-subject is carried onward, so downstream ordering per sku survives.
    assertEquals(alerts.head.message.subject, Some("sku-4"))
  }

  test("a consumer that ignores a message publishes nothing") {
    publish(StockEvent("sku-5", 3, "w1"))

    val _ = eventually("the consumer saw it") {
      Option.when(LowStockNotifier.seen.asScala.exists(_.startsWith("sku-5:")))(())
    }
    assertEquals(broker.publishedTo("stock-alerts"), Seq.empty)
  }

  // ── Several messages for one message read ─────────────────────────────────

  /** As a broker delivers it; the result is what the consumer's handling came to. */
  private def deliver(topic: String, event: StockEvent): scala.util.Try[org.apache.pekko.Done] =
    scala.util.Try(
      scala.concurrent.Await.result(
        broker
          .publish(topic, eventSerializer.toBytes(event), Metadata.empty.withSubject(event.sku)),
        10.seconds
      )
    )

  private def lines(sku: String) =
    broker.publishedTo("fanout-lines").filter(_.message.subject.contains(sku))

  test("several messages are published in the order returned") {
    assert(deliver("fanout-events", StockEvent("fan-1", 5, "w1")).isSuccess)
    assertEquals(
      lines("fan-1").map(_.text),
      Seq(1, 2, 3).map(n => s"""{"sku":"fan-1","n":$n}""")
    )
  }

  test("a message that names a key is published under it; one that does not, under its subject") {
    assert(deliver("fanout-events", StockEvent("fan-2", 5, "w1")).isSuccess)
    assertEquals(
      lines("fan-2").map(_.message.key),
      Seq(Some("fan-2"), Some("second:fan-2"), Some("fan-2"))
    )
    // Naming a key does not change what the message is about.
    assertEquals(lines("fan-2").map(_.message.metadata.subject).distinct, Seq(Some("fan-2")))
    assertEquals(lines("fan-2").map(_.message.metadata.get("x-n")), Seq(None, None, Some("3")))
  }

  test("an empty list publishes nothing and the message is handled") {
    assert(deliver("fanout-events", StockEvent("fan-3", 0, "w1")).isSuccess)
    assertEquals(lines("fan-3"), Seq.empty)
    // Handled, not stuck: the next message about the same subject is published.
    assert(deliver("fanout-events", StockEvent("fan-3", 1, "w1")).isSuccess)
    assertEquals(lines("fan-3").size, 3)
  }

  test(
    "when the broker refuses one of several, the message is not handled, and comes again whole"
  ) {
    broker.failNext("fanout-lines", after = 1)
    val refused = deliver("fanout-events", StockEvent("fan-4", 5, "w1"))
    // The failure reaches whoever delivered the message, which is what stops a broker committing
    // its offset.
    assert(refused.failed.toOption.exists(_.isInstanceOf[InMemoryBroker.Refused]), refused.toString)
    assertEquals(lines("fan-4").map(_.text.takeRight(2)), Seq("1}", "3}"))

    // Redelivered, as a broker does with a message whose offset was not committed.
    assert(deliver("fanout-events", StockEvent("fan-4", 5, "w1")).isSuccess)
    val all = lines("fan-4").map(_.text.takeRight(2))
    assertEquals(all.count(_ == "2}"), 1, "the refused message is there now")
    assert(all.count(_ == "1}") >= 2, s"and the accepted ones were published again: $all")
  }

  test("several messages with no topic to publish to fail the message, naming the consumer") {
    val failure = deliver("topicless-events", StockEvent("fan-5", 1, "w1")).failed.get
    assert(failure.getMessage.contains("topicless-fanout"), failure.getMessage)
    assert(failure.getMessage.contains("no publish target"), failure.getMessage)
  }

  test("rows can be queried by a field, as with any view") {
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
    publish(StockEvent("sku-6", 1, "w1"))
    // Wait on the query that is asserted, not on a read by key that can land first (CLAUDE.md:
    // an `eventually` must wait for the thing it asserts) — this failed once in a full run.
    val found = eventually("the row is queryable by field") {
      Some(rows.where(jsonText("sku") ++ sql" = ${"sku-6"}")).filter(_.nonEmpty)
    }
    assertEquals(found.map(_.sku), Vector("sku-6"))
  }

  test("a message with no subject is skipped rather than retried forever") {
    // Without ce-subject there is no row key; failing would redeliver indefinitely.
    val _ = broker.publish(
      "stock-events",
      eventSerializer.toBytes(StockEvent("sku-orphan", 1, "w1")),
      Metadata.empty
    )
    Thread.sleep(1000)
    assertEquals(rows.get("sku-orphan"), None)
  }

  test("a topic component without a broker is rejected at startup") {
    // Silently never delivering is the failure mode this guards against. Starting a
    // broker-less ProjectionRuntime against a service that has a topic-sourced view
    // must fail, and say which knob is missing.
    val withoutBroker = ProjectionRuntime()

    val failure = intercept[IllegalArgumentException] {
      withoutBroker.start(testKit.service)
    }
    assert(failure.getMessage.contains("MessageSubscriber"), failure.getMessage)
    assert(failure.getMessage.contains("stock-events"), failure.getMessage)
  }

  test("fromEnv with no broker named is broker-less, and the refusal names the variable") {
    // A deployed service reaches Kafka through its descriptor's env; forgetting the variable must
    // fail the same way as forgetting the broker in code, and say which variable to set.
    val failure = intercept[IllegalArgumentException] {
      ProjectionRuntime.fromEnv(Map(ProjectionRuntime.KafkaEnvVar -> "  ")).start(testKit.service)
    }
    assert(failure.getMessage.contains("stock-events"), failure.getMessage)
    assert(failure.getMessage.contains("ANKKA_KAFKA_BOOTSTRAP_SERVERS"), failure.getMessage)
  }
