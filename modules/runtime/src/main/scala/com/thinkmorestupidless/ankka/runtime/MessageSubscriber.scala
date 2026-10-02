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
  // Per topic: how many more publications succeed before one is refused. Absent: none is.
  private val failures = ConcurrentHashMap[String, java.lang.Integer]()

  private given ExecutionContext = ExecutionContext.parasitic

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    publish(topic, None, payload, metadata)

  /** The record key is the one named, else the subject, as on a real broker. */
  override def publish(
      topic: String,
      key: Option[String],
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Done] =
    if refuses(topic) then
      Future.failed(InMemoryBroker.Refused(s"the broker refused a publication to '$topic'"))
    else
      val message = IncomingMessage(key.orElse(metadata.subject), payload, metadata)
      delivered.add(InMemoryBroker.Delivered(topic, message)): Unit

      val listeners =
        Option(subscriptions.get(topic)).map(_.asScala.toVector).getOrElse(Vector.empty)
      if listeners.isEmpty then Future.successful(Done)
      else
        // Every group gets its own copy, as a broker would.
        Future
          .sequence(listeners.map(_.handle(message)))
          .map(_ => Done)

  /**
   * Refuses one publication to `topic`: the next, or the one after `after` more have succeeded. For
   * testing what a consumer does when the broker accepts some of a change's messages and not the
   * rest. Publications after the refused one succeed.
   */
  def failNext(topic: String, after: Int = 0): Unit =
    failures.put(topic, after): Unit

  private def refuses(topic: String): Boolean =
    var refused = false
    failures.computeIfPresent(
      topic,
      (_, remaining) =>
        if remaining == 0 then
          refused = true
          null
        else remaining - 1
    ): Unit
    refused

  def subscribe(topic: String, groupId: String, handle: IncomingMessage => Future[Done]): Unit =
    subscriptions
      .computeIfAbsent(topic, _ => CopyOnWriteArrayList[Subscription]())
      .add(Subscription(groupId, handle)): Unit

  def stop(): Unit = subscriptions.clear()

  /** Everything published, for assertions. */
  def published: Seq[InMemoryBroker.Delivered] = delivered.asScala.toSeq

  def publishedTo(topic: String): Seq[InMemoryBroker.Delivered] =
    published.filter(_.topic == topic)

  def clear(): Unit =
    delivered.clear()
    failures.clear()

object InMemoryBroker:

  /** What `failNext` fails a publication with. */
  final case class Refused(message: String) extends RuntimeException(message)

  final case class Delivered(topic: String, message: IncomingMessage):
    def text: String = String(message.payload, "UTF-8")
