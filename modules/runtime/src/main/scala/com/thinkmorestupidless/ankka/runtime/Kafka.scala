package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{Producer, ProducerRecord}
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
 * Publishes to Kafka.
 *
 * Calls the Kafka producer directly, so each `send` reaches it in the order `publish` was called:
 * that, and the record key, is what keeps a change's messages in order on their partition. Pekko's
 * `SendProducer` was used here until a change's messages were seen out of order on one partition:
 * its `send` is a callback on the producer's future, run on a multi-threaded dispatcher, so two
 * sends issued in order are two tasks that may run in either.
 */
final class KafkaPublisher private (
    producer: Producer[String, Array[Byte]],
    manifestOf: String => String
) extends MessagePublisher:

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    publish(topic, None, payload, metadata)

  /** The record key is the one named, else `ce-subject`. The subject is a header either way. */
  override def publish(
      topic: String,
      key: Option[String],
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Done] =
    val record = ProducerRecord(topic, key.orElse(metadata.subject).orNull, payload)

    CloudEvents.headers(metadata, manifestOf(topic)).foreach { (key, value) =>
      record.headers().add(RecordHeader(key, value.getBytes(UTF_8))): Unit
    }

    val sent = Promise[Done]()
    try
      producer.send(
        record,
        (_, failure) => if failure == null then sent.success(Done) else sent.failure(failure)
      ): Unit
    catch case NonFatal(failure) => sent.failure(failure)
    sent.future

  def close(): Unit = producer.close()

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
    new KafkaPublisher(settings.createKafkaProducer(), _ => "message")

  /** As above, with a per-topic CloudEvents `ce-type`. */
  def withTypes(bootstrapServers: String, typeFor: String => String)(using
      system: ActorSystem[?]
  ): KafkaPublisher =
    val settings = ProducerSettings(system, StringSerializer(), ByteArraySerializer())
      .withBootstrapServers(bootstrapServers)
    new KafkaPublisher(settings.createKafkaProducer(), typeFor)

/**
 * Consumes from Kafka.
 *
 * Offsets are committed to Kafka and distribution is handled by consumer groups, rather than going
 * through ankka's projection offset store. That means rebalancing across nodes works with no code,
 * at the cost of topic sources being at-least-once, and a rebuild reaching back only as far as the
 * broker retains — a broker's retention is a window, not an event journal.
 */
final class KafkaSubscriber private (
    bootstrapServers: String,
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
    val settings = ConsumerSettings(system, StringDeserializer(), ByteArrayDeserializer())
      .withBootstrapServers(bootstrapServers)
      .withGroupId(subscription.group)
      // No longer where a new group starts: the assignment handler decides that, and commits it.
      // This is what a group whose committed offset has aged out of the topic falls back to — the
      // oldest message still there, rather than skipping to the end.
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val topics = Subscriptions
      .topics(subscription.topic)
      .withPartitionAssignmentHandler(KafkaSubscriber.StartPosition(subscription.startFrom))

    val killSwitch = KillSwitches.shared(s"topic-${subscription.topic}-${subscription.group}")

    // Restarting on failure is what makes this at-least-once: the stream resumes from
    // the last committed offset, so the failed message is delivered again.
    RestartSource
      .onFailuresWithBackoff(restart) { () =>
        Consumer
          .committableSource(settings, topics)
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
      "subscribed to topic '{}' as group '{}', starting at {} where the group has never read",
      subscription.topic,
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
  def earliestRetained(topic: String): Future[Map[Int, Option[Instant]]] =
    Future {
      blocking {
        val properties = Properties()
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
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
    new KafkaSubscriber(
      bootstrapServers,
      RestartSettings(minBackoff, maxBackoff, randomFactor = 0.2)
    )
