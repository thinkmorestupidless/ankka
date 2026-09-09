package nakka.sdk

import nakka.core.{ComponentId, Serializer}

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

  /** Messages from a broker topic. */
  final case class Topic[Src](topic: String, decoder: Serializer[Src]) extends ChangeSource[Src]:
    def describe = s"topic($topic)"

  def eventsOf[C <: EventSourcedEntity[S, E], S, E](
      companion: EventSourcedEntity.Companion[C, S, E]
  ): ChangeSource[E] =
    EventSourced(companion.componentId, companion.eventSerializer)

  def stateOf[C <: KeyValueEntity[S], S](
      companion: KeyValueEntity.Companion[C, S]
  ): ChangeSource[S] =
    KeyValue(companion.componentId, companion.stateSerializer)

  def fromTopic[Src](name: String, decoder: Serializer[Src]): ChangeSource[Src] =
    Topic(name, decoder)

/** Available while a view or consumer is handling one change. */
trait ChangeContext:
  /** The source entity's id — CloudEvents calls this the subject. */
  def subject: String

  /** Sequence number for an event-sourced source, or revision for a key value one. */
  def sequenceNumber: Long

  /** Whether the change originated in this region. */
  def localOrigin: Boolean

private[nakka] final case class SimpleChangeContext(
    subject: String,
    sequenceNumber: Long,
    localOrigin: Boolean
) extends ChangeContext
