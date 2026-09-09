package nakka.runtime

import nakka.core.Metadata
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer, SendProducer}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, ProducerSettings, Subscriptions}
import org.apache.pekko.stream.{KillSwitches, Materializer, RestartSettings, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{Keep, RestartSource, Sink}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/**
 * CloudEvents framing for broker messages.
 *
 * The attributes travel as Kafka headers rather than being wrapped around the payload, so a
 * consumer written in another language reads a plain JSON body with metadata beside it.
 * `ce-subject` doubles as the record key, which is what preserves per-entity ordering: Kafka
 * guarantees order within a partition, and keying by subject puts every message about one entity on
 * the same partition.
 */
private[nakka] object CloudEvents:

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

/** Publishes to Kafka. */
final class KafkaPublisher private (
    producer: SendProducer[String, Array[Byte]],
    manifestOf: String => String
)(using system: ActorSystem[?])
    extends MessagePublisher:

  private given ExecutionContext = system.executionContext

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    val subject = metadata.subject.orNull
    val record  = ProducerRecord(topic, subject, payload)

    CloudEvents.headers(metadata, manifestOf(topic)).foreach { (key, value) =>
      record.headers().add(RecordHeader(key, value.getBytes(UTF_8))): Unit
    }

    producer.send(record).map(_ => Done)

  def close(): Unit = producer.close(): Unit

object KafkaPublisher:

  /**
   * Connects to `bootstrapServers`.
   *
   * The key serializer is `String` so `ce-subject` can be the record key; values are raw bytes,
   * already encoded by the producing consumer's own serializer.
   */
  def apply(bootstrapServers: String)(using system: ActorSystem[?]): KafkaPublisher =
    val settings = ProducerSettings(system, StringSerializer(), ByteArraySerializer())
      .withBootstrapServers(bootstrapServers)
    new KafkaPublisher(SendProducer(settings)(using system), _ => "message")

  /** As above, with a per-topic CloudEvents `ce-type`. */
  def withTypes(bootstrapServers: String, typeFor: String => String)(using
      system: ActorSystem[?]
  ): KafkaPublisher =
    val settings = ProducerSettings(system, StringSerializer(), ByteArraySerializer())
      .withBootstrapServers(bootstrapServers)
    new KafkaPublisher(SendProducer(settings)(using system), typeFor)

/**
 * Consumes from Kafka.
 *
 * Offsets are committed to Kafka and distribution is handled by consumer groups, rather than going
 * through nakka's projection offset store. That means rebalancing across nodes works with no code,
 * at the cost of topic sources being at-least-once and unable to rebuild from history — which is
 * what a topic can offer anyway, since a broker's retention is not an event journal.
 */
final class KafkaSubscriber private (
    bootstrapServers: String,
    restart: RestartSettings
)(using system: ActorSystem[?])
    extends MessageSubscriber:

  private given ExecutionContext = system.executionContext

  // A KillSwitch, not a Consumer.Control: `RestartSource` recreates the inner source
  // on each restart, so its control is not a stable handle on the subscription.
  private val running = CopyOnWriteArrayList[UniqueKillSwitch]()

  def subscribe(topic: String, groupId: String, handle: IncomingMessage => Future[Done]): Unit =
    val settings = ConsumerSettings(system, StringDeserializer(), ByteArrayDeserializer())
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      // A new component starts from the beginning of what the broker still holds; there
      // is no earlier history to replay, so this is the most it can see.
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    // Restarting on failure is what makes this at-least-once: the stream resumes from
    // the last committed offset, so the failed message is delivered again.
    val killSwitch = RestartSource
      .onFailuresWithBackoff(restart) { () =>
        Consumer
          .committableSource(settings, Subscriptions.topics(topic))
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
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.left)
      .run()(using Materializer(system))

    running.add(killSwitch): Unit
    system.log.info("subscribed to topic '{}' as group '{}'", topic, groupId)

  def stop(): Unit =
    running.asScala.foreach(_.shutdown())
    running.clear()

object KafkaSubscriber:

  def apply(
      bootstrapServers: String,
      minBackoff: FiniteDuration = 1.second,
      maxBackoff: FiniteDuration = 30.seconds
  )(using system: ActorSystem[?]): KafkaSubscriber =
    new KafkaSubscriber(
      bootstrapServers,
      RestartSettings(minBackoff, maxBackoff, randomFactor = 0.2)
    )
