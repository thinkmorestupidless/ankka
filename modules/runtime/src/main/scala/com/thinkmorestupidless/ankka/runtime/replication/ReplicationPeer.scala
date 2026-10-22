package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.{EntityKeys, EntityProtocol}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future

/**
 * What a transport beside the runtime's own may hand the local copy of a replicated entity (spec
 * 057), through public operations rather than the entity's own protocol, which stays the runtime's.
 */
object ReplicationPeer:

  /** The replication of each replicating service started in this JVM, by its actor system. */
  private val running = ConcurrentHashMap[ActorSystem[?], Replication]()

  private[replication] def started(system: ActorSystem[?], replication: Replication): Unit =
    running.put(system, replication): Unit

  private[replication] def stopped(system: ActorSystem[?], replication: Replication): Unit =
    running.remove(system, replication): Unit

  /**
   * Delivers a handover target's report to the local copy of the entity: `from` holds every record
   * up to `position`. What `ReplicationTransport.report` at the home must do with a report it is
   * carried. Completes once the local copy is sent it; fails when this service does not replicate
   * the component, or replicates nothing.
   */
  def reported(
      system: ActorSystem[?],
      componentId: ComponentId,
      entityId: EntityId,
      from: String,
      position: Long
  ): Future[Done] =
    Option(running.get(system)) match
      case Some(replication) if replication.replicates(componentId) =>
        ClusterSharding(system)
          .entityRefFor(EntityKeys.forComponent(componentId), entityId)
          .tell(EntityProtocol.Reported(from, position))
        Future.successful(Done)
      case _ =>
        Future.failed(
          IllegalArgumentException(
            s"'$componentId' is not a replicated component of this service; a report for " +
              s"'$entityId' from '$from' has nowhere to go"
          )
        )
