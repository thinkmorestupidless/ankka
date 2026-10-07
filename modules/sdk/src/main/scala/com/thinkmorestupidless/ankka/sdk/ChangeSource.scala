package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{ComponentId, Contract, Serializer}

import java.time.Instant

/**
 * Where a view or consumer gets its changes from.
 *
 * Built from the source component's own companion, so the change type and its decoder come from one
 * place. A view over `ShoppingCartEntity` cannot accidentally be typed against the wrong event
 * hierarchy, and cannot drift when the entity's serializer changes.
 */
sealed trait ChangeSource[Src]:
  def decoder: Serializer[Src]
  def describe: String

object ChangeSource:

  /** Every event the entity has persisted, in order, exactly once. */
  final case class EventSourced[Src](componentId: ComponentId, decoder: Serializer[Src])
      extends ChangeSource[Src]:
    def describe = s"event-sourced-entity($componentId)"

  /**
   * State changes of a key value entity.
   *
   * Only the latest value is guaranteed to arrive: intermediate updates can be skipped, because the
   * store keeps no history to replay. Fine for a projection of "what is", wrong for anything that
   * needs to count or audit changes.
   */
  final case class KeyValue[Src](componentId: ComponentId, decoder: Serializer[Src])
      extends ChangeSource[Src]:
    def describe = s"key-value-entity($componentId)"

  /**
   * Messages from a broker topic.
   *
   * `startFrom` is where the source begins the first time its consumer group reads the topic, and
   * `None` when it declares nowhere. A view that declares nowhere starts at the earliest message
   * the broker holds; a consumer must declare, and one that does not is refused when the service
   * starts, because either default would be wrong for something that acts on each message.
   */
  /**
   * `options` is what the project must know about the subscription: the contract the component
   * expects the topic to carry, the declared broker the topic is on, and whether the partitions an
   * instance holds are read in parallel (feature 037).
   */
  final case class Topic[Src](
      topic: String,
      decoder: Serializer[Src],
      startFrom: Option[StartFrom] = None,
      options: TopicOptions = TopicOptions()
  ) extends ChangeSource[Src]:
    def describe =
      val from   = startFrom.fold("")(start => s", from $start")
      val onto   = options.broker.fold("")(b => s", on $b")
      val stated = options.contract.fold("")(c => s", as ${c.name}")
      s"topic($topic$from$onto$stated)"

  def eventsOf[C <: EventSourcedEntity[S, E], S, E](
      companion: EventSourcedEntity.Companion[C, S, E]
  ): ChangeSource[E] =
    EventSourced(companion.componentId, companion.eventSerializer)

  def stateOf[C <: KeyValueEntity[S], S](
      companion: KeyValueEntity.Companion[C, S]
  ): ChangeSource[S] =
    KeyValue(companion.componentId, companion.stateSerializer)

  /** A topic, starting wherever the component's default says: a view from the earliest message. */
  def fromTopic[Src](name: String, decoder: Serializer[Src]): ChangeSource[Src] =
    Topic(name, decoder)

  /** A topic, starting at `startFrom` the first time the component's group reads it. */
  def fromTopic[Src](
      name: String,
      decoder: Serializer[Src],
      startFrom: StartFrom
  ): ChangeSource[Src] =
    Topic(name, decoder, Some(startFrom))

  def fromTopic[Src](
      name: String,
      decoder: Serializer[Src],
      startFrom: StartFrom,
      options: TopicOptions
  ): ChangeSource[Src] =
    Topic(name, decoder, Some(startFrom), options)

/**
 * What a component says about a topic it reads, beyond its name: the contract it expects the topic
 * to carry (checked at start against the project's declaration), the declared broker the topic is
 * on (the installation's when `None`), and whether the partitions an instance holds are handled at
 * once, each in order. A view's topic source takes the same options.
 */
final case class TopicOptions(
    contract: Option[Contract] = None,
    broker: Option[String] = None,
    parallel: Boolean = false
)

/**
 * A topic a consumer publishes to, with the contract it states for it and the declared broker it is
 * on. `produceTo` is the short form: the topic alone.
 */
final case class Publication(
    topic: String,
    contract: Option[Contract] = None,
    broker: Option[String] = None
)

/**
 * Where a topic source begins, the first time its consumer group reads a partition.
 *
 * Applied once per partition and committed at once, so a restart, a rebalance or a new instance
 * resumes from where the group got to, never from here again.
 */
enum StartFrom:
  /** The oldest message the broker still holds. */
  case Earliest

  /** After the newest: only what is published from now on. */
  case Latest

  /** The first message published at or after `time`; after the newest when there is none. */
  case At(time: Instant)

  override def toString: String = this match
    case Earliest => "earliest"
    case Latest   => "latest"
    case At(time) => time.toString

/** Available while a view or consumer is handling one change. */
trait ChangeContext:
  /** The source entity's id — CloudEvents calls this the subject. */
  def subject: String

  /** Sequence number for an event-sourced source, or revision for a key value one. */
  def sequenceNumber: Long

  /** Whether the change originated in this region. */
  def localOrigin: Boolean

private[ankka] final case class SimpleChangeContext(
    subject: String,
    sequenceNumber: Long,
    localOrigin: Boolean
) extends ChangeContext
