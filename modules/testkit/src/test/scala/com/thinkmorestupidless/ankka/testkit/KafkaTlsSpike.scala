package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.KafkaTls
import com.thinkmorestupidless.ankka.testpki.TestPki
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Whether a service's Kafka clients can present the certificate it already holds, and follow its
 * renewal (research R10 of the managed broker): the gate the rest of that feature rests on.
 *
 * A broker in a container with one TLS listener that requires a client certificate from a test
 * authority. A client configured with `KafkaTls` must round-trip a record; one whose directory
 * holds another authority's certificate must be refused; and when the files in a directory change,
 * the next connection a new client opens must present the new ones. That last is shown by swapping
 * in a certificate the broker refuses, then the good one back. Gated on `-Dankka.spikes=on`.
 *
 * sbt -Dankka.spikes=on 'testkit/testOnly *KafkaTlsSpike'
 */
class KafkaTlsSpike extends munit.FunSuite with LogCapturing:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")
  override val munitTimeout         = 5.minutes

  private val authority = TestPki.root("kafka-tls-spike")
  private val foreign   = TestPki.root("kafka-tls-spike-foreign")

  private val port: Int =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  private var broker: TlsKafka = null

  override def beforeAll(): Unit =
    val serverDir = Files.createTempDirectory("kafka-tls-server")
    val server    = authority.issue(cn = Some("broker"), dnsNames = Seq("localhost"))
    Files.writeString(serverDir.resolve("server.pem"), server.keyPem + server.certPem): Unit
    Files.writeString(serverDir.resolve("ca.crt"), authority.pem): Unit
    Files.writeString(serverDir.resolve("server.properties"), properties): Unit
    broker = TlsKafka(port)
    for name <- Seq("server.pem", "ca.crt", "server.properties") do
      broker.withCopyFileToContainer(
        MountableFile.forHostPath(serverDir.resolve(name), 0x1a4),
        s"/mnt/tls/$name"
      ): Unit
    broker.start()

  override def afterAll(): Unit = if broker != null then broker.stop()

  private def properties: String =
    s"""node.id=1
       |process.roles=broker,controller
       |controller.quorum.voters=1@localhost:9094
       |listeners=SSL://0.0.0.0:9093,INTERNAL://localhost:9092,CONTROLLER://localhost:9094
       |advertised.listeners=SSL://localhost:$port,INTERNAL://localhost:9092
       |listener.security.protocol.map=SSL:SSL,INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
       |inter.broker.listener.name=INTERNAL
       |controller.listener.names=CONTROLLER
       |listener.name.ssl.ssl.keystore.type=PEM
       |listener.name.ssl.ssl.keystore.location=/mnt/tls/server.pem
       |listener.name.ssl.ssl.truststore.type=PEM
       |listener.name.ssl.ssl.truststore.location=/mnt/tls/ca.crt
       |listener.name.ssl.ssl.client.auth=required
       |offsets.topic.replication.factor=1
       |transaction.state.log.replication.factor=1
       |transaction.state.log.min.isr=1
       |auto.create.topics.enable=true
       |log.dirs=/tmp/kraft
       |""".stripMargin

  /** A directory as cert-manager writes one: this leaf, its key, and the authority it trusts. */
  private def directory(leaf: TestPki.Leaf): Path =
    val dir = leaf.writeTo(Files.createTempDirectory("kafka-tls-client"))
    Files.writeString(dir.resolve("ca.crt"), authority.pem): Unit
    dir

  private def replace(dir: Path, leaf: TestPki.Leaf): Unit =
    leaf.writeTo(dir): Unit
    Files.writeString(dir.resolve("ca.crt"), authority.pem): Unit

  private def client(dir: Path): Properties =
    val p = new Properties()
    p.put("bootstrap.servers", s"localhost:$port")
    KafkaTls.clientProperties(dir.toString).foreach((k, v) => p.put(k, v))
    p.put(KafkaTls.ReloadConfig, "200")
    p

  /** Publishes one record with a client of its own, so every attempt opens new connections. */
  private def publish(dir: Path, topic: String, value: String): Either[Throwable, Unit] =
    val p = client(dir)
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "8000")
    p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000")
    p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000")
    val producer = new KafkaProducer[String, String](p)
    try
      Try(producer.send(new ProducerRecord(topic, "k", value)).get(15, TimeUnit.SECONDS)).toEither
        .map(_ => ())
    finally producer.close(Duration.ofSeconds(2))

  private def read(dir: Path, topic: String): Vector[String] =
    val p = client(dir)
    p.put(ConsumerConfig.GROUP_ID_CONFIG, s"spike-${System.nanoTime()}")
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    val consumer = new KafkaConsumer[String, String](p)
    try
      consumer.subscribe(java.util.List.of(topic))
      val deadline = System.nanoTime() + 30.seconds.toNanos
      var seen     = Vector.empty[String]
      while seen.isEmpty && System.nanoTime() < deadline do
        seen ++= consumer.poll(Duration.ofSeconds(1)).asScala.map(_.value())
      seen
    finally consumer.close(Duration.ofSeconds(2))

  test("a client presenting the service's certificate round-trips a record") {
    val dir =
      directory(authority.issue(cn = Some("money.wallet"), uris = Seq("ankka://money/wallet")))
    assertEquals(publish(dir, "spike-a", "hello"), Right(()))
    assertEquals(read(dir, "spike-a"), Vector("hello"))
  }

  test("a client presenting another authority's certificate is refused") {
    val dir = directory(foreign.issue(cn = Some("money.wallet")))
    assert(
      publish(dir, "spike-b", "nope").isLeft,
      "the broker accepted a certificate it does not trust"
    )
  }

  test("a renewed certificate is the one the next connection presents") {
    val dir = directory(authority.issue(cn = Some("money.wallet")))
    assertEquals(publish(dir, "spike-c", "first"), Right(()))
    // A certificate the broker refuses, in place of the good one: refused from now on.
    replace(dir, foreign.issue(cn = Some("money.wallet")))
    Thread.sleep(1000)
    assert(publish(dir, "spike-c", "refused").isLeft, "the old certificate was still presented")
    // A good one again, as a renewal brings: accepted with nothing restarted.
    replace(dir, authority.issue(cn = Some("money.wallet")))
    Thread.sleep(1000)
    assertEquals(publish(dir, "spike-c", "renewed"), Right(()))
  }

/**
 * testcontainers' self-typed container needs a concrete subclass for Scala 3 to infer. A fixed host
 * port is deprecated there, and is what a certificate naming the address needs.
 */
@annotation.nowarn("cat=deprecation")
private final class TlsKafka(port: Int)
    extends FixedHostPortGenericContainer[TlsKafka]("apache/kafka:3.8.0"):
  withFixedExposedPort(port, 9093)
  withCommand(
    "sh",
    "-c",
    "/opt/kafka/bin/kafka-storage.sh format -t spike0000000000000001 -c /mnt/tls/server.properties " +
      "&& exec /opt/kafka/bin/kafka-server-start.sh /mnt/tls/server.properties"
  )
  waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
