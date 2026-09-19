package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import org.apache.pekko.Done

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A message arriving from a broker topic. */
final case class IncomingMessage(
    key: Option[String],
    payload: Array[Byte],
    metadata: Metadata
):
  /** CloudEvents subject, which ankka uses as the entity id a message concerns. */
  def subject: Option[String] = metadata.subject.orElse(key)

/**
 * Where a topic-sourced view or consumer gets its messages.
 *
 * The counterpart to `MessagePublisher`, and an SPI for the same reason: which broker a deployment
 * uses is not something the runtime should decide.
 *
 * A handler that fails must not have its offset committed. Implementations are expected to
 * redeliver — topic sources are at-least-once, and a component reading one has to tolerate seeing a
 * message twice.
 */
trait MessageSubscriber:

  /**
   * Starts consuming `topic`.
   *
   * `groupId` identifies the consuming component, so two components reading one topic each see
   * every message, while two *instances* of one component share the work.
   */
  def subscribe(topic: String, groupId: String, handle: IncomingMessage => Future[Done]): Unit

  /** Stops every subscription. */
  def stop(): Unit

/**
 * A publisher and subscriber wired to each other, with no broker.
 *
 * This is what lets ankka's topic support be tested properly without a container: the whole path —
 * CloudEvents headers, subject-keyed ordering, decoding, view and consumer dispatch — is exercised,
 * and only the wire itself is substituted.
 */
final class InMemoryBroker extends MessagePublisher with MessageSubscriber:

  private final case class Subscription(groupId: String, handle: IncomingMessage => Future[Done])

  private val subscriptions = ConcurrentHashMap[String, CopyOnWriteArrayList[Subscription]]()
  private val delivered     = CopyOnWriteArrayList[InMemoryBroker.Delivered]()

  private given ExecutionContext = ExecutionContext.parasitic

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    val message = IncomingMessage(metadata.subject, payload, metadata)
    delivered.add(InMemoryBroker.Delivered(topic, message)): Unit

    val listeners = Option(subscriptions.get(topic)).map(_.asScala.toVector).getOrElse(Vector.empty)
    if listeners.isEmpty then Future.successful(Done)
    else
      // Every group gets its own copy, as a broker would.
      Future
        .sequence(listeners.map(_.handle(message)))
        .map(_ => Done)

  def subscribe(topic: String, groupId: String, handle: IncomingMessage => Future[Done]): Unit =
    subscriptions
      .computeIfAbsent(topic, _ => CopyOnWriteArrayList[Subscription]())
      .add(Subscription(groupId, handle)): Unit

  def stop(): Unit = subscriptions.clear()

  /** Everything published, for assertions. */
  def published: Seq[InMemoryBroker.Delivered] = delivered.asScala.toSeq

  def publishedTo(topic: String): Seq[InMemoryBroker.Delivered] =
    published.filter(_.topic == topic)

  def clear(): Unit = delivered.clear()

object InMemoryBroker:
  final case class Delivered(topic: String, message: IncomingMessage):
    def text: String = String(message.payload, "UTF-8")
