package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteConsumerDescriptor,
  RemoteKeyedViewDescriptor,
  RemoteSource,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  ConsumerDescriptor,
  KeyedViewDescriptor,
  ViewDescriptor
}

/**
 * What a view or consumer may declare about a topic it reads, checked once for every component
 * however it arrived: written in Scala, or discovered from another process. A service that breaks a
 * rule does not start, and the problem names the component.
 *
 * The rules discovery can also be broken in — a start position on a source that reads an entity, a
 * start position that names nothing — are checked where the wire is read, since a Scala declaration
 * cannot spell them.
 */
private[ankka] object TopicSourceRules:

  def problems(descriptors: Seq[ComponentDescriptor]): Vector[String] =
    descriptors.toVector.flatMap {
      case c: ConsumerDescriptor[?, ?, ?] =>
        val start = c.source match
          case t: ChangeSource.Topic[?] if t.startFrom.isEmpty =>
            Vector(noStartPosition(c.componentId, t.topic))
          case _ => Vector.empty
        start ++ versionProblems("consumer", c.componentId, c.version, readsOf(c.source))
      case c: RemoteConsumerDescriptor =>
        val start = c.source match
          case RemoteSource.Topic(topic, None) if c.startDeclarable =>
            Vector(noStartPosition(c.componentId, topic))
          case _ => Vector.empty
        start ++ versionProblems("consumer", c.componentId, c.version, readsOf(c.source))
      // A view of any source may declare a version: one that reads entities is rebuilt from their
      // journals when it is raised, as one that reads a topic is from the broker.
      case v: ViewDescriptor[?, ?, ?] => versionProblems("view", v.componentId, v.version, None)
      case v: RemoteViewDescriptor    => versionProblems("view", v.componentId, v.version, None)
      case v: KeyedViewDescriptor[?, ?] =>
        versionProblems("view", v.componentId, v.version, None)
      case v: RemoteKeyedViewDescriptor =>
        versionProblems("view", v.componentId, v.version, None)
      case _ => Vector.empty
    }

  /** What a source reads, as a problem names it: `None` for a topic. */
  private def readsOf(source: ChangeSource[?]): Option[String] = source match
    case _: ChangeSource.Topic[?] => None
    case other: ChangeSource[?]   => Some(other.describe)

  private def readsOf(source: RemoteSource): Option[String] = source match
    case RemoteSource.Topic(_, _)         => None
    case RemoteSource.Component(kind, id) => Some(s"$kind($id)")

  /**
   * A version says which generation of a handler built what a source holds. A consumer that reads
   * an entity has no group to change and nothing to rebuild, and would accept a version and do
   * nothing with it, which is the silence this rule refuses. A view's version is checked only to be
   * a whole number of 1 or more.
   */
  private def versionProblems(
      kind: String,
      componentId: String,
      version: Option[Int],
      readsComponent: Option[String]
  ): Vector[String] =
    version match
      case None => Vector.empty
      case Some(v) if v < 1 =>
        Vector(
          s"$kind '$componentId' declares version $v; a version is a whole number of 1 or more"
        )
      case Some(_) =>
        readsComponent
          .map(reads =>
            s"$kind '$componentId' declares a version, which applies to a topic; it reads $reads"
          )
          .toVector

  /**
   * A consumer acts on each message, and neither default is right for that: the earliest message
   * replays everything the broker holds through the action, the latest silently skips the backlog.
   */
  def noStartPosition(componentId: String, topic: String): String =
    s"consumer '$componentId' reads topic '$topic' and declares no start position; declare " +
      "earliest, latest or a time"
