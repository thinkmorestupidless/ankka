package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Socket
import com.thinkmorestupidless.ankka.runtime.AnkkaSerializable
import com.thinkmorestupidless.ankka.runtime.erasure.ChannelWire
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * An erasure every keyring instance must tell its channels of. Crosses the keyring's own cluster.
 */
final case class Broadcast(
    erasureId: String,
    project: String,
    subject: String,
    sequence: Long,
    reapply: Boolean
) extends AnkkaSerializable

/** One service instance's open channel, on this keyring instance. */
final class OpenChannel(
    val id: String,
    val project: String,
    val service: String,
    val instance: String,
    val reads: Set[String],
    socket: Socket
):
  def send(frame: ChannelWire.In): Unit =
    socket.synchronized(socket.send(ChannelWire.writeIn(frame)))
  @volatile var closed: Boolean = false

/**
 * The channels this keyring instance holds, and the fan-out of an erasure to every channel of every
 * instance (FR-022): a destroyed notice to every channel that reads the project, an apply order to
 * every service of it, and — 60 seconds on — a `Close("unacknowledged")` to any channel that has
 * not acknowledged the notice, which makes that service drop its whole cache.
 */
final class Channels(client: () => ComponentClient, ackWithin: FiniteDuration = 60.seconds):

  private val open      = ConcurrentHashMap[String, OpenChannel]()
  private val acked     = ConcurrentHashMap.newKeySet[(String, String)]()
  private val scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
  @volatile private var topic: Option[ActorRef[Topic.Command[Broadcast]]] = None

  def start(system: ActorSystem[?]): Unit =
    val t =
      system.systemActorOf(Topic[Broadcast]("ankka-keyring-erasures"), "ankka-keyring-erasures")
    val receiver = system.systemActorOf(
      Behaviors.receiveMessage[Broadcast] { b =>
        deliver(b)
        Behaviors.same
      },
      "ankka-keyring-channels"
    )
    t ! Topic.Subscribe(receiver)
    topic = Some(t)

  def stop(): Unit = scheduler.shutdownNow(): Unit

  def size: Int = open.size()

  def register(channel: OpenChannel): Unit = open.put(channel.id, channel): Unit
  def unregister(channel: OpenChannel): Unit =
    channel.closed = true
    open.remove(channel.id): Unit

  /** Every keyring instance tells its channels, this one included. */
  def broadcast(b: Broadcast): Unit = topic match
    case Some(t) => t ! Topic.Publish(b)
    case None    => deliver(b)

  def acknowledged(channel: OpenChannel, erasureId: String): Unit =
    acked.add((channel.id, erasureId)): Unit
    record(
      client()
        .forEventSourcedEntity(EntityId(erasureId))
        .call(ErasureEntity.acknowledged)
        .invoke(channel.id)
    )

  private def deliver(b: Broadcast): Unit =
    open.values.asScala.foreach { channel =>
      val ownProject = channel.project == b.project
      if ownProject || channel.reads(b.project) then
        try
          record(
            client()
              .forEventSourcedEntity(EntityId(b.erasureId))
              .call(ErasureEntity.notified)
              .invoke(Notify(channel.id, channel.service, channel.instance))
          )
          channel.send(ChannelWire.In.Destroyed(b.project, b.subject, b.erasureId))
          if ownProject then
            channel.send(ChannelWire.In.Apply(b.erasureId, b.sequence, b.subject, b.reapply))
          scheduler.schedule(
            (() => unacknowledged(channel, b.erasureId)): Runnable,
            ackWithin.toMillis,
            TimeUnit.MILLISECONDS
          ): Unit
        catch case NonFatal(_) => unacknowledged(channel, b.erasureId)
    }

  private def unacknowledged(channel: OpenChannel, erasureId: String): Unit =
    if !acked.contains((channel.id, erasureId)) && !channel.closed then
      try channel.send(ChannelWire.In.Close("unacknowledged"))
      catch case NonFatal(_) => ()
      unregister(channel)
      record(
        client()
          .forEventSourcedEntity(EntityId(erasureId))
          .call(ErasureEntity.unacknowledged)
          .invoke(channel.id)
      )

  private def record(work: => Any): Unit =
    try work: Unit
    catch case NonFatal(_) => ()
