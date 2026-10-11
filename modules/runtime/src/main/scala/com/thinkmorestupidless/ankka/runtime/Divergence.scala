package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.actor.typed.{ActorSystem, Extension, ExtensionId}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * What a restore cannot rewind (feature 041): the messages on the topics a service publishes to and
 * reads that are newer than a moment, and how far each of its groups has read past it.
 *
 * A restore takes back a service's journal and read positions; the broker keeps everything. This is
 * what the service itself can say about that, asked by the control plane over the observe port when
 * a restore is verified: per topic, how many messages each partition holds after the moment, and
 * per group, how many of those it has already read. Nothing is changed and nothing is committed. A
 * broker that cannot be asked answers nothing for its topics, never zero.
 */
final class Divergence extends Extension:

  /** Each (topic, group) this service touches, with the subscriber that can ask its broker. */
  private val sources = ConcurrentHashMap[(String, Option[String]), MessageSubscriber]()

  /** A topic this service publishes to (`group` none) or reads under `group`. */
  def touch(topic: String, group: Option[String], subscriber: MessageSubscriber): Unit =
    sources.put((topic, group), subscriber): Unit

  def since(at: Instant)(using ExecutionContext): Future[Vector[Divergence.Entry]] =
    val asked = sources.asScala.toVector.sortBy(_._1).map { case ((topic, group), subscriber) =>
      subscriber
        .positionsSince(topic, group, at)
        .map(_.map(p => Divergence.Entry(topic, group, p.after, p.read)))
        .recover { case NonFatal(_) => None }
    }
    Future.sequence(asked).map(_.flatten)

object Divergence extends ExtensionId[Divergence]:

  def createExtension(system: ActorSystem[?]): Divergence = new Divergence

  /**
   * Messages on `topic` after the moment (`after`), and for a group, how many of those it has read
   * (`read`, none for a topic the service only publishes to).
   */
  final case class Entry(topic: String, group: Option[String], after: Long, read: Option[Long])

  /** What a broker says of one topic since a moment. */
  final case class Positions(after: Long, read: Option[Long])

  def json(entries: Vector[Entry]): String =
    entries
      .map(e =>
        s"""{"topic":${Json.str(e.topic)},"group":${e.group.fold("null")(Json.str)},""" +
          s""""after":${e.after},"read":${e.read.fold("null")(_.toString)}}"""
      )
      .mkString("[", ",", "]")
