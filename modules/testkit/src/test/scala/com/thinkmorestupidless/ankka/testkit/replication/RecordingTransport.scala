package com.thinkmorestupidless.ankka.testkit.replication

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.replication.{
  Advanced,
  DatabaseReplicationTransport,
  ForwardedCall,
  ForwardedReply,
  LocalInstallations,
  Located,
  LoopbackReplication,
  ReplicationPeer,
  ReplicationSettings,
  ReplicationTransport,
  ReplicationTransportFactory,
  ReplicationTransportProvider,
  Replicated
}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done, NotUsed}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * A transport beside the runtime's own, as one written outside the runtime would be (spec 057):
 * built on the runtime's own for records, forwarding and locating, it records what it was given and
 * hands a handover's report to the home's copy through `ReplicationPeer.reported`, the public
 * operation, rather than through anything the runtime keeps to itself.
 *
 * Supplied two ways: by `RecordingTransportProvider` on the test classpath, when the configuration
 * says `ankka.test.recording-transport = on`, and by `factory`, for the test kit's `using`.
 */
final class RecordingTransport(
    system: ActorSystem[?],
    settings: ReplicationSettings,
    replicated: Set[ComponentId]
) extends ReplicationTransport:

  private val self = settings.installation.getOrElse("")
  RecordingTransport.made.put(self, RecordingTransport.Made(settings, replicated)): Unit

  private val underlying =
    DatabaseReplicationTransport(settings, replicated, LoopbackReplication.none)(using system)

  def name: String = RecordingTransport.Name

  def records(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      from: Long
  ): Source[Replicated, NotUsed] = underlying.records(installation, componentId, entityId, from)

  def forward(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      call: ForwardedCall
  ): Future[ForwardedReply] = underlying.forward(installation, componentId, entityId, call)

  /** Through the public operation, at the installation the report is for. */
  def report(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      position: Long
  ): Future[Done] =
    LocalInstallations.lookup(installation) match
      case Some(home) =>
        val delivered =
          ReplicationPeer.reported(home.system, componentId, entityId, self, position)
        delivered.foreach(_ =>
          RecordingTransport.reports
            .updateWith(installation)(r =>
              Some(r.getOrElse(Vector.empty) :+ (entityId, self))
            ): Unit
        )(using system.executionContext)
        delivered
      case None => Future.failed(IllegalStateException(s"'$installation' is not started"))

  def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]] =
    underlying.locate(componentId, entityId)

  def advanced: Source[Advanced, NotUsed] = underlying.advanced

  def reachable(installation: String): Boolean = underlying.reachable(installation)

  override def stop(): Unit = underlying.stop()

object RecordingTransport:
  val Name = "the recording transport"

  /** What a transport was made with: the settings and the replicated components. */
  final case class Made(settings: ReplicationSettings, replicated: Set[ComponentId])

  /** Each installation's transport, as it was made. */
  val made: TrieMap[String, Made] = TrieMap.empty

  /** The reports delivered to each installation's copy: the entity and who reported. */
  val reports: TrieMap[String, Vector[(EntityId, String)]] = TrieMap.empty

  def clear(): Unit =
    made.clear()
    reports.clear()

  /** For the test kit's `installations(…).using(…)`. */
  val factory: ReplicationTransportFactory =
    (system, settings, replicated) => RecordingTransport(system, settings, replicated)

/** Named in the test classpath's `META-INF/services`; supplies a transport only when asked. */
final class RecordingTransportProvider extends ReplicationTransportProvider:
  def transport(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings,
      replicated: Set[ComponentId]
  ): Option[ReplicationTransport] =
    Option.when(
      config.hasPath(RecordingTransportProvider.Switch) &&
        config.getBoolean(RecordingTransportProvider.Switch)
    )(RecordingTransport(system, settings, replicated))

object RecordingTransportProvider:
  val Switch = "ankka.test.recording-transport"
