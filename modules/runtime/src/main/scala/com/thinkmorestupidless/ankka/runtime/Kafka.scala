package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{Producer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer, PartitionAssignmentHandler}
import org.apache.pekko.kafka.{
  CommitterSettings,
  ConsumerSettings,
  ProducerSettings,
  RestrictedConsumer,
  Subscriptions
}
import org.apache.pekko.stream.{KillSwitches, Materializer, RestartSettings, SharedKillSwitch}
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration as JDuration, Instant}
import java.util.{Properties, UUID}
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*

/**
 * CloudEvents framing for broker messages.
 *
 * The attributes travel as Kafka headers rather than being wrapped around the payload, so a
 * consumer written in another language reads a plain JSON body with metadata beside it.
 * `ce-subject` doubles as the record key unless a message names a key of its own, which is what
 * preserves per-entity ordering: Kafka guarantees order within a partition, and keying by subject
 * puts every message about one entity on the same partition.
 */
private[ankka] object CloudEvents:

  val SpecVersion = "1.0"

  def headers(metadata: Metadata, manifest: String): Vector[(String, String)] =
    val declared = metadata.toSeq.toVector.filter((key, _) => key.startsWith("ce-"))
    val defaults = Vector(
      Metadata.CeSpecVersion -> SpecVersion,
      Metadata.CeId          -> UUID.randomUUID().toString,
      Metadata.CeType        -> manifest,
      "content-type"         -> "application/json"
    ).filterNot((key, _) => declared.exists((existing, _) => existing.equalsIgnoreCase(key)))

    declared ++ defaults ++
      metadata.toSeq.toVector.filterNot((key, _) => key.startsWith("ce-") || key == "content-type")

  def metadataFrom(headers: Iterable[(String, String)]): Metadata =
    Metadata(headers.toVector)

/**
 * How a service reaches its broker: where it is, and, on an installation's broker, the certificate
 * to present and the prefix its project's topics carry.
 *
 * A component names a topic as its descriptor declared it; `qualified` is the name the broker
 * holds, and it is applied where a topic is handed to Kafka and nowhere else, so declared
 * connections, the topology and the logs keep the name the component wrote. A broker a descriptor
 * names has no prefix and no TLS directory, which is the connection every service had before.
 *
 * @param tlsDirectory
 *   where cert-manager writes the service certificate; with it the clients use TLS, presenting that
 *   certificate and following its renewal (`KafkaTls`)
 */
final case class KafkaConnection(
    bootstrapServers: String,
    tlsDirectory: Option[String] = None,
    topicPrefix: String = ""
):
  def qualified(topic: String): String = topicPrefix + topic

  /** What a Kafka client is configured with beyond the bootstrap address. */
  def properties: Map[String, String] = tlsDirectory.fold(Map.empty)(KafkaTls.clientProperties)

object KafkaConnection:

  /** The broker's address: given by a descriptor, or by the operator for the installation's. */
  val BootstrapVariable: String = "ANKKA_KAFKA_BOOTSTRAP_SERVERS"

  /** Where the certificate to present is; written by the operator alone. */
  val TlsDirectoryVariable: String = "ANKKA_KAFKA_TLS_DIRECTORY"

  /** What the project's topics start with on the broker; written by the operator alone. */
  val TopicPrefixVariable: String = "ANKKA_KAFKA_TOPIC_PREFIX"

  /** The connection the environment describes, if it names a broker at all. */
  def fromEnv(env: Map[String, String]): Option[KafkaConnection] =
    def value(name: String) = env.get(name).map(_.trim).filter(_.nonEmpty)
    value(BootstrapVariable).map(bootstrap =>
      KafkaConnection(
        bootstrap,
        value(TlsDirectoryVariable),
        value(TopicPrefixVariable).getOrElse("")
      )
    )

/**
 * Publishes to Kafka.
 *
 * Calls the Kafka producer directly, so each `send` reaches it in the order `publish` was called:
 * that, and the record key, is what keeps a change's messages in order on their partition. Pekko's
 * `SendProducer` was used here until a change's messages were seen out of order on one partition:
 * its `send` is a callback on the producer's future, run on a multi-threaded dispatcher, so two
 * sends issued in order are two tasks that may run in either.
 *
 * The producer is made on first publish: every service of an installation with a broker is told
 * where it is, and one that never publishes should hold no connection to it.
 */
final class KafkaPublisher private (
    connection: KafkaConnection,
    manifestOf: String => String
)(using system: ActorSystem[?])
    extends MessagePublisher
    with AutoCloseable:

  @volatile private var made: Option[Producer[String, Array[Byte]]] = None

  private def producer: Producer[String, Array[Byte]] =
    made.getOrElse(synchronized {
      made.getOrElse {
        val created = ProducerSettings(system, StringSerializer(), ByteArraySerializer())
          .withBootstrapServers(connection.bootstrapServers)
          // A send waits for its topic's metadata on the thread that calls it, which is the
          // projection's. For a topic nobody has declared that wait would be a minute, Kafka's
          // default, on every attempt; bounded, the send fails, the log names the topic and the
          // change comes again with the projection's backoff, holding no thread meanwhile.
          .withProperty(
            ProducerConfig.MAX_BLOCK_MS_CONFIG,
            KafkaPublisher.TopicWait.toMillis.toString
          )
          .withProperties(connection.properties)
          .createKafkaProducer()
        made = Some(created)
        created
      }
    })

  /** Whether a producer has been made: not until something is published. */
  private[ankka] def opened: Boolean = made.isDefined

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    publish(topic, None, payload, metadata)

  /** The record key is the one named, else `ce-subject`. The subject is a header either way. */
  override def publish(
      topic: String,
      key: Option[String],
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Done] =
    val qualified = connection.qualified(topic)
    val record    = ProducerRecord(qualified, key.orElse(metadata.subject).orNull, payload)

    CloudEvents.headers(metadata, manifestOf(topic)).foreach { (key, value) =>
      record.headers().add(RecordHeader(key, value.getBytes(UTF_8))): Unit
    }

    // A topic nobody declared is not made by publishing to it: the send fails, the delivery is
    // retried with the backoff the projection has, and the log says which topic it was waiting for.
    val sent = Promise[Done]()
    def failed(failure: Throwable): Unit =
      system.log.warn(
        "could not publish to topic '{}' ({} on the broker): {}",
        topic,
        qualified,
        failure.getMessage
      )
      sent.failure(failure): Unit

    try
      producer.send(
        record,
        (_, failure) => if failure == null then sent.success(Done) else failed(failure)
      ): Unit
    catch case NonFatal(failure) => failed(failure)
    sent.future

  def close(): Unit = made.foreach(_.close())

object KafkaPublisher:

  /** The longest a send waits for its topic to be known before it fails and is tried again. */
  val TopicWait: FiniteDuration = 5.seconds

  /**
   * Connects to `bootstrapServers`.
   *
   * The key serializer is `String` so `ce-subject` can be the record key; values are raw bytes,
   * already encoded by the producing consumer's own serializer.
   */
  def apply(bootstrapServers: String)(using system: ActorSystem[?]): KafkaPublisher =
    apply(KafkaConnection(bootstrapServers))

  /** To the broker `connection` describes. */
  def apply(connection: KafkaConnection)(using system: ActorSystem[?]): KafkaPublisher =
    new KafkaPublisher(connection, _ => "message")

  /** As above, with a per-topic CloudEvents `ce-type`. */
  def withTypes(bootstrapServers: String, typeFor: String => String)(using
      system: ActorSystem[?]
  ): KafkaPublisher =
    new KafkaPublisher(KafkaConnection(bootstrapServers), typeFor)

/**
 * Consumes from Kafka.
 *
 * Offsets are committed to Kafka and distribution is handled by consumer groups, rather than going
 * through ankka's projection offset store. That means rebalancing across nodes works with no code,
 * at the cost of topic sources being at-least-once, and a rebuild reaching back only as far as the
 * broker retains — a broker's retention is a window, not an event journal.
 */
final class KafkaSubscriber private (
    connection: KafkaConnection,
    restart: RestartSettings
)(using system: ActorSystem[?])
    extends MessageSubscriber:

  private given ExecutionContext = system.executionContext

  // A KillSwitch, not a Consumer.Control: `RestartSource` recreates the inner source on each
  // restart, so its control is not a stable handle on the subscription. Shared, so one switch is in
  // every incarnation, and placed between the source and the handler: shutting it completes what is
  // downstream, so the message in hand is finished and the committer flushes what it has batched.
  // Downstream of the committer it cancelled the commit instead, and a restart was handed again
  // everything since the last flush.
  private val running = CopyOnWriteArrayList[SharedKillSwitch]()

  def subscribe(
      subscription: TopicSubscription,
      handle: IncomingMessage => Future[Done]
  ): Subscribed =
    val qualified = connection.qualified(subscription.topic)
    val settings = ConsumerSettings(system, StringDeserializer(), ByteArrayDeserializer())
      .withBootstrapServers(connection.bootstrapServers)
      .withProperties(connection.properties)
      .withGroupId(subscription.group)
      // No longer where a new group starts: the assignment handler decides that, and commits it.
      // This is what a group whose committed offset has aged out of the topic falls back to — the
      // oldest message still there, rather than skipping to the end.
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      // A topic declared after the component started is found within this, not five minutes.
      .withProperty(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "30000")

    val topics = Subscriptions
      .topics(qualified)
      .withPartitionAssignmentHandler(KafkaSubscriber.StartPosition(subscription.startFrom))

    val killSwitch = KillSwitches.shared(s"topic-${subscription.topic}-${subscription.group}")

    // Restarting on failure is what makes this at-least-once: the stream resumes from
    // the last committed offset, so the failed message is delivered again.
    RestartSource
      .onFailuresWithBackoff(restart) { () =>
        Consumer
          .committableSource(settings, topics)
          // A topic that is not there, or not this project's, fails the stream; the restart asks
          // again with its backoff, and the log names the topic each time.
          .mapError { case failure =>
            system.log.warn(
              "could not read topic '{}' ({} on the broker): {}",
              subscription.topic,
              qualified,
              failure.getMessage
            )
            failure
          }
          .via(killSwitch.flow)
          .mapAsync(1) { committable =>
            val record = committable.record
            val headers = record
              .headers()
              .asScala
              .map(header => header.key -> String(header.value, UTF_8))
              .toVector

            handle(
              IncomingMessage(
                Option(record.key),
                record.value,
                CloudEvents.metadataFrom(headers)
              )
            ).map(_ => committable.committableOffset)
          }
          .via(Committer.flow(CommitterSettings(system)))
      }
      .runWith(Sink.ignore)(using Materializer(system)): Unit

    running.add(killSwitch): Unit
    system.log.info(
      "subscribed to topic '{}' ({}) as group '{}', starting at {} where the group has never read",
      subscription.topic,
      qualified,
      subscription.group,
      subscription.startFrom
    )
    () =>
      killSwitch.shutdown()
      running.remove(killSwitch): Unit

  /**
   * A consumer with no group, assigned every partition and moved to the beginning: the first record
   * of each says when the oldest message it holds was published. It commits nothing, and fails when
   * the broker cannot be asked, or a partition that holds messages yields none in time.
   */
  def earliestRetained(declared: String): Future[Map[Int, Option[Instant]]] =
    val topic = connection.qualified(declared)
    Future {
      blocking {
        val properties = Properties()
        connection.properties.foreach((key, value) => properties.put(key, value))
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers)
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        val consumer =
          KafkaConsumer[Array[Byte], Array[Byte]](
            properties,
            ByteArrayDeserializer(),
            ByteArrayDeserializer()
          )
        try
          val partitions = consumer
            .partitionsFor(topic, JDuration.ofSeconds(30))
            .asScala
            .toVector
            .map(info => TopicPartition(topic, info.partition))
          consumer.assign(partitions.asJava)
          val beginnings = consumer.beginningOffsets(partitions.asJava).asScala
          val ends       = consumer.endOffsets(partitions.asJava).asScala
          val holding    = partitions.filter(p => beginnings(p).longValue < ends(p).longValue)
          consumer.seekToBeginning(partitions.asJava)

          var found    = Map.empty[Int, Instant]
          val deadline = System.nanoTime() + 30.seconds.toNanos
          while !holding.forall(p => found.contains(p.partition)) && System.nanoTime() < deadline do
            consumer.poll(JDuration.ofMillis(500)).asScala.foreach { record =>
              if !found.contains(record.partition) then
                found += record.partition -> Instant.ofEpochMilli(record.timestamp)
                consumer.pause(java.util.List.of(TopicPartition(topic, record.partition)))
            }
          val missing = holding.filterNot(p => found.contains(p.partition))
          if missing.nonEmpty then
            throw IllegalStateException(
              s"topic '$topic' partitions ${missing.map(_.partition).mkString(", ")} hold " +
                "messages and yielded none within 30 seconds"
            )
          partitions.map(p => p.partition -> found.get(p.partition)).toMap
        finally consumer.close()
      }
    }

  def stop(): Unit =
    running.asScala.foreach(_.shutdown())
    running.clear()

object KafkaSubscriber:

  /**
   * Where a group begins on a partition it has never read, decided and committed the moment the
   * partition is first assigned.
   *
   * Committed, not only sought: a group that started at the latest message and has read nothing
   * from a partition would otherwise hold no offset for it, and a restart would move it to the
   * *new* end, past everything published while it was down. Once a partition has a committed
   * offset, this never looks at it again — a restart, a rebalance and a new instance all resume.
   *
   * A partition added to a topic after the group first read it has no committed offset either, so
   * it begins where the source declares. Under `latest`, what was published to it before the group
   * was assigned it is not read.
   */
  private[runtime] final class StartPosition(start: StartFrom) extends PartitionAssignmentHandler:

    def onAssign(assigned: Set[TopicPartition], consumer: RestrictedConsumer): Unit =
      if assigned.nonEmpty then
        val committed = consumer.committed(assigned.asJava)
        val fresh     = assigned.filter(p => Option(committed.get(p)).isEmpty)
        if fresh.nonEmpty then
          val offsets = positions(fresh, consumer)
          offsets.foreach((partition, offset) => consumer.seek(partition, offset))
          consumer.commitSync(
            offsets.map((partition, offset) => partition -> OffsetAndMetadata(offset)).asJava
          )

    private def positions(
        partitions: Set[TopicPartition],
        consumer: RestrictedConsumer
    ): Map[TopicPartition, Long] =
      def longs(offsets: java.util.Map[TopicPartition, java.lang.Long]) =
        offsets.asScala.map((partition, offset) => partition -> offset.longValue).toMap
      start match
        case StartFrom.Earliest => longs(consumer.beginningOffsets(partitions.asJava))
        case StartFrom.Latest   => longs(consumer.endOffsets(partitions.asJava))
        case StartFrom.At(time) =>
          val ends = longs(consumer.endOffsets(partitions.asJava))
          val found = consumer.offsetsForTimes(
            partitions.map(p => p -> java.lang.Long.valueOf(time.toEpochMilli)).toMap.asJava
          )
          // No message at or after the time: the end, as `latest` would be.
          partitions.map(p => p -> Option(found.get(p)).map(_.offset).getOrElse(ends(p))).toMap

    def onRevoke(revoked: Set[TopicPartition], consumer: RestrictedConsumer): Unit = ()
    def onLost(lost: Set[TopicPartition], consumer: RestrictedConsumer): Unit      = ()
    def onStop(current: Set[TopicPartition], consumer: RestrictedConsumer): Unit   = ()

  def apply(
      bootstrapServers: String,
      minBackoff: FiniteDuration = 1.second,
      maxBackoff: FiniteDuration = 30.seconds
  )(using system: ActorSystem[?]): KafkaSubscriber =
    apply(KafkaConnection(bootstrapServers), minBackoff, maxBackoff)

  /** From the broker `connection` describes. */
  def apply(
      connection: KafkaConnection,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration
  )(using system: ActorSystem[?]): KafkaSubscriber =
    new KafkaSubscriber(
      connection,
      RestartSettings(minBackoff, maxBackoff, randomFactor = 0.2)
    )
