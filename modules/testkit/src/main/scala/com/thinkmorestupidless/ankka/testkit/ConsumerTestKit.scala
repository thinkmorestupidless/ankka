package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.core.graph.GraphDelta
import com.thinkmorestupidless.ankka.runtime.{InMemoryPublisher, ProjectionSupport}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.graph.GraphConsumer

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * Drives a consumer in memory: hand it a change, read back what it would publish.
 *
 * Nothing is started — no runtime, no database, no broker. The change is decoded as the runtime
 * decodes it, the handler runs with the subject and sequence number given, and its effect is
 * applied by the function the runtime applies it with, against a publisher that only records. So
 * what a test reads is what a broker would have been given: each message's key, its headers, and
 * its payload as its bytes decode.
 *
 * A handler that fails, or a result the runtime would refuse — no topic to publish to, an empty key
 * — throws here, where in a running service the change would be delivered again.
 */
final class ConsumerTestKit[Src, Out] private (
    descriptor: ConsumerDescriptor[? <: Consumer[Src, Out], Src, Out],
    client: ComponentClient
):
  import ConsumerTestKit.*

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client))

  /** Hands the consumer one change of `subject` at `sequenceNumber`. */
  def onMessage(message: Src, subject: String = "test", sequenceNumber: Long = 1): Result[Out] =
    // Through its bytes, so a message the source's serializer cannot carry fails here.
    val decoded = descriptor.source.decoder.fromBytes(descriptor.source.decoder.toBytes(message))
    run(subject, sequenceNumber)(consumer.onMessage(decoded))

  /** Tells the consumer its source entity was deleted. */
  def onDelete(subject: String = "test", sequenceNumber: Long = 1): Result[Out] =
    run(subject, sequenceNumber)(consumer.onDelete)

  private def run(subject: String, sequenceNumber: Long)(
      handle: => ConsumerEffect[Out]
  ): Result[Out] =
    consumer._setContext(Some(SimpleChangeContext(subject, sequenceNumber, localOrigin = true)))
    val effect =
      try handle
      finally consumer._setContext(None)

    val publisher = InMemoryPublisher()
    val applied = ProjectionSupport.applyConsumer(
      effect.asInstanceOf[ConsumerEffect[Any]],
      subject,
      descriptor.asInstanceOf[ConsumerDescriptor[Consumer[Any, Any], Any, Any]],
      Some(publisher)
    )
    val _ = Await.result(applied, 5.seconds)

    Result(
      effect,
      publisher.published.toVector.map { published =>
        Produced(
          descriptor.outputSerializer.get.fromBytes(published.payload),
          published.key,
          published.metadata,
          published.payload
        )
      }
    )

object ConsumerTestKit:

  /**
   * One message as it would be published: its payload, decoded from the bytes it was encoded to;
   * the key it named, if it named one; and its metadata, with `ce-subject` set as the runtime sets
   * it.
   */
  final case class Produced[Out](
      payload: Out,
      key: Option[String],
      metadata: Metadata,
      bytes: Array[Byte]
  ):
    def text: String = String(bytes, "UTF-8")

    /** What a broker is given as the record's key: the one named, else the subject. */
    def recordKey: Option[String] = key.orElse(metadata.subject)

  /** What a change came to: the effect the handler returned and the messages it publishes. */
  final case class Result[Out](effect: ConsumerEffect[Out], messages: Vector[Produced[Out]]):
    def payloads: Vector[Out] = messages.map(_.payload)

    /** The key each message named; `None` for one that is keyed by its subject. */
    def keys: Vector[Option[String]] = messages.map(_.key)

    /** The key a broker is given for each message. */
    def recordKeys: Vector[Option[String]] = messages.map(_.recordKey)

  /** A kit for a consumer, from its companion. Calls through `client` are refused by default. */
  def of[C <: Consumer[Src, Out], Src, Out](
      companion: Consumer.Companion[C, Src, Out],
      client: ComponentClient = TestTransport.unroutedClient
  ): ConsumerTestKit[Src, Out] =
    new ConsumerTestKit(companion.descriptor, client)

  /** A kit for a graph consumer: what it reads back are the deltas, not messages. */
  def graph[C <: GraphConsumer[Src], Src](
      companion: GraphConsumer.Companion[C, Src],
      client: ComponentClient = TestTransport.unroutedClient
  ): GraphConsumerTestKit[Src] =
    new GraphConsumerTestKit(new ConsumerTestKit(companion.descriptor, client))

/**
 * Drives a graph consumer in memory: hand it a change, read back the deltas it publishes.
 *
 * Each delta is read from the bytes that would be published, under the key they would be published
 * under, by the reader a consumer of the topic would use — so a test asserts on what the merge sink
 * would be given.
 */
final class GraphConsumerTestKit[Src] private[testkit] (kit: ConsumerTestKit[Src, GraphDelta]):

  /** The same consumer as the records it publishes: each delta's bytes, key and headers. */
  def records: ConsumerTestKit[Src, GraphDelta] = kit

  def onMessage(
      message: Src,
      subject: String = "test",
      sequenceNumber: Long = 1
  ): Vector[GraphDelta] =
    read(kit.onMessage(message, subject, sequenceNumber))

  def onDelete(subject: String = "test", sequenceNumber: Long = 1): Vector[GraphDelta] =
    read(kit.onDelete(subject, sequenceNumber))

  private def read(result: ConsumerTestKit.Result[GraphDelta]): Vector[GraphDelta] =
    result.messages.map { message =>
      GraphDelta
        .read(message.recordKey, message.bytes)
        .fold(
          problem => throw IllegalStateException(s"published a delta the sink refuses: $problem"),
          identity
        )
    }
