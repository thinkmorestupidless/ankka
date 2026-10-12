package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaService,
  Database,
  EntityKeys,
  EntityProtocol,
  RuntimeExtension,
  SqlFragment,
  StartRefusal
}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Makes a service's replication transport once its actor system exists. */
type ReplicationTransportFactory =
  (ActorSystem[?], ReplicationSettings, Set[ComponentId]) => ReplicationTransport

/**
 * A replicating service's part beside its entities (feature 051): it makes this installation
 * reachable from siblings started in the same JVM, wakes a copy when another installation advanced
 * it, and stops the transport when the service stops.
 */
final class ReplicationRuntime private[ankka] (val replication: Replication)
    extends RuntimeExtension:

  def name: String = "replication"

  @volatile private var feed: Option[UniqueKillSwitch]          = None
  @volatile private var local: Option[LocalInstallations.Local] = None

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system
    val askTimeout = FiniteDuration(
      system.settings.config.getDuration("ankka.ask-timeout").toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )
    val here = LocalInstallations.Local(system, askTimeout)
    LocalInstallations.register(replication.self, here)
    local = Some(here)
    feed = Some(wake(system))
    system.log.info(
      "replication: installation '{}' with {} through {}",
      replication.self,
      replication.settings.installations.toVector.sorted.mkString(", "),
      replication.transport.name
    )

  /**
   * Sends each copy another installation advanced a wake. A copy that holds no record of the entity
   * is woken only when what was written put this installation in its replication set: anything else
   * would start a copy of every entity a sibling writes.
   */
  private def wake(system: ActorSystem[?]): UniqueKillSwitch =
    given Materializer     = Materializer(system)
    given ExecutionContext = system.executionContext
    val sharding           = ClusterSharding(system)
    val database           = Database()(using system)
    val held               = ConcurrentHashMap.newKeySet[String]()
    def holds(componentId: ComponentId, entityId: EntityId): Future[Boolean] =
      val pid = PersistenceId(componentId, entityId).id
      if held.contains(pid) then Future.successful(true)
      else
        database
          .queryOne(
            SqlFragment.raw(
              "SELECT 1 FROM event_journal WHERE persistence_id = " +
                s"'${pid.replace("'", "''")}' LIMIT 1"
            )
          )(_ => ())
          .map { found =>
            if found.nonEmpty then held.add(pid): Unit
            found.nonEmpty
          }
          .recover { case NonFatal(_) => false }
    replication.transport.advanced
      .filter(a => replication.replicates(a.componentId))
      .mapAsync(4) { advanced =>
        (if advanced.joined then Future.successful(true)
         else holds(advanced.componentId, advanced.entityId)).map(advanced -> _)
      }
      .collect { case (advanced, true) => advanced }
      .viaMat(KillSwitches.single)(Keep.right)
      .to(Sink.foreach { advanced =>
        sharding
          .entityRefFor(EntityKeys.forComponent(advanced.componentId), advanced.entityId)
          .tell(
            EntityProtocol.Wake(advanced.installation, advanced.position, advanced.joined)
          )
      })
      .run()

  override def stop(): Unit =
    feed.foreach(_.shutdown())
    feed = None
    local.foreach(LocalInstallations.unregister(replication.self, _))
    local = None
    try replication.transport.stop()
    catch case NonFatal(_) => ()

object ReplicationRuntime:

  /**
   * The service's replication, or `None` for a service that names no other installation, whose
   * replicated entities then behave as any other. A service that names installations takes the
   * transport the test kit gave it, else the first a provider on the classpath supplies, else the
   * runtime's own when every installation names its database; with none of those it refuses to
   * start, naming the transport.
   */
  private[ankka] def resolve(
      system: ActorSystem[?],
      config: Config,
      components: Seq[ComponentDescriptor],
      supplied: Option[ReplicationTransportFactory],
      loopback: LoopbackReplication
  ): Option[Replication] =
    val settings =
      try ReplicationSettings.from(config)
      catch
        case e: IllegalArgumentException =>
          StartRefusal.refuse(e.getMessage, IllegalArgumentException(_))
    if !settings.enabled then None
    else
      val replicated = components.filter(_.replicated).map(_.componentId).toSet
      val provided = java.util.ServiceLoader
        .load(classOf[ReplicationTransportProvider])
        .iterator()
        .asScala
        .toVector
      val transport = supplied
        .map(_(system, settings, replicated))
        .orElse(provided.iterator.flatMap(_.transport(system, config, settings)).nextOption())
        .orElse(
          Option.when(settings.installations.forall(settings.databases.contains))(
            DatabaseReplicationTransport(settings, replicated, loopback)(using system)
          )
        )
        .getOrElse {
          val missing = (settings.installations -- settings.databases.keySet).toVector.sorted
          StartRefusal.refuse(
            s"no replication transport can reach the installations this service names " +
              s"(${settings.installations.toVector.sorted.mkString(", ")}): no provider on the " +
              "classpath supplies one, and the runtime's own replication transport needs the " +
              s"database of each, which ${missing.mkString(", ")} does not name. Add " +
              "ankka-replication, or name each installation's database " +
              "(ANKKA_INSTALLATION_<NAME>_DB_HOST and the rest)",
            IllegalStateException(_)
          )
        }
      Some(Replication(settings, transport, replicated))
