package com.thinkmorestupidless.ankka.core.effect

import com.thinkmorestupidless.ankka.core.Metadata

/**
 * What a consumer should do with the message it just handled.
 *
 * All four advance the projection offset. The difference is what leaves the service: `Produce`
 * emits one message downstream, `ProduceAll` several, the other two none. Failing is *not* modelled
 * here on purpose — a consumer that throws must not advance its offset, so failure is an exception,
 * and the runtime redelivers.
 */
sealed trait ConsumerEffect[+Out]

object ConsumerEffect:
  final case class Produce[Out](payload: Out, metadata: Metadata) extends ConsumerEffect[Out]

  /**
   * Several messages for one change, published in the order given. The change counts as handled
   * when the broker has accepted every one; if one is refused the change comes again and all are
   * published again. None at all behaves as `Done`.
   */
  final case class ProduceAll[Out](messages: Seq[Outgoing[Out]]) extends ConsumerEffect[Out]
  case object Done                                               extends ConsumerEffect[Nothing]
  case object Ignore                                             extends ConsumerEffect[Nothing]

  /**
   * What an effect asks to have published, in order: one message for `Produce`, as many as it holds
   * for `ProduceAll`, none otherwise. The runtime and the test kit both read an effect through
   * this, so they cannot disagree about what a handler produced.
   */
  def outgoing[Out](effect: ConsumerEffect[Out]): Seq[Outgoing[Out]] = effect match
    case Produce(payload, metadata) => Seq(Outgoing(payload, metadata, None))
    case ProduceAll(messages)       => messages
    case Done | Ignore              => Nil

/**
 * One message of several: what to publish, its CloudEvents attributes and other headers, and
 * optionally the record key it is published under.
 *
 * A message with no key is keyed by its subject — the source entity's id unless the metadata sets
 * `ce-subject` — which is what keeps everything about one entity on one partition, in order. A key
 * is for a message about something else: a line item, an element of a graph. Naming one does not
 * change the subject.
 */
final case class Outgoing[+Out](
    payload: Out,
    metadata: Metadata = Metadata.empty,
    key: Option[String] = None
):

  /** Publish under `key` instead of the subject. */
  def withKey(key: String): Outgoing[Out] =
    require(key.nonEmpty, "a record key must not be empty; leave it out to key by the subject")
    copy(key = Some(key))

  def withMetadata(metadata: Metadata): Outgoing[Out] = copy(metadata = metadata)

/** The `effects` surface inside a consumer. */
final class ConsumerEffects[Out] private[ankka] ():

  /** Publish downstream — to a topic, or to a service-to-service stream. */
  def produce(payload: Out): ConsumerEffect[Out] =
    ConsumerEffect.Produce(payload, Metadata.empty)

  /**
   * Publish with explicit metadata. Set `ce-subject` to the entity id to preserve per-entity
   * ordering on the broker.
   */
  def produce(payload: Out, metadata: Metadata): ConsumerEffect[Out] =
    ConsumerEffect.Produce(payload, metadata)

  /**
   * One message of several, for `produceAll`: add a key with `withKey`, headers with
   * `withMetadata`.
   */
  def message(payload: Out): Outgoing[Out] = Outgoing(payload)

  /**
   * Publish several messages for this change, in order. The change is handled when all of them are
   * accepted. An empty list publishes nothing and is handled at once.
   */
  def produceAll(messages: Seq[Outgoing[Out]]): ConsumerEffect[Out] =
    ConsumerEffect.ProduceAll(messages)

  /** Handled successfully, nothing to emit. */
  def done(): ConsumerEffect[Out] = ConsumerEffect.Done

  /** Not interesting to this consumer. */
  def ignore(): ConsumerEffect[Out] = ConsumerEffect.Ignore
