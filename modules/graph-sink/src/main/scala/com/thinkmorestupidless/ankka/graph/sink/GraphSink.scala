package com.thinkmorestupidless.ankka.graph.sink

import com.thinkmorestupidless.ankka.core.graph.GraphDelta
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  Consumer,
  ConsumerContext,
  StartFrom,
  TopicOptions
}
import org.slf4j.LoggerFactory

/**
 * The graph sink: a consumer of a delta topic (`ankka.graph-delta.v1`) that keeps a `GraphStore` in
 * step with it, applying each delta under the contract's rules. Register it in a service of your
 * own with the store you want: the in-memory one, a store over a database such as the Neo4j store
 * in ankka-contrib, or one of your own.
 *
 * A record that is not a delta, or breaks a delta's rules, fails the change: it is named in the log
 * with the rule it breaks, the topic source reports it as what the sink is failing on, and it is
 * handed to the sink again until it is handled, so it holds its partition and no other. A delete
 * marker, a record with no value, is passed over: it carries nothing to apply.
 *
 * Raising `version` reads the topic again from its start under a new group: with the store emptied
 * first, that builds it again from the topic alone.
 */
final class GraphSink(store: GraphStore) extends Consumer[GraphSink.Record, Nothing]:

  private val log = LoggerFactory.getLogger(classOf[GraphSink])

  def onMessage(record: GraphSink.Record): Effect =
    if record.bytes.isEmpty then effects.ignore()
    else
      GraphDelta.read(record.bytes) match
        case Left(why) =>
          val message = s"delta refused for '${messageContext.subject}': $why"
          log.warn(message)
          throw IllegalArgumentException(message)
        case Right(delta) =>
          store.apply(delta)
          effects.done()

object GraphSink:

  /** A record as read from the topic: its bytes, kept whole so an empty value can be told apart. */
  final case class Record(bytes: Array[Byte])

  /** Keeps the record's bytes; the sink reads the delta itself, so a refusal names the rule. */
  val recordSerializer: Serializer[Record] = new Serializer[Record]:
    val manifest: String                      = GraphDelta.SchemaName
    def toBytes(record: Record): Array[Byte]  = record.bytes
    def fromBytes(bytes: Array[Byte]): Record = Record(bytes)

  val DefaultComponentId: ComponentId = ComponentId("graph-sink")

  /**
   * The sink over `topic` into `store`, from the topic's start, reading the partitions an instance
   * holds in parallel unless told otherwise, at `version`: raise it to build the store again. One
   * store serves every instance of the sink in the service.
   */
  def apply(
      topic: String,
      store: GraphStore,
      version: Int = 1,
      parallel: Boolean = true,
      componentId: ComponentId = DefaultComponentId
  ): Consumer.Companion[GraphSink, Record, Nothing] =
    val declaredVersion = version
    new Consumer.Companion[GraphSink, Record, Nothing](
      componentId,
      ChangeSource.fromTopic(
        topic,
        recordSerializer,
        StartFrom.Earliest,
        TopicOptions(parallel = parallel)
      )
    ):
      def create(ctx: ConsumerContext): GraphSink = new GraphSink(store)
      override def version: Option[Int]           = Some(declaredVersion)
