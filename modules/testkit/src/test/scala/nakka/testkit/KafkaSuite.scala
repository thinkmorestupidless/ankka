package nakka.testkit

import nakka.core.{Codecs, Metadata}
import nakka.runtime.{KafkaPublisher, ProjectionRuntime}
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The real Kafka wire.
 *
 * `TopicSourceSuite` already covers dispatch, decoding and view writes over an in-memory broker;
 * what only a broker can verify is that CloudEvents attributes survive as Kafka headers, that the
 * subject becomes the record key, and that consumer groups deliver.
 *
 * Skipped unless Docker is available.
 */
class KafkaSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private val image = DockerImageName.parse("apache/kafka:3.8.0")

  private var kafka: KafkaContainer = null
  private var testKit: NakkaTestKit = null
  private var bootstrap: String     = ""

  private val eventSerializer = Codecs.serializer[StockEvent]("stock-event")

  override def beforeAll(): Unit =
    kafka = KafkaContainer(image)
    kafka.start()
    bootstrap = kafka.getBootstrapServers

    testKit = NakkaTestKit.start(
      Seq(StockLevels.descriptor, LowStockNotifier.descriptor),
      Seq(ProjectionRuntime.withKafka(bootstrap))
    )

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    if kafka != null then kafka.stop()

  override def beforeEach(context: BeforeEach): Unit = LowStockNotifier.seen.clear()

  private def rows = testKit.service.viewClient.forView(StockLevels)

  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.service.system

  private lazy val publisher = KafkaPublisher(bootstrap)

  private def publish(event: StockEvent): Unit =
    Await.result(
      publisher.publish(
        "stock-events",
        eventSerializer.toBytes(event),
        Metadata.empty.withSubject(event.sku)
      ),
      30.seconds
    ): Unit

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(250)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("a message published to Kafka reaches a topic-sourced view") {
    publish(StockEvent("k-sku-1", 7, "w1"))

    val row = eventually("the row appears")(rows.get("k-sku-1"))
    assertEquals(row.onHand, 7)
    assertEquals(row.updates, 1)
  }

  test("successive messages for one subject accumulate in order") {
    publish(StockEvent("k-sku-2", 10, "w1"))
    publish(StockEvent("k-sku-2", -4, "w1"))

    val row = eventually("both messages land")(rows.get("k-sku-2").filter(_.updates == 2))
    // Ordering is what the subject-as-key gives us: both messages land on one partition.
    assertEquals(row.onHand, 6)
  }

  test("a consumer on the same topic also receives every message") {
    publish(StockEvent("k-sku-3", -15, "w1"))

    val _ = eventually("the consumer saw it") {
      Option.when(LowStockNotifier.seen.asScala.exists(_.startsWith("k-sku-3:")))(())
    }
    // And the view saw it too — two components, one topic, separate consumer groups.
    val _ = eventually("the view saw it")(rows.get("k-sku-3"))
  }

  test("a consumer republishes onto another Kafka topic") {
    publish(StockEvent("k-sku-4", -25, "w1"))

    // Read the alert topic back with a plain consumer, so this asserts on the wire
    // rather than on nakka's own bookkeeping. Search rather than take the first record:
    // earlier tests in this suite also breach the threshold, and the topic accumulates.
    val (key, value) = eventually("an alert for k-sku-4 is published to Kafka") {
      KafkaSuite
        .readAll(bootstrap, "stock-alerts", "assert-alerts")
        .find((_, body) => body.contains("k-sku-4"))
    }
    assert(value.contains("\"sku\":\"k-sku-4\""), value)
    // ce-subject became the record key, preserving per-sku ordering downstream.
    assertEquals(key, "k-sku-4")
  }

  test("CloudEvents attributes travel as Kafka headers") {
    publish(StockEvent("k-sku-5", 1, "w1"))

    val headers = eventually("the record is readable") {
      KafkaSuite
        .readHeaders(bootstrap, "stock-events", "assert-headers")
        .find((key, _) => key == "k-sku-5")
        .map(_._2)
    }
    assertEquals(headers.get("ce-subject"), Some("k-sku-5"))
    assertEquals(headers.get("ce-specversion"), Some("1.0"))
    assertEquals(headers.get("content-type"), Some("application/json"))
    assert(headers.contains("ce-id"), headers.toString)
    assert(headers.contains("ce-type"), headers.toString)
  }

object KafkaSuite:
  import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
  import org.apache.kafka.common.serialization.StringDeserializer
  import java.time.Duration as JDuration
  import java.util.Properties

  private def consumer(bootstrap: String, groupId: String): KafkaConsumer[String, String] =
    val properties = Properties()
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    KafkaConsumer[String, String](properties, StringDeserializer(), StringDeserializer())

  /** Every record on `topic`, as (key, value). */
  def readAll(bootstrap: String, topic: String, groupId: String): Vector[(String, String)] =
    // A fresh group each poll, so `earliest` really means from the start — a reused
    // group would commit offsets and see nothing on the next attempt.
    val client = consumer(bootstrap, s"$groupId-${java.util.UUID.randomUUID()}")
    try
      client.subscribe(java.util.List.of(topic))
      client.poll(JDuration.ofSeconds(5)).asScala.toVector.map(r => r.key -> r.value)
    finally client.close()

  /** Headers per record key. */
  def readHeaders(
      bootstrap: String,
      topic: String,
      groupId: String
  ): Vector[(String, Map[String, String])] =
    val client = consumer(bootstrap, s"$groupId-${java.util.UUID.randomUUID()}")
    try
      client.subscribe(java.util.List.of(topic))
      client
        .poll(JDuration.ofSeconds(5))
        .asScala
        .toVector
        .map { record =>
          record.key -> record
            .headers()
            .asScala
            .map(h => h.key -> String(h.value, "UTF-8"))
            .toMap
        }
    finally client.close()
