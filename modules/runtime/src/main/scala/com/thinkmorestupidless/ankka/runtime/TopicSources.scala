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
    failing: Option[String] = None
):
  def kindWord: String = if kind == ComponentKind.View then "view" else "consumer"

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
