package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import org.apache.pekko.Done

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Where a consumer's `effects.produce(...)` output goes.
 *
 * An SPI rather than a concrete broker client: publishing is the one part of the consumer story
 * that is genuinely environment-specific, and a runtime that hard-codes one broker forces everyone
 * else to fork it.
 *
 * ankka ships `InMemoryPublisher` for tests. A Kafka or Pub/Sub implementation is this one method
 * over `SendProducer`; none is bundled yet, and a consumer that declares `produceTo` without a
 * publisher configured fails at startup rather than dropping messages silently.
 */
trait MessagePublisher:

  /** Publishes one message, keyed by its subject (`ce-subject`). */
  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done]

  /**
   * Publishes one message under `key` when one is given: the record's key and the message's subject
   * are separate things. The subject says which entity a message is about; the key says which
   * messages are ordered together and which one a compacted topic keeps.
   *
   * A publisher that has not been taught to key a message apart from its subject fails here rather
   * than publish it under the subject, which a reader that depends on the key would take for a
   * different record.
   */
  def publish(
      topic: String,
      key: Option[String],
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Done] =
    key match
      case None => publish(topic, payload, metadata)
      case Some(named) =>
        Future.failed(
          UnsupportedOperationException(
            s"${getClass.getName} cannot publish to '$topic' under the record key '$named': it " +
              "keys every message by its subject. Implement " +
              "publish(topic, key, payload, metadata)."
          )
        )

/** Records published messages in memory, for tests and local development. */
final class InMemoryPublisher extends MessagePublisher:

  private val recorded = ConcurrentLinkedQueue[InMemoryPublisher.Published]()

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    publish(topic, None, payload, metadata)

  override def publish(
      topic: String,
      key: Option[String],
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Done] =
    recorded.add(InMemoryPublisher.Published(topic, payload, metadata, key)): Unit
    Future.successful(Done)

  def published: Seq[InMemoryPublisher.Published] = recorded.asScala.toSeq

  def publishedTo(topic: String): Seq[InMemoryPublisher.Published] =
    published.filter(_.topic == topic)

  def clear(): Unit = recorded.clear()

object InMemoryPublisher:
  /** `key` is the record key the message named, if it named one. */
  final case class Published(
      topic: String,
      payload: Array[Byte],
      metadata: Metadata,
      key: Option[String] = None
  ):
    def text: String = String(payload, "UTF-8")

    /** What a broker is given as the record's key: the one named, else the subject. */
    def recordKey: Option[String] = key.orElse(metadata.subject)
