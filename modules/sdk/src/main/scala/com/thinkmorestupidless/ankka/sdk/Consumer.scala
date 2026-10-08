package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*

/**
 * Reacts to changes from one source, optionally publishing something onward.
 *
 * Delivery is at-least-once and the same message is redelivered until the handler returns without
 * throwing — so a consumer must tolerate seeing a message twice. Deduplication is the developer's
 * to implement, because only the developer knows whether a repeat is harmless.
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

  private[ankka] def _setContext(ctx: Option[ChangeContext]): Unit = contextOpt = ctx

object Consumer:

  /**
   * Declares a consumer to the runtime.
   *
   * `Out` is `Nothing` for a consumer that only reacts; give it a type and a `produceTo` target to
   * turn it into a publisher.
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

    /**
     * The topic this consumer publishes to, with its contract and broker. By default the one
     * `produceTo` names; a consumer that states a contract or a broker overrides this and leaves
     * `produceTo` alone.
     */
    def produces: Option[Publication] = produceTo.map(Publication(_))

    def parallelism: Int = 4

    /**
     * Raise it to have this consumer read its topic again from its start position, under a consumer
     * group of its own. `None` is version 1. Only for a topic source: a version on one that reads
     * an entity is refused when the service starts.
     */
    def version: Option[Int] = None

    final def descriptor: ConsumerDescriptor[C, Src, Out] =
      val publication = produces
      if publication.isDefined && outputSerializer.isEmpty then
        throw IllegalArgumentException(
          s"consumer '$componentId' publishes to '${publication.get.topic}' but declares no " +
            "outputSerializer, so its messages could not be encoded"
        )
      else if produceTo.exists(t => publication.exists(_.topic != t)) then
        throw IllegalArgumentException(
          s"consumer '$componentId' names '${produceTo.get}' in produceTo and " +
            s"'${publication.get.topic}' in produces; a consumer publishes to one topic"
        )
      else
        ConsumerDescriptor(
          componentId,
          source,
          outputSerializer,
          publication.map(_.topic),
          create,
          parallelism,
          version = version,
          produces = publication
        )

/** The registered form of a consumer. */
final case class ConsumerDescriptor[C <: Consumer[Src, Out], Src, Out](
    componentId: ComponentId,
    source: ChangeSource[Src],
    outputSerializer: Option[Serializer[Out]],
    produceTo: Option[String],
    create: ConsumerContext => C,
    parallelism: Int,
    override val platform: Boolean = false,
    version: Option[Int] = None,
    produces: Option[Publication] = None
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.Consumer

  override def declaredHandlers: Vector[DeclaredHandler] = Vector(ConsumerDescriptor.OnMessage)

object ConsumerDescriptor:

  /** A consumer has one handler, under one name in every language: what it does with a message. */
  val OnMessage: DeclaredHandler = DeclaredHandler("on-message", HandlerKind.Update)
