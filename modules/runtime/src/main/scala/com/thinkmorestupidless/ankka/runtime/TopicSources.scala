package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentKind
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.pekko.actor.typed.{ActorSystem, Extension, ExtensionId}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * One view or consumer reading a topic, as the service tells anyone who asks: what the log lines
 * say, the metrics, and the local console's answer.
 *
 * `recordedVersion` is a view's, `None` for a consumer. `behind` is a view whose recorded version
 * is higher than this instance declares: it reads nothing and writes nothing.
 */
final case class TopicSourceStatus(
    kind: ComponentKind,
    componentId: String,
    topic: String,
    group: String,
    startFrom: StartFrom,
    version: Int,
    recordedVersion: Option[Int],
    behind: Boolean,
    /** Feature 037: the declared broker the topic is on, and the contract the component states. */
    broker: Option[String] = None,
    contract: Option[String] = None,
    /** Feature 037: messages the topic holds past the last one handled, as of the last poll. */
    lag: Option[Long] = None,
    /** Feature 037: the reason of the change being delivered again, until one succeeds. */
    failing: Option[String] = None,
    /** Feature 043: what the broker still holds of the topic, as of the last time it was asked. */
    gap: Option[RetentionGap] = None
):
  def kindWord: String = if kind == ComponentKind.View then "view" else "consumer"

/** Where one partition begins on the broker, and when its earliest message was published. */
final case class PartitionGap(
    partition: Int,
    beginning: Long,
    earliestAt: Option[java.time.Instant]
)

/**
 * What a topic source's topic no longer holds (feature 043): each partition's beginning and
 * earliest retained time, whether the topic is compacted, and whether earlier messages are gone,
 * which is so when the topic is not compacted and a partition begins above zero or holds nothing
 * from before the view's version was built. Nothing here claims to know what was written first.
 */
final case class RetentionGap(
    partitions: Vector[PartitionGap],
    compacted: Boolean,
    gone: Boolean,
    readAt: java.time.Instant
)

object RetentionGap:

  /**
   * From what the broker holds: `since` is when the reader's view was built, `None` for a consumer
   * or a view with no such time, which leaves only the beginning to say whether anything is gone.
   */
  def of(
      retained: Map[Int, Retained],
      compacted: Boolean,
      since: Option[java.time.Instant],
      readAt: java.time.Instant
  ): RetentionGap =
    val partitions =
      retained.toVector.sortBy(_._1).map((p, r) => PartitionGap(p, r.beginning, r.earliestAt))
    val dropped = partitions.exists(_.beginning > 0)
    val later   = since.exists(s => partitions.exists(_.earliestAt.exists(_.isAfter(s))))
    RetentionGap(partitions, compacted, gone = !compacted && (dropped || later), readAt)

  def json(gap: RetentionGap): String =
    val partitions = gap.partitions
      .map(p =>
        s"""{"partition":${p.partition},"beginning":${p.beginning},""" +
          s""""earliestRetained":${p.earliestAt.fold("null")(t => Json.str(t.toString))}}"""
      )
      .mkString("[", ",", "]")
    s"""{"partitions":$partitions,"compacted":${gap.compacted},"gone":${gap.gone},""" +
      s""""readAt":${Json.str(gap.readAt.toString)}}"""

/**
 * The topic sources of this service. Its keys are declared component ids, so it is bounded by the
 * service's registration, and it is not the recorder's name table: nothing here is interned.
 */
final class TopicSources extends Extension:

  private val sources = ConcurrentHashMap[String, TopicSourceStatus]()

  private[runtime] def put(status: TopicSourceStatus): Unit =
    sources.put(status.componentId, status): Unit

  private[runtime] def update(componentId: String)(
      change: TopicSourceStatus => TopicSourceStatus
  ): Unit =
    sources.computeIfPresent(componentId, (_, current) => change(current)): Unit

  /** Every topic source, by component id. */
  def all: Vector[TopicSourceStatus] = sources.values.asScala.toVector.sortBy(_.componentId)

object TopicSources extends ExtensionId[TopicSources]:
  def createExtension(system: ActorSystem[?]): TopicSources = new TopicSources

  /**
   * One topic source as JSON, the same in the service document the local console reads and the
   * topology the control plane reads over the observe port.
   */
  def json(s: TopicSourceStatus): String =
    s"""{"kind":${Json.str(s.kindWord)},"component":${Json.str(s.componentId)},""" +
      s""""topic":${Json.str(s.topic)},"group":${Json.str(s.group)},""" +
      s""""start":${Json.str(s.startFrom.toString)},"version":${s.version},""" +
      s""""recordedVersion":${s.recordedVersion.fold("null")(_.toString)},""" +
      s""""behind":${s.behind},""" +
      s""""broker":${s.broker.fold("null")(Json.str)},""" +
      s""""contract":${s.contract.fold("null")(Json.str)},""" +
      s""""lag":${s.lag.fold("null")(_.toString)},""" +
      s""""failing":${s.failing.fold("null")(Json.str)},""" +
      s""""gap":${s.gap.fold("null")(RetentionGap.json)}}"""
