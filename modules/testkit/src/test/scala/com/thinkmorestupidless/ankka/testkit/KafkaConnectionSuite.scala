package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.sdk.StartFrom
import com.thinkmorestupidless.ankka.runtime.{
  IncomingMessage,
  KafkaConnection,
  KafkaPublisher,
  KafkaSubscriber,
  TopicSubscription
}
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * How a service reaches a broker the platform names for it (feature 027): the connection the
 * environment describes, the project's prefix on every topic it hands to Kafka and on none it
 * shows, a producer made only when something is published, and a topic nobody declared waited for
 * rather than made. Against a real broker that makes no topic on use, as the installation's does.
 */
class KafkaConnectionSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var kafka: KafkaContainer        = null
  private var bootstrap: String            = ""
  private var system: ActorSystem[Nothing] = null

  override def beforeAll(): Unit =
    kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
      .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
    kafka.start()
    bootstrap = kafka.getBootstrapServers
    system = ActorSystem(Behaviors.empty, "kafka-connection")
    create("money.transactions", "money.incoming")

  override def afterAll(): Unit =
    if system != null then system.terminate()
    if kafka != null then kafka.stop()

  private given ActorSystem[?] = system

  private def admin[A](use: AdminClient => A): A =
    val p = new Properties()
    p.put("bootstrap.servers", bootstrap)
    val client = AdminClient.create(p)
    try use(client)
    finally client.close()

  private def create(names: String*): Unit =
    admin(_.createTopics(names.map(n => new NewTopic(n, 1, 1.toShort)).asJava).all().get(): Unit)

  private def readRaw(topic: String): Vector[String] =
    val p = new Properties()
    p.put("bootstrap.servers", bootstrap)
    p.put(ConsumerConfig.GROUP_ID_CONFIG, s"raw-${System.nanoTime()}")
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[ByteArrayDeserializer].getName)
    val consumer = new KafkaConsumer[String, Array[Byte]](p)
    try
      consumer.subscribe(java.util.List.of(topic))
      val deadline = System.nanoTime() + 30.seconds.toNanos
      var seen     = Vector.empty[String]
      while seen.isEmpty && System.nanoTime() < deadline do
        seen ++= consumer.poll(Duration.ofSeconds(1)).asScala.map(r => String(r.value, UTF_8))
      seen
    finally consumer.close()

  private def publishRaw(topic: String, value: String): Unit =
    val p = new Properties()
    p.put("bootstrap.servers", bootstrap)
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    val producer = new KafkaProducer[String, Array[Byte]](p)
    try producer.send(new ProducerRecord(topic, "k", value.getBytes(UTF_8))).get(): Unit
    finally producer.close()

  test(
    "the environment describes the connection the operator wrote, or the one a descriptor gave"
  ) {
    assertEquals(
      KafkaConnection.fromEnv(
        Map(
          "ANKKA_KAFKA_BOOTSTRAP_SERVERS" -> "ankka-kafka-bootstrap.ankka-broker.svc:9093",
          "ANKKA_KAFKA_TLS_DIRECTORY"     -> "/var/run/secrets/ankka/service",
          "ANKKA_KAFKA_TOPIC_PREFIX"      -> "money."
        )
      ),
      Some(
        KafkaConnection(
          "ankka-kafka-bootstrap.ankka-broker.svc:9093",
          Some("/var/run/secrets/ankka/service"),
          "money."
        )
      )
    )
    // A broker a descriptor names: plaintext and no prefix, the connection every service had.
    assertEquals(
      KafkaConnection.fromEnv(Map("ANKKA_KAFKA_BOOTSTRAP_SERVERS" -> "kafka:9092")),
      Some(KafkaConnection("kafka:9092"))
    )
    assertEquals(KafkaConnection.fromEnv(Map.empty), None)
    assertEquals(KafkaConnection("kafka:9092").properties, Map.empty)
    assertEquals(
      KafkaConnection("k:9093", Some("/certs")).properties.get("security.protocol"),
      Some("SSL")
    )
  }

  test("a consumer publishes to its project's topic by the name the descriptor declared") {
    val publisher = KafkaPublisher(KafkaConnection(bootstrap, topicPrefix = "money."))
    Await.result(
      publisher.publish("transactions", "hello".getBytes(UTF_8), Metadata.empty),
      30.seconds
    )
    assertEquals(readRaw("money.transactions"), Vector("hello"))
    publisher.close()
  }

  test("a subscription by the declared name reads the project's topic") {
    val seen = new ConcurrentLinkedQueue[String]()
    val subscriber =
      KafkaSubscriber(KafkaConnection(bootstrap, topicPrefix = "money."), 1.second, 5.seconds)
    subscriber.subscribe(
      TopicSubscription("incoming", "ankka.money.wallet.view.entries", StartFrom.Earliest),
      (m: IncomingMessage) => Future.successful { seen.add(String(m.payload, UTF_8)); Done }
    )
    publishRaw("money.incoming", "arrived")
    val deadline = System.nanoTime() + 60.seconds.toNanos
    while seen.isEmpty && System.nanoTime() < deadline do Thread.sleep(200)
    subscriber.stop()
    assertEquals(seen.asScala.toVector, Vector("arrived"))
  }

  test("a service that never publishes holds no connection to the broker") {
    val publisher = KafkaPublisher(KafkaConnection(bootstrap, topicPrefix = "money."))
    assert(!publisher.opened, "a producer was made before anything was published")
    Await.result(
      publisher.publish("transactions", "first".getBytes(UTF_8), Metadata.empty),
      30.seconds
    )
    assert(publisher.opened)
    publisher.close()
  }

  test("a consumer that publishes to a topic no descriptor declares waits for it") {
    val publisher = KafkaPublisher(KafkaConnection(bootstrap, topicPrefix = "money."))
    val refused = Try(
      Await.result(publisher.publish("entries", "early".getBytes(UTF_8), Metadata.empty), 2.minutes)
    )
    assert(refused.isFailure, "a topic nobody declared was made by publishing to it")
    assert(!admin(_.listTopics().names().get().asScala.contains("money.entries")))
    // Declared, and the next attempt is delivered.
    create("money.entries")
    Await.result(publisher.publish("entries", "later".getBytes(UTF_8), Metadata.empty), 60.seconds)
    assertEquals(readRaw("money.entries"), Vector("later"))
    publisher.close()
  }
