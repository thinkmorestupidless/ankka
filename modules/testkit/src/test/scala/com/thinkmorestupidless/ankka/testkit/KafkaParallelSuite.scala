package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, ServiceIdentity, TopicSources}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  Consumer,
  ConsumerContext,
  StartFrom,
  TopicOptions
}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

import java.util.Properties
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * features/topics/parallelism.feature and the lag scenario of features/topics/status.feature
 * (feature 037), over a Kafka with four partitions: a consumer that asks for its partitions in
 * parallel handles them at once, each in order; one that does not reads one message at a time.
 */
class KafkaParallelSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 6.minutes

  private val image                 = DockerImageName.parse("apache/kafka:3.8.0")
  private var kafka: KafkaContainer = null
  private var bootstrap: String     = ""
  private val serializer            = Codecs.serializer[StockEvent]("stock-event")

  override def beforeAll(): Unit =
    kafka = KafkaContainer(image)
    kafka.start()
    bootstrap = kafka.getBootstrapServers

  override def afterAll(): Unit = if kafka != null then kafka.stop()

  /** `(partition, key, sku)` records, each produced to the partition named. */
  private def produce(topic: String, records: Seq[(Int, String, String)]): Unit =
    val properties = Properties()
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    val producer =
      KafkaProducer[String, Array[Byte]](properties, StringSerializer(), ByteArraySerializer())
    try
      records.foreach { (partition, key, sku) =>
        producer
          .send(ProducerRecord(topic, partition, key, serializer.toBytes(StockEvent(sku, 1, "w1"))))
          .get(): Unit
      }
      producer.flush()
    finally producer.close()

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  /**
   * A consumer over `topic` whose handler takes `pause` per message and records what it handled,
   * when.
   */
  private def recorder(
      topic: String,
      parallel: Boolean,
      pause: FiniteDuration,
      poison: Option[String] = None,
      gate: Option[CountDownLatch] = None
  ) =
    val handled = ConcurrentLinkedQueue[(String, Long)]()
    val companion = new Consumer.Companion[Recording, StockEvent, Nothing](
      ComponentId("recorder"),
      ChangeSource.fromTopic(
        topic,
        serializer,
        StartFrom.Earliest,
        TopicOptions(parallel = parallel)
      )
    ):
      def create(ctx: ConsumerContext) = new Recording(handled, pause, poison, gate)
    (companion, handled)

  private def start(descriptor: com.thinkmorestupidless.ankka.core.ComponentDescriptor) =
    AnkkaTestKit.start(
      Seq(descriptor),
      Seq(ProjectionRuntime.withKafka(bootstrap)),
      serviceIdentity = ServiceIdentity.deployed("shop", "intake")
    )

  test("partitions held by one instance are handled at once when asked for") {
    val topic                = KafkaSuite.createTopic(bootstrap, "parallel", partitions = 4)
    val (companion, handled) = recorder(topic, parallel = true, 1.second)
    produce(topic, (0 until 4).map(p => (p, s"k$p", s"sku-$p")))
    val kit = start(companion.descriptor)
    try
      val _     = eventually("all four are handled")(Option.when(handled.size == 4)(()))
      val times = handled.asScala.map(_._2).toVector
      assert(
        times.max - times.min < 2000,
        s"four one-second handlers took ${times.max - times.min} ms between first and last"
      )
    finally kit.stop()
  }

  test("a topic source that does not ask reads one message at a time") {
    val topic                = KafkaSuite.createTopic(bootstrap, "serial", partitions = 4)
    val (companion, handled) = recorder(topic, parallel = false, 500.millis)
    produce(topic, (0 until 4).map(p => (p, s"k$p", s"sku-$p")))
    val kit = start(companion.descriptor)
    try
      val _     = eventually("all four are handled")(Option.when(handled.size == 4)(()))
      val times = handled.asScala.map(_._2).toVector.sorted
      // One after another: the four finish at least three pauses apart, first to last.
      assert(
        times.last - times.head >= 1400,
        s"four half-second handlers finished within ${times.last - times.head} ms"
      )
    finally kit.stop()
  }

  test("messages under one key are handled in order") {
    val topic                = KafkaSuite.createTopic(bootstrap, "ordered", partitions = 4)
    val (companion, handled) = recorder(topic, parallel = true, 10.millis)
    produce(topic, (1 to 10).map(n => (1, "cart-1", s"n-$n")))
    val kit = start(companion.descriptor)
    try
      val _ = eventually("all ten are handled")(Option.when(handled.size == 10)(()))
      assertEquals(handled.asScala.map(_._1).toVector, (1 to 10).map(n => s"n-$n").toVector)
    finally kit.stop()
  }

  test("a message that cannot be handled holds its partition and no other") {
    val topic                = KafkaSuite.createTopic(bootstrap, "poison", partitions = 4)
    val (companion, handled) = recorder(topic, parallel = true, 10.millis, poison = Some("bad"))
    produce(
      topic,
      Seq((2, "k2", "bad"), (2, "k2", "after-bad"), (0, "k0", "a"), (1, "k1", "b"), (3, "k3", "d"))
    )
    val kit = start(companion.descriptor)
    try
      val _ = eventually("the other partitions are handled")(
        Option.when(Set("a", "b", "d").subsetOf(handled.asScala.map(_._1).toSet))(())
      )
      Thread.sleep(2000)
      assert(
        !handled.asScala.exists(_._1 == "after-bad"),
        "the message behind the poison was handled"
      )
      // Handed to the consumer again, until it is handled: the status names it meanwhile.
      val status = TopicSources(kit.service.system).all.find(_.componentId == "recorder").get
      assert(status.failing.exists(_.contains("poison")), status.toString)
    finally kit.stop()
  }

  // features/topics/status.feature: a service's status lists each topic source with how far behind it is
  test("a service's status says how far behind a topic source is") {
    val topic                = KafkaSuite.createTopic(bootstrap, "lag", partitions = 4)
    val gate                 = CountDownLatch(1)
    val (companion, handled) = recorder(topic, parallel = false, 1.millis, gate = Some(gate))
    produce(topic, (1 to 100).map(n => (n % 4, s"k${n % 4}", s"m-$n")))
    val kit = start(companion.descriptor)
    try
      // The handler blocks on the 41st message, so 40 are committed and 60 are held.
      val _ = eventually("forty are handled")(Option.when(handled.size == 40)(()))
      val lag = eventually("the lag is polled", within = 90.seconds) {
        TopicSources(kit.service.system).all
          .find(_.componentId == "recorder")
          .flatMap(_.lag)
          .filter(_ > 0)
      }
      assertEquals(lag, 60L)
      gate.countDown()
    finally
      gate.countDown()
      kit.stop()
  }

  // features/deploying/process-resources.feature: a service with only consumers runs without a database
  test("a service with only consumers runs without a database") {
    val topic                = KafkaSuite.createTopic(bootstrap, "nodb", partitions = 1)
    val (companion, handled) = recorder(topic, parallel = false, 1.millis)
    produce(topic, Seq((0, "k0", "only")))
    // No database at all: the one the kit would give it is pointed at a closed port, and the
    // service says it has none. It starts, and reads its topic.
    val kit = AnkkaTestKit.start(
      Seq(companion.descriptor),
      Seq(ProjectionRuntime.withKafka(bootstrap)),
      serviceIdentity = ServiceIdentity.deployed("shop", "intake"),
      settings = com.typesafe.config.ConfigFactory.parseString(
        """ankka.database = none
          |pekko.persistence.r2dbc.connection-factory { host = "127.0.0.1", port = 1 }
          |""".stripMargin
      )
    )
    try
      val _ = eventually("the message is handled")(Option.when(handled.size == 1)(()))
    finally kit.stop()
  }

/**
 * Records each message it handled and when; sleeps `pause`; fails forever on `poison`; blocks at
 * `gate` after forty.
 */
final class Recording(
    handled: ConcurrentLinkedQueue[(String, Long)],
    pause: FiniteDuration,
    poison: Option[String],
    gate: Option[CountDownLatch]
) extends Consumer[StockEvent, Nothing]:
  def onMessage(event: StockEvent): Effect =
    if poison.contains(event.sku) then throw IllegalStateException(s"poison: ${event.sku}")
    gate.foreach(g => if handled.size >= 40 then g.await(5, TimeUnit.MINUTES): Unit)
    Thread.sleep(pause.toMillis)
    handled.add(event.sku -> System.currentTimeMillis()): Unit
    effects.ignore()
