package com.thinkmorestupidless.ankka.sdk.graph

import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, GraphRules, PropertyValue}
import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeContext,
  ChangeSource,
  Consumer,
  ConsumerContext,
  ConsumerDescriptor
}

/**
 * An element of a graph as its author describes it: a node or an edge as it now is, whole, or a
 * tombstone for one that is gone.
 *
 * It is valid — describing one that the merge sink would refuse fails where it is described — and
 * it has no version yet. It takes the sequence number of the change being handled when it is
 * published, unless `at` states one.
 */
final class GraphElement private[ankka] (
    private[ankka] val template: GraphDelta,
    private[ankka] val stated: Option[Long]
):

  /** Publish at `version` instead of the change's sequence number. At least 1. */
  def at(version: Long): GraphElement =
    new GraphElement(template, Some(GraphRules.stated(GraphRules.describe(template), version)))

  /** `node`, `edge` or `tombstone`. */
  def kind: String = template.kind.name
  def id: String   = template.id

  /**
   * The record key it is published under: `node:<id>` or `edge:<id>`. Never the author's to set.
   */
  def key: String = template.key

  override def toString: String = s"GraphElement(${GraphRules.describe(template)})"

/** `graph` inside a graph consumer: describes elements. Each call validates what it is given. */
final class GraphElements private[ankka] ():

  /** A node as it now is: all its labels and all its properties. */
  def node(
      id: String,
      labels: Seq[String] = Nil,
      properties: Map[String, PropertyValue] = Map.empty
  ): GraphElement =
    new GraphElement(GraphRules.node(id, labels, properties), None)

  /** An edge as it now is, running `from` one node's id `to` another's. */
  def edge(
      id: String,
      `type`: String,
      from: String,
      to: String,
      properties: Map[String, PropertyValue] = Map.empty
  ): GraphElement =
    new GraphElement(GraphRules.edge(id, `type`, from, to, properties), None)

  /**
   * Marks a node deleted. It stays in the graph, marked, so an older delta cannot bring it back.
   */
  def tombstoneNode(id: String): GraphElement =
    new GraphElement(GraphRules.tombstoneNode(id), None)

  /** Marks an edge deleted; its type and endpoints say which edge. */
  def tombstoneEdge(id: String, `type`: String, from: String, to: String): GraphElement =
    new GraphElement(GraphRules.tombstoneEdge(id, `type`, from, to), None)

/** What a graph consumer decided about one change. */
sealed trait GraphEffect

object GraphEffect:
  final case class Publish(elements: Seq[GraphElement]) extends GraphEffect
  case object Done                                      extends GraphEffect
  case object Ignore                                    extends GraphEffect

  /**
   * The deltas a result comes to for a change at `sequenceNumber`: each element at the version it
   * stated, else the change's, and none twice. The runtime's adapter and the test kit both resolve
   * a result through this.
   */
  private[ankka] def resolve(effect: GraphEffect, sequenceNumber: Long): Vector[GraphDelta] =
    effect match
      case Publish(elements) =>
        GraphRules.resolve(elements.map(e => e.template -> e.stated), sequenceNumber)
      case Done | Ignore => Vector.empty

/** `effects` inside a graph consumer. There is no `produce`: a graph consumer publishes deltas. */
final class GraphEffects private[ankka] ():

  /** Publish these elements as they now are. None at all is handled with nothing published. */
  def publish(elements: GraphElement*): GraphEffect = GraphEffect.Publish(elements)

  /** Handled, nothing to publish. */
  def done(): GraphEffect = GraphEffect.Done

  /** Not of interest. */
  def ignore(): GraphEffect = GraphEffect.Ignore

/**
 * A consumer that publishes its source as a graph.
 *
 * For each change, say which elements it leaves in which state and return them. Each is published
 * to the consumer's topic as a delta of `ankka.graph-delta.v1`, the contract of ankka-flow's merge
 * sink: keyed by its element, versioned by the change's sequence number. There is no JSON, key or
 * version to write.
 *
 * Three rules are the author's to keep, because nothing can check them:
 *   - an element is its **whole** state, not what changed: properties left out are removed;
 *   - an element has **one** writing entity: do not publish a node another entity owns — publish
 *     the edge, and let that entity publish the node;
 *   - ids are global: prefix them by the kind of thing (`cart:`, `checkout:`).
 *
 * A change delivered again publishes the same deltas at the same versions, which the sink passes
 * over, so publishing a graph is safe to repeat as it stands.
 */
abstract class GraphConsumer[Src]:

  final type Effect = GraphEffect

  protected final val graph: GraphElements  = new GraphElements
  protected final val effects: GraphEffects = new GraphEffects

  private var contextOpt: Option[ChangeContext] = None

  /** The change being handled: its subject, the source entity's id, and its sequence number. */
  protected final def messageContext: ChangeContext =
    contextOpt.getOrElse(
      throw IllegalStateException("messageContext is only available while handling a change")
    )

  def onMessage(message: Src): Effect

  /** The source entity was deleted. Publish its elements' tombstones here. */
  def onDelete: Effect = effects.ignore()

  private[ankka] def _setContext(ctx: Option[ChangeContext]): Unit = contextOpt = ctx

object GraphConsumer:

  /**
   * Declares a graph consumer to the runtime. What is registered is an ordinary consumer that
   * publishes to `topic`: the same descriptor, startup checks, sharding and delivery.
   *
   * The topic must be compacted to hold the graph. ankka creates no topics: declare it in the
   * ankka-flow pipeline that reads it, which creates it compacted.
   */
  abstract class Companion[C <: GraphConsumer[Src], Src](
      val componentId: ComponentId,
      val source: ChangeSource[Src],
      val topic: String
  ):

    def create(ctx: ConsumerContext): C

    def parallelism: Int = 4

    /**
     * Raise it to have this graph consumer read its topic again from its start position, under a
     * consumer group of its own. `None` is version 1. Only for a topic source: a version on one
     * that reads an entity is refused when the service starts.
     */
    def version: Option[Int] = None

    final def descriptor: ConsumerDescriptor[Consumer[Src, GraphDelta], Src, GraphDelta] =
      if topic.isEmpty then
        throw IllegalArgumentException(
          s"graph consumer '$componentId' names no topic to publish to"
        )
      else
        ConsumerDescriptor(
          componentId,
          source,
          Some(GraphDelta.serializer),
          Some(topic),
          ctx => new Adapter(create(ctx)),
          parallelism,
          version = version
        )

  /** The attributes of a delta's record: what it is, for anything that reads the topic. */
  private[ankka] val DeltaMetadata: Metadata =
    Metadata.empty.set(Metadata.CeType, GraphDelta.SchemaName)

  /** A graph consumer as the consumer the runtime runs. */
  private final class Adapter[Src](underlying: GraphConsumer[Src])
      extends Consumer[Src, GraphDelta]:

    def onMessage(message: Src): Effect = run(underlying.onMessage(message))

    override def onDelete: Effect = run(underlying.onDelete)

    private def run(handle: => GraphEffect): Effect =
      val context = messageContext
      underlying._setContext(Some(context))
      try
        handle match
          case GraphEffect.Done             => effects.done()
          case GraphEffect.Ignore           => effects.ignore()
          case publish: GraphEffect.Publish =>
            // Versions and duplicates are settled here, once the handler has returned, so a
            // refusal fails the change before anything of it is published.
            effects.produceAll(
              GraphEffect
                .resolve(publish, context.sequenceNumber)
                .map(delta => effects.message(delta).withKey(delta.key).withMetadata(DeltaMetadata))
            )
      finally underlying._setContext(None)
