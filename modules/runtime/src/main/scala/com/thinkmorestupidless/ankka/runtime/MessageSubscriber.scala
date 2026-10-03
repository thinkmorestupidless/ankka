package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.pekko.Done

import java.time.Instant
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
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
 * What a topic source asks a broker for: a topic, read under a consumer group, starting at
 * `startFrom` wherever the group has never read.
 */
final case class TopicSubscription(topic: String, group: String, startFrom: StartFrom)

/** One running subscription. */
trait Subscribed:
  /** Stops this subscription. Offsets already committed stay committed. */
  def stop(): Unit

/**
 * Where a topic-sourced view or consumer gets its messages.
 *
 * The counterpart to `MessagePublisher`, and an SPI for the same reason: which broker a deployment
 * uses is not something the runtime should decide. An implementation owes four things:
 *
 *   1. **One group, one delivery.** A message is handed to one subscriber of a group, and to every
 *      group. Two subscriptions under one group share the topic.
 *   2. **A group resumes.** A group that has been handed a message, or assigned a partition, starts
 *      again where it stopped. `startFrom` applies only to what the group has never been assigned.
 *   3. **A failed handler is not acknowledged.** The message is delivered again. A handler that
 *      stopped its own subscription and then failed is not delivered to again by this instance.
 *   4. **`earliestRetained` answers for every partition**, `None` for one that holds nothing, and
 *      fails when the broker cannot be asked. It reads under no group and commits nothing.
 *
 * Topic sources are at-least-once, so a component reading one has to tolerate seeing a message
 * twice.
 */
trait MessageSubscriber:

  /** Starts reading `subscription.topic` under `subscription.group`. */
  def subscribe(
      subscription: TopicSubscription,
      handle: IncomingMessage => Future[Done]
  ): Subscribed

  /** For each partition of `topic`, when its earliest retained message was published. */
  def earliestRetained(topic: String): Future[Map[Int, Option[Instant]]]

  /** Stops every subscription. */
  def stop(): Unit

/**
 * A publisher and subscriber wired to each other, with no broker.
 *
 * This is what lets ankka's topic support be tested properly without a container: the whole path —
 * CloudEvents headers, subject-keyed ordering, decoding, view and consumer dispatch, where a group
 * starts and resumes — is exercised, and only the wire itself is substituted.
 *
 * It keeps every message published to each topic, as one partition that never drops anything, and
 * for each group a position in it. A group takes messages in order, one at a time, its subscribers
 * in turn; a group that starts behind is handed its backlog first. Positions outlive `stop`, so a
 * restarted service resumes where it was, as it would on a broker. Retention, rebalancing and
 * partitions added later cannot be shown with it: that is the Kafka suite's.
 *
 * A publication's future completes when every group on the topic has caught up with it, and fails
 * with the first handler that failed. A failed message stays at the head of its group, and is
 * delivered again by the next publication to the topic or by `redeliver`.
 */
final class InMemoryBroker extends MessagePublisher with MessageSubscriber:

  private given ExecutionContext = ExecutionContext.parasitic

  private final case class Entry(at: Instant, message: IncomingMessage)

  private final class Member(val handle: IncomingMessage => Future[Done]):
    @volatile var live = true

  private final class Group(topic: String):
    var position: Int                        = 0
    val members: mutable.ArrayBuffer[Member] = mutable.ArrayBuffer.empty
    private var turn                         = 0
    private var tail: Future[Done]           = Future.successful(Done)

    /**
     * Catches up with the topic; one drain at a time, each after the last. Handlers run outside the
     * lock, so one that publishes, to this topic or another, cannot deadlock against a publisher.
     */
    def drain(): Future[Done] =
      val done     = Promise[Done]()
      val previous = synchronized { val last = tail; tail = done.future; last }
      previous.recover(_ => Done).flatMap(_ => step()).onComplete(done.complete)
      done.future

    private def step(): Future[Done] =
      val next = synchronized {
        members.filterInPlace(_.live)
        val log = logOf(topic)
        if members.isEmpty || position >= log.size then None
        else
          val member = members(turn % members.size)
          turn += 1
          Some(member -> log(position))
      }
      next match
        case None => Future.successful(Done)
        case Some((member, entry)) =>
          Future
            .delegate(member.handle(entry.message))
            .transformWith {
              case scala.util.Success(_) =>
                synchronized { position += 1; stuck = false }
                step()
              case scala.util.Failure(failure) =>
                synchronized { stuck = true }
                Future.failed(failure)
            }

    // Whether the message at `position` is one a handler failed.
    private var stuck = false

    def skipFailed(): Unit = synchronized {
      if stuck then
        position += 1
        stuck = false
    }

  // Per topic, everything ever published to it. Never cleared: a group's position indexes it.
  private val logs = ConcurrentHashMap[String, mutable.ArrayBuffer[Entry]]()
  // Per topic, then per group.
  private val groups    = ConcurrentHashMap[String, ConcurrentHashMap[String, Group]]()
  private val delivered = CopyOnWriteArrayList[InMemoryBroker.Delivered]()
  // Per topic: how many more publications succeed before one is refused. Absent: none is.
  private val failures = ConcurrentHashMap[String, java.lang.Integer]()

  @volatile private var clock: () => Instant = () => Instant.now()

  /** What time a publication is recorded at, for a start position that is a time. */
  def setClock(now: () => Instant): Unit = clock = now

  private def logOf(topic: String): Vector[Entry] =
    val log = logs.computeIfAbsent(topic, _ => mutable.ArrayBuffer.empty[Entry])
    log.synchronized(log.toVector)

  private def groupsOf(topic: String): Vector[Group] =
    Option(groups.get(topic)).map(_.values.asScala.toVector).getOrElse(Vector.empty)

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
      val log = logs.computeIfAbsent(topic, _ => mutable.ArrayBuffer.empty[Entry])
      log.synchronized(log += Entry(clock(), message)): Unit
      redeliver(topic)

  /**
   * Has every group on `topic` catch up, starting with any message a handler failed — what a broker
   * does on its own when an unacknowledged message is due again.
   */
  def redeliver(topic: String): Future[Done] =
    Future.sequence(groupsOf(topic).map(_.drain())).map(_ => Done)

  /**
   * Moves every group on `topic` that is stuck on a message its handler failed past that message,
   * as an operator resetting a group's offset past a message that can never be handled would. For a
   * test whose message is meant to fail for ever, so the cases after it are not handed it first.
   */
  def skipFailed(topic: String): Unit =
    groupsOf(topic).foreach(_.skipFailed())

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

  def subscribe(
      subscription: TopicSubscription,
      handle: IncomingMessage => Future[Done]
  ): Subscribed =
    val byGroup = groups.computeIfAbsent(subscription.topic, _ => ConcurrentHashMap())
    val group = byGroup.computeIfAbsent(
      subscription.group,
      _ =>
        val created = Group(subscription.topic)
        created.position = startOf(subscription)
        created
    )
    val member = Member(handle)
    group.synchronized(group.members += member): Unit
    group.drain(): Unit
    () => member.live = false

  /** Where a group that has never read begins. */
  private def startOf(subscription: TopicSubscription): Int =
    val log = logOf(subscription.topic)
    subscription.startFrom match
      case StartFrom.Earliest => 0
      case StartFrom.Latest   => log.size
      case StartFrom.At(time) =>
        val first = log.indexWhere(!_.at.isBefore(time))
        if first < 0 then log.size else first

  def earliestRetained(topic: String): Future[Map[Int, Option[Instant]]] =
    Future.successful(Map(0 -> logOf(topic).headOption.map(_.at)))

  /**
   * Every subscription ends; every group keeps its position, as a broker keeps committed offsets.
   */
  def stop(): Unit =
    groups.values.asScala.foreach(_.values.asScala.foreach { group =>
      group.synchronized(group.members.foreach(_.live = false))
    })

  /** The groups that have read `topic`, and how many of its messages each has been handed. */
  def positions(topic: String): Map[String, Int] =
    Option(groups.get(topic))
      .map(_.asScala.map((name, group) => name -> group.synchronized(group.position)).toMap)
      .getOrElse(Map.empty)

  /** Everything published, for assertions. */
  def published: Seq[InMemoryBroker.Delivered] = delivered.asScala.toSeq

  def publishedTo(topic: String): Seq[InMemoryBroker.Delivered] =
    published.filter(_.topic == topic)

  /** Forgets what was published, for assertions, and any refusal still due. Groups keep reading. */
  def clear(): Unit =
    delivered.clear()
    failures.clear()

object InMemoryBroker:

  /** What `failNext` fails a publication with. */
  final case class Refused(message: String) extends RuntimeException(message)

  final case class Delivered(topic: String, message: IncomingMessage):
    def text: String = String(message.payload, "UTF-8")
