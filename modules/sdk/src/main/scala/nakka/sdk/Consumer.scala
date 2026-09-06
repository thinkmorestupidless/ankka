package nakka.sdk

import nakka.core.*
import nakka.core.effect.*

/**
 * Reacts to changes from one source, optionally publishing something onward.
 *
 * Delivery is at-least-once and the same message is redelivered until the handler
 * returns without throwing — so a consumer must tolerate seeing a message twice.
 * Deduplication is the developer's to implement, because only the developer knows
 * whether a repeat is harmless.
 */
abstract class Consumer[Src, Out]:

  private var contextOpt: Option[ChangeContext] = None

  final type Effect = ConsumerEffect[Out]

  protected final val effects: ConsumerEffects[Out] = new ConsumerEffects[Out]()

  protected final def messageContext: ChangeContext =
    contextOpt.getOrElse(
      throw IllegalStateException("messageContext is only available while handling a message")
    )

  /** Handles one message. Throwing means "redeliver me". */
  def onMessage(message: Src): Effect

  /** Called when the source entity is deleted. Ignored unless overridden. */
  def onDelete: Effect = effects.ignore()

  private[nakka] def _setContext(ctx: Option[ChangeContext]): Unit = contextOpt = ctx

object Consumer:

  /**
   * Declares a consumer to the runtime.
   *
   * `Out` is `Nothing` for a consumer that only reacts; give it a type and a
   * `produceTo` target to turn it into a publisher.
   */
  abstract class Companion[C <: Consumer[Src, Out], Src, Out](
      val componentId: ComponentId,
      val source: ChangeSource[Src]
  ):

    def create(ctx: ConsumerContext): C

    /** Encoder for produced messages. Required if this consumer produces anything. */
    def outputSerializer: Option[Serializer[Out]] = None

    /** Topic to publish `effects.produce(...)` output to. */
    def produceTo: Option[String] = None

    def parallelism: Int = 4

    final def descriptor: ConsumerDescriptor[C, Src, Out] =
      if produceTo.isDefined && outputSerializer.isEmpty then
        throw IllegalArgumentException(
          s"consumer '$componentId' publishes to '${produceTo.get}' but declares no " +
            "outputSerializer, so its messages could not be encoded"
        )
      else
        ConsumerDescriptor(
          componentId,
          source,
          outputSerializer,
          produceTo,
          create,
          parallelism
        )

/** The registered form of a consumer. */
final case class ConsumerDescriptor[C <: Consumer[Src, Out], Src, Out](
    componentId: ComponentId,
    source: ChangeSource[Src],
    outputSerializer: Option[Serializer[Out]],
    produceTo: Option[String],
    create: ConsumerContext => C,
    parallelism: Int
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.Consumer
