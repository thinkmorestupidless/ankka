package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.replication.{
  Advanced,
  DatabaseReplicationTransport,
  ForwardedCall,
  ForwardedReply,
  Located,
  LoopbackReplication,
  ReplicationTransport,
  ReplicationTransportFactory,
  Replicated
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done, NotUsed}

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * The runtime's own replication transport between a test kit's installations, with what a test
 * needs to make the network misbehave (feature 051):
 *
 *   - `pause(installation)` stalls everything that reaches `installation`: records it pulls, and
 *     the wake feed telling it another installation wrote. Nothing is lost; `resume` lets it all
 *     through in order.
 *   - `block(from, to)` makes `to` unreachable from `from`: every call `from` makes to it fails at
 *     once, and its records and wakes stop arriving at `from`. `unblock` ends it.
 *
 * Each installation of a group has its own transport, made by `factory`; they share this one's
 * switches.
 */
final class TestReplicationTransport private[testkit] (
    val installations: Vector[String],
    supplied: Boolean = false
):

  /** A group running over the test's own transport has none of these switches to throw. */
  private def switch(name: String): Unit =
    if supplied then
      throw UnsupportedOperationException(
        s"$name belongs to the runtime's own replication transport; the group " +
          s"${installations.mkString(", ")} runs over the transport given to `using`"
      )

  private val lock                               = Object()
  private var paused: Map[String, Promise[Unit]] = Map.empty
  private var blocked: Set[(String, String)]     = Set.empty

  /** Stalls every record and wake on its way to `installation` until `resume`. */
  def pause(installation: String): Unit = lock.synchronized {
    switch("pause")
    if !paused.contains(installation) then paused += installation -> Promise[Unit]()
  }

  /** Lets what was stalled on its way to `installation` through, in order. */
  def resume(installation: String): Unit = lock.synchronized {
    switch("resume")
    paused.get(installation).foreach(_.trySuccess(()))
    paused -= installation
  }

  /** Whether `installation`'s transport is paused. */
  def isPaused(installation: String): Boolean = lock.synchronized(paused.contains(installation))

  /** Makes `to` unreachable from `from` until `unblock`. */
  def block(from: String, to: String): Unit = lock.synchronized {
    switch("block")
    blocked += from -> to
  }

  @volatile private var loopback = false

  /**
   * Has the installations of the group reach one another as two services on one machine do, through
   * the routes their local console endpoints serve, rather than in the JVM. Set before the
   * installations start.
   */
  def loopbackOnly(on: Boolean): Unit =
    switch("loopbackOnly")
    loopback = on

  /** Makes `to` reachable from `from` again. */
  def unblock(from: String, to: String): Unit = lock.synchronized {
    switch("unblock")
    blocked -= from -> to
  }

  private def isBlocked(from: String, to: String): Boolean =
    lock.synchronized(blocked.contains(from -> to))

  /** Completes when nothing holds traffic to `installation` back. */
  private def open(installation: String): Future[Unit] =
    lock.synchronized(paused.get(installation)).fold(Future.unit)(_.future)

  /** The transport of the installation `self`, made once its service's actor system exists. */
  private[testkit] def factory(self: String): ReplicationTransportFactory =
    (system, settings, components) =>
      Controlled(
        self,
        DatabaseReplicationTransport(
          settings,
          components,
          LoopbackReplication.local(system),
          inJvm = !loopback
        )(using system)
      )(using system.executionContext)

  private def unreachable(from: String, to: String): Exception =
    IllegalStateException(s"'$to' cannot be reached from '$from' (blocked by the test)")

  private final class Controlled(self: String, underlying: DatabaseReplicationTransport)(using
      ExecutionContext
  ) extends ReplicationTransport:

    def name: String = underlying.name

    def records(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        from: Long
    ): Source[Replicated, NotUsed] =
      if isBlocked(self, installation) then Source.failed(unreachable(self, installation))
      else
        underlying
          .records(installation, componentId, entityId, from)
          .mapAsync(1)(record => open(self).map(_ => record))
          .map { record =>
            if isBlocked(self, installation) then throw unreachable(self, installation)
            record
          }

    def forward(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        call: ForwardedCall
    ): Future[ForwardedReply] =
      if isBlocked(self, installation) then Future.failed(unreachable(self, installation))
      else underlying.forward(installation, componentId, entityId, call)

    def report(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        position: Long
    ): Future[Done] =
      if isBlocked(self, installation) then Future.failed(unreachable(self, installation))
      else underlying.report(installation, componentId, entityId, position)

    def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]] =
      // What a blocked installation holds is not known here: locate asks the others only.
      val reachable = installations.filterNot(i => i == self || isBlocked(self, i)).toSet
      if reachable.isEmpty then Future.successful(None)
      else underlying.locate(componentId, entityId, reachable)

    def advanced: Source[Advanced, NotUsed] =
      underlying.advanced
        .mapAsync(1)(a => open(self).map(_ => a))
        .filterNot(a => isBlocked(self, a.installation))

    def reachable(installation: String): Boolean =
      !isBlocked(self, installation) && underlying.reachable(installation)

    override def stop(): Unit = underlying.stop()
