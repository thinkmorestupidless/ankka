package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.typed.{Cluster, Join, JoinSeedNodes}
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.management.scaladsl.PekkoManagement

import java.nio.file.Paths
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * How this node comes to be a cluster member — one code path, chosen by configuration.
 *
 * An ankka service is always a cluster, including on a laptop: running the same code path in
 * development and production is the point. What differs is only how a node finds its peers, and
 * that is decided by the overlay `ClusterConfig` selected, never by the service's code.
 *
 *   - `join-self-or-seeds` (the local overlay): named seed nodes if any were given, otherwise join
 *     self. Joining self is what removes the configuration step that would make a local run a
 *     special case — and, because the local port is random, it is the only join that can work
 *     without knowing the port in advance.
 *   - `bootstrap` (the Kubernetes overlay): start Pekko Management, then Cluster Bootstrap, and
 *     never join self — there, a self-join is exactly the split this exists to prevent.
 *
 * Management is started here and only here because it binds a fixed port, which two local services
 * on one machine could not share. Deciding from configuration rather than calling bootstrap
 * unconditionally also spares every local run the WARN bootstrap logs when it finds seed nodes and
 * stands down.
 */
object ClusterFormation:

  val FormationKey: String = "ankka.cluster.formation"
  val SeedNodesKey: String = "ankka.cluster.seed-nodes"

  val ClusterTlsKey: String = "ankka.tls.cluster-directory"

  val JoinSelfOrSeeds: String = "join-self-or-seeds"
  val Bootstrap: String       = "bootstrap"

  def form(system: ActorSystem[?]): Unit =
    val config = system.settings.config
    // `startWith` hosts on a system the caller built without the loader; treat the overlay's
    // absence as the local overlay's defaults rather than failing a system we did not create.
    def string(key: String, default: String) =
      if config.hasPath(key) then config.getString(key) else default
    def boolean(key: String, default: Boolean) =
      if config.hasPath(key) then config.getBoolean(key) else default

    string(FormationKey, JoinSelfOrSeeds) match
      case JoinSelfOrSeeds =>
        val cluster = Cluster(system)
        val seeds   = seedNodes(string(SeedNodesKey, ""))
        if seeds.nonEmpty then cluster.manager ! JoinSeedNodes(seeds)
        else if boolean("ankka.join-self-if-no-seed-nodes", true) then
          cluster.manager ! Join(cluster.selfMember.address)

      case Bootstrap =>
        // Before management, so the kubelet can ask from the moment the pod starts; it says 503
        // until membership and every extension agree, exactly as management's /ready did.
        ProbeEndpoint.start(system): Unit
        string(ClusterTlsKey, "") match
          case "" =>
            PekkoManagement(system).start(): Unit
          case directory =>
            val tls = clusterTls(system, directory)
            // Before bootstrap: with no CA path configured, the contact-point probes go through
            // pekko-http's default client context — this one, presenting the cluster certificate.
            Http()(using system).setDefaultClientHttpsContext(
              ConnectionContext.httpsClient((host, port) => tls.clientEngine(host, port))
            )
            PekkoManagement(system).start(
              _.withHttpsConnectionContext(ConnectionContext.httpsServer(() => tls.serverEngine()))
            ): Unit
        ClusterBootstrap(system).start()

      case other =>
        throw IllegalArgumentException(
          s"$FormationKey '$other' is not one of: $JoinSelfOrSeeds, $Bootstrap"
        )

  /**
   * The cluster identity for management and the probes of it: only a peer holding this very
   * certificate may ask a management route, the same rule remoting's own engine applies after its
   * handshake. A missing file fails startup naming it, rather than starting a node that nothing can
   * reach and that reports nothing.
   */
  private def clusterTls(system: ActorSystem[?], directory: String): RotatingTls =
    val config = system.settings.config
    val interval =
      if config.hasPath(ReloadKey) then
        FiniteDuration(
          config.getDuration(ReloadKey).toMillis,
          java.util.concurrent.TimeUnit.MILLISECONDS
        )
      else 1.minute
    RotatingTls(Paths.get(directory), interval, RotatingTls.Peers.SameIdentity)

  private val ReloadKey = "ankka.tls.reload-interval"

  /** Comma-separated `pekko://system@host:port` addresses; blank entries ignored. */
  def seedNodes(value: String): List[Address] =
    value.split(',').iterator.map(_.trim).filter(_.nonEmpty).map(parseAddress).toList

  private def parseAddress(text: String): Address =
    org.apache.pekko.actor.AddressFromURIString.parse(text)
