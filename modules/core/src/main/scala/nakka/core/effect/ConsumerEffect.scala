package nakka.core.effect

import nakka.core.Metadata

/**
 * What a consumer should do with the message it just handled.
 *
 * All three advance the projection offset. The difference is what leaves the service: `Produce`
 * emits downstream, the other two do not. Failing is *not* modelled here on purpose — a consumer
 * that throws must not advance its offset, so failure is an exception, and the runtime redelivers.
 */
sealed trait ConsumerEffect[+Out]

object ConsumerEffect:
  final case class Produce[Out](payload: Out, metadata: Metadata) extends ConsumerEffect[Out]
  case object Done                                                extends ConsumerEffect[Nothing]
  case object Ignore                                              extends ConsumerEffect[Nothing]

/** The `effects` surface inside a consumer. */
final class ConsumerEffects[Out] private[nakka] ():

  /** Publish downstream — to a topic, or to a service-to-service stream. */
  def produce(payload: Out): ConsumerEffect[Out] =
    ConsumerEffect.Produce(payload, Metadata.empty)

  /**
   * Publish with explicit metadata. Set `ce-subject` to the entity id to preserve per-entity
   * ordering on the broker.
   */
  def produce(payload: Out, metadata: Metadata): ConsumerEffect[Out] =
    ConsumerEffect.Produce(payload, metadata)

  /** Handled successfully, nothing to emit. */
  def done(): ConsumerEffect[Out] = ConsumerEffect.Done

  /** Not interesting to this consumer. */
  def ignore(): ConsumerEffect[Out] = ConsumerEffect.Ignore
