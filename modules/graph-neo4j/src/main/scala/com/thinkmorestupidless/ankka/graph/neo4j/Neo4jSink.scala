package com.thinkmorestupidless.ankka.graph.neo4j

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
 * The graph merge sink: a consumer of a delta topic (`ankka.graph-delta.v1`) that keeps a Neo4j
 * store in step with it, applying each delta only when its version is newer than the element's in
 * the store. Register it in a service of your own, or deploy the platform's `ankka-graph-sink`
 * image, which registers one from its environment.
 *
 * A record that is not a delta, or breaks a delta's rules, fails the change: it is named in the log
 * with the rule it breaks, the topic source reports it as what the sink is failing on, and it is
 * handed to the sink again until it is handled, so it holds its partition and no other. A delete
 * marker, a record with no value, is passed over: it carries nothing to apply.
 *
 * Raising `version` reads the topic again from its start under a new group: with the store emptied
 * first, that builds it again from the topic alone.
 */
final class Neo4jSink(store: Neo4jStore) extends Consumer[Neo4jSink.Record, Nothing]:

  private val log = LoggerFactory.getLogger(classOf[Neo4jSink])

  def onMessage(record: Neo4jSink.Record): Effect =
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

object Neo4jSink:

  /** A record as read from the topic: its bytes, kept whole so an empty value can be told apart. */
  final case class Record(bytes: Array[Byte])

  /** Keeps the record's bytes; the sink reads the delta itself, so a refusal names the rule. */
  val recordSerializer: Serializer[Record] = new Serializer[Record]:
    val manifest: String                      = GraphDelta.SchemaName
    def toBytes(record: Record): Array[Byte]  = record.bytes
    def fromBytes(bytes: Array[Byte]): Record = Record(bytes)

  val DefaultComponentId: ComponentId = ComponentId("graph-sink")

  /**
   * The sink over `topic`, from the topic's start, reading the partitions an instance holds in
   * parallel unless told otherwise, at `version`: raise it to build the store again.
   */
  def apply(
      topic: String,
      settings: Neo4jSettings,
      version: Int = 1,
      parallel: Boolean = true,
      componentId: ComponentId = DefaultComponentId
  ): Consumer.Companion[Neo4jSink, Record, Nothing] =
    val declaredVersion = version
    new Consumer.Companion[Neo4jSink, Record, Nothing](
      componentId,
      ChangeSource.fromTopic(
        topic,
        recordSerializer,
        StartFrom.Earliest,
        TopicOptions(parallel = parallel)
      )
    ):
      // One store, and so one driver, for every instance of the sink in the service.
      private val store                           = Neo4jStore(settings)
      def create(ctx: ConsumerContext): Neo4jSink = new Neo4jSink(store)
      override def version: Option[Int]           = Some(declaredVersion)
