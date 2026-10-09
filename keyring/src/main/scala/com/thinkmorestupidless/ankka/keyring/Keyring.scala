package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, RuntimeExtension, ServiceBuilder}
import com.thinkmorestupidless.ankka.runtime.erasure.{KeyringApi, ObjectStoreClient}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import com.typesafe.config.Config

import scala.util.control.NonFatal

/** Who may read which other project's subject keys: a grant with `decrypt` (spec 040). */
trait Grants:
  def allows(reader: String, owner: String): Boolean

object Grants:
  /** Until spec 040 renders grants to the keyring, no project reads another's. */
  val none: Grants = (_, _) => false

/**
 * The keyring (R7): a platform component of ankka, an ankka application of its own, deployed beside
 * the control plane. Its state is the subject keys, wrapped; each project's keys, wrapped; each
 * project's erasure log as it has applied it; and where each erasure stands.
 */
final class KeyringState(
    val grants: Grants,
    val logSources: LogSources,
    ackWithin: scala.concurrent.duration.FiniteDuration =
      scala.concurrent.duration.DurationInt(60).seconds,
    val applySchema: Boolean = true
):
  @volatile private var service: AnkkaService = scala.compiletime.uninitialized
  @volatile var ready: Boolean                = false
  @volatile var replayed: Int                 = 0
  @volatile var behind: Option[String]        = None
  def copies: Int                             = logSources.copies

  val keys     = Keys(() => service.secrets, () => service.componentClient)
  val channels = Channels(() => service.componentClient, ackWithin)

  private[keyring] def attach(s: AnkkaService): Unit = service = s

  private def client: ComponentClient = service.componentClient

  /** Destroys the key, appends the erasure to the project's log, and tells every channel. */
  def apply(
      project: String,
      erasureId: String,
      subject: String,
      sequence: Long,
      reapply: Boolean
  ): Unit =
    val at = System.currentTimeMillis()
    val before =
      client.forKeyValueEntity(EntityId(s"$project/$subject")).call(SubjectKeyEntity.state).invoke()
    client
      .forKeyValueEntity(EntityId(s"$project/$subject"))
      .call(SubjectKeyEntity.destroy)
      .invoke(Destroy(erasureId, at)): Unit
    client
      .forEventSourcedEntity(EntityId(project))
      .call(ProjectLogEntity.append)
      .invoke(Applied(erasureId, sequence, subject, at)): Unit
    client
      .forEventSourcedEntity(EntityId(erasureId))
      .call(ErasureEntity.start)
      .invoke(
        Start(project, subject, sequence, at, before.wrapped.isDefined || before.everExisted)
      ): Unit
    channels.broadcast(Broadcast(erasureId, project, subject, sequence, reapply))

  def refused(project: String, subject: String): Unit =
    try
      client
        .forKeyValueEntity(EntityId(s"$project/$subject"))
        .call(SubjectKeyEntity.refused)
        .invoke(): Unit
    catch case NonFatal(_) => ()

/** Where the erasure log's two copies are read from after a restore (FR-021). */
trait LogSources:
  def copies: Int
  def controlPlane: Option[Vector[KeyringApi.LogEntry]]
  def bucket: Option[Vector[KeyringApi.LogEntry]]

object LogSources:
  val none: LogSources = new LogSources:
    def copies                                            = 1
    def controlPlane: Option[Vector[KeyringApi.LogEntry]] = None
    def bucket: Option[Vector[KeyringApi.LogEntry]]       = None

/**
 * Starts the channels and replays the erasure log before the keyring answers anything (FR-021): the
 * union of both copies, every entry's key destroyed and its project's log appended, and the copy
 * that was behind reported. With neither copy configured — a local run, a test — its own journal is
 * the log.
 */
final class KeyringRuntime(state: KeyringState) extends RuntimeExtension:
  def name: String                              = "keyring"
  override def readiness: Option[() => Boolean] = Some(() => state.ready)

  def start(service: AnkkaService): Unit =
    if state.applySchema then Schema(service.system)
    state.attach(service)
    state.channels.start(service.system)
    state.keys.rootKey: Unit
    val fromControlPlane = state.logSources.controlPlane
    val fromBucket       = state.logSources.bucket
    val union = (fromControlPlane.getOrElse(Vector.empty) ++ fromBucket.getOrElse(Vector.empty))
      .distinctBy(_.erasureId)
      .sortBy(_.sequence)
    union.foreach { entry =>
      state.apply(entry.project, entry.erasureId, entry.subject, entry.sequence, reapply = false)
    }
    state.replayed = union.size
    state.behind = (fromControlPlane, fromBucket) match
      case (Some(cp), Some(_)) if cp.size < union.size => Some("controlplane")
      case (Some(_), Some(b)) if b.size < union.size   => Some("bucket")
      case _                                           => None
    service.system.log.info(
      "keyring replay: copies={} replayed={} behind={}",
      state.copies,
      union.size,
      state.behind.getOrElse("neither")
    )
    state.ready = true

  override def stop(): Unit = state.channels.stop()

object Keyring:
  val components: Vector[ComponentDescriptor] =
    Vector(
      SubjectKeyEntity.descriptor,
      ProjectKeyEntity.descriptor,
      ProjectLogEntity.descriptor,
      ErasureEntity.descriptor
    )

  def builder(
      state: KeyringState,
      interface: Option[String] = None,
      port: Option[Int] = None
  ): ServiceBuilder =
    val server = (interface, port) match
      case (Some(host), Some(p)) =>
        HttpServer.at(host, p)(clients => KeyringEndpoint(clients, state))
      case _ => HttpServer.of(clients => KeyringEndpoint(clients, state))
    Ankka.service
      .registerAll(components)
      .withExtension(KeyringRuntime(state))
      .withExtension(server)

  /**
   * The log's two copies as the installation configures them: the control plane's route and the
   * platform bucket.
   */
  def logSources(config: Config, env: String => Option[String] = sys.env.get): LogSources =
    val logUrl    = env("ANKKA_ERASURE_LOG_URL").filter(_.nonEmpty)
    val logBucket = ObjectStoreClient.fromEnvironment(env)
    new LogSources:
      def copies: Int = (logUrl.size + logBucket.size).max(1)
      def controlPlane: Option[Vector[KeyringApi.LogEntry]] =
        logUrl.map(ErasureLogReader.fromControlPlane(_, config))
      def bucket: Option[Vector[KeyringApi.LogEntry]] = logBucket.map(ErasureLogReader.fromBucket)
