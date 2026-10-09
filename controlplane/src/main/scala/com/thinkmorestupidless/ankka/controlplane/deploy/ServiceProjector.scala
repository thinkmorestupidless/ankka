package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.application.{
  OrganizationRows,
  ProjectEntity,
  ProjectRows,
  ServiceEntity,
  ServiceRows
}
import com.thinkmorestupidless.ankka.controlplane.domain.Attribution
import com.thinkmorestupidless.ankka.controlplane.domain.{DeclaredBroker, RegistryRef, ServiceKey}
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaService as RunningService,
  RuntimeExtension,
  SqlFragment,
  ViewClient
}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * Keeps the cluster's `AnkkaService` resources matching the control plane's record, and folds the
 * status the operator writes back into the journal.
 *
 * This is the control plane's entire share of reconciliation. It writes one kind of object and
 * reads one kind of object; it never sees a Deployment. Everything below that — rendering, rollout,
 * failure classification — is the operator's, and the resource is the only thing either side knows
 * about the other.
 *
 * A cluster singleton, for the reason `TimerRuntime` is one: one sweeper is far easier to reason
 * about than N racing for the same rows.
 */
final class ServiceProjector private (
    config: DeployConfig,
    clientFactory: DeployConfig => AnkkaServiceClient
) extends RuntimeExtension
    with RegistryWriter
    with ProjectSecretWriter
    with ProjectTopicsReader
    with ProjectSchemaStore:

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.projector")

  @volatile private var projection: Option[Projection]     = None
  @volatile private var client: Option[AnkkaServiceClient] = None
  @volatile private var watching: Option[AutoCloseable]    = None

  def name: String = "service-projector"

  /**
   * Projects one service now, without waiting for the sweep.
   *
   * Called by `ProjectionTrigger` the moment desired state changes, which is what keeps
   * apply-to-running off the sweep interval.
   */
  def project(key: ServiceKey): Unit = projection.foreach(_.projectOne(key))

  /**
   * Every service in the organization's projects, suspended — by `SuspensionTrigger`, on the event.
   */
  def organizationDisabled(organizationId: String, by: Option[Attribution]): Unit =
    projection.foreach(_.setSuspended(organizationId, suspended = true, by))

  def organizationEnabled(organizationId: String, by: Option[Attribution]): Unit =
    projection.foreach(_.setSuspended(organizationId, suspended = false, by))

  /**
   * Puts a project's registry credential in the cluster.
   *
   * The projector rather than the endpoint, because the client is built here and a project's
   * namespace is named from this configuration. Refuses before startup rather than silently
   * succeeding: a credential nobody wrote must not be recorded as written.
   */
  def writePullSecret(
      projectId: String,
      server: String,
      username: String,
      password: String
  ): Unit =
    client match
      case Some(resources) =>
        resources.ensurePullSecret(config.namespaceFor(projectId), server, username, password)
      case None => throw new IllegalStateException("the cluster client is not started")

  /** A project secret's entries, into the project's namespace. Refuses before startup, as above. */
  def setEntries(projectId: String, name: String, entries: Map[String, String]): Unit =
    client match
      case Some(resources) =>
        resources.setSecretEntries(config.namespaceFor(projectId), name, entries)
      case None => throw new IllegalStateException("the cluster client is not started")

  def removeEntry(projectId: String, name: String, entry: String): Unit =
    client match
      case Some(resources) =>
        resources.removeSecretEntry(config.namespaceFor(projectId), name, entry)
      case None => throw new IllegalStateException("the cluster client is not started")

  /**
   * Where machine tokens' signing keys are written (feature 040): the Secret `secret` in the
   * control plane's own `namespace`, one `<kid>.pem` entry per key, by the merge patch that writes
   * a project secret's entries — `create` and `patch`, never `get`.
   */
  def machineKeyWriter(
      namespace: String,
      secret: String
  ): com.thinkmorestupidless.ankka.controlplane.auth.MachineKeyWriter =
    new com.thinkmorestupidless.ankka.controlplane.auth.MachineKeyWriter:
      private def resources =
        client.getOrElse(throw new IllegalStateException("the cluster client is not started"))
      def addKey(kid: String, pem: String): Unit =
        resources.setSecretEntries(namespace, secret, Map(s"$kid.pem" -> pem))
      def removeKeys(kids: Seq[String]): Unit =
        kids.foreach(kid => resources.removeSecretEntry(namespace, secret, s"$kid.pem"))

  /**
   * Writes a project's declared topics to the cluster as its `AnkkaProject` (feature 027). Called
   * by `ProjectTopicsTrigger` when the declarations change; it throws when the cluster cannot take
   * the write, so the trigger is retried with its projection's backoff until it can.
   */
  /**
   * Writes a registered machine as its `AnkkaMachine`, or removes it when it is gone (feature 040).
   * `None` is a machine that is not registered.
   */
  def projectMachine(
      organizationId: String,
      name: String,
      machine: Option[com.thinkmorestupidless.ankka.controlplane.api.MachineSummary]
  ): Unit =
    client match
      case Some(resources) =>
        machine match
          case Some(m) =>
            resources.putMachine(
              com.thinkmorestupidless.ankka.crd.AnkkaMachineSpec(
                organizationId,
                name,
                m.byteRates.map(_.produceBytesPerSecond),
                m.byteRates.map(_.consumeBytesPerSecond),
                m.byteRates.map(_.requestPercentage)
              )
            )
          case None => resources.deleteMachine(organizationId, name)
      case None => throw new IllegalStateException("the cluster client is not started")

  /** Whether the projector has its cluster client and its projection: whether it can write. */
  def ready: Boolean = client.isDefined && projection.isDefined

  def projectTopics(projectId: String): Unit =
    (client, projection) match
      case (Some(resources), Some(work)) =>
        resources.putProject(
          config.namespaceFor(projectId),
          projectId,
          ProjectProjection.spec(
            projectId,
            work.topicsOf(projectId),
            work.brokersOf(projectId),
            work.grantsOf(projectId)
          )
        )
      case _ => throw new IllegalStateException("the cluster client is not started")

  def putSchema(projectId: String, fingerprint: String, document: String): Unit =
    client match
      case Some(resources) =>
        resources.putSchema(config.namespaceFor(projectId), fingerprint, document)
      case None => throw new IllegalStateException("the cluster client is not started")

  def schema(projectId: String, fingerprint: String): Option[String] =
    client match
      case Some(resources) => resources.schema(config.namespaceFor(projectId), fingerprint)
      case None            => throw new IllegalStateException("the cluster client is not started")

  def topicStatus(projectId: String): Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus] =
    client match
      case Some(resources) => resources.projectStatus(config.namespaceFor(projectId), projectId)
      case None            => throw new IllegalStateException("the cluster client is not started")

  def start(service: RunningService): Unit =
    given system: ActorSystem[?] = service.system

    val resources = clientFactory(config)
    client = Some(resources)

    val work = new Projection(resources, config, service.componentClient, service.viewClient)
    projection = Some(work)

    // Status arrives by watch, so a change in the cluster reaches `services list` in about
    // the time it takes the operator to write it — not at the next sweep.
    try watching = Some(resources.watch(work.onStatus))
    catch
      case NonFatal(failure) =>
        // A cluster that is down at startup must not stop the control plane coming up: an
        // apply has to succeed and be durable regardless. The sweep re-establishes this.
        if config.failFastOnUnreachableCluster then throw failure
        log.warn("could not start watching the cluster; the sweep will retry", failure)

    val _ = ClusterSingleton(system).init(
      SingletonActor(ProjectionSweeper(work, config.sweepInterval), "ankka-service-projector")
    )

    log.info(
      "service projector started (namespace prefix '{}', sweeping every {})",
      config.namespacePrefix,
      config.sweepInterval
    )

  override def stop(): Unit =
    watching.foreach(_.close())
    client.foreach(_.close())
    watching = None
    client = None
    projection = None

object ServiceProjector:

  /** Uses the ambient cluster credentials. */
  def apply(config: DeployConfig): ServiceProjector =
    new ServiceProjector(config, c => Fabric8AnkkaServiceClient(c.namespacePrefix))

  /** For tests: supply a fake so the whole projector runs with no cluster. */
  def withClient(config: DeployConfig, client: AnkkaServiceClient): ServiceProjector =
    new ServiceProjector(config, _ => client)

/**
 * The off-actor half.
 *
 * Nothing here touches `ActorContext`. That is not stylistic: doing so from a callback in the timer
 * sweeper once turned "retry with backoff" into "retry immediately, forever", and the same shape is
 * followed here so it cannot recur.
 */
private[deploy] final class Projection(
    client: AnkkaServiceClient,
    config: DeployConfig,
    componentClient: ComponentClient,
    viewClient: ViewClient
):

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.projector")

  private def entity(key: ServiceKey) =
    componentClient.forEventSourcedEntity(EntityId(key.id))

  /**
   * The project's registry credential, or nothing.
   *
   * Read on every projection rather than cached: a credential set or cleared while a service is
   * running must reach the next projection, and there is no event on the *service* to notice it. A
   * project that has gone missing answers nothing — a service being projected for a deleted project
   * is a state the delete path handles, and failing the projection over it would be a worse answer
   * than leaving the reference off.
   */
  /** A project's declared topics, from the project itself; none for a project that is gone. */
  def topicsOf(
      projectId: String
  ): Map[String, com.thinkmorestupidless.ankka.controlplane.domain.DeclaredTopic] =
    componentClient
      .forEventSourcedEntity(EntityId(projectId))
      .call(ProjectEntity.topics)
      .invoke()

  def brokersOf(projectId: String): Map[String, DeclaredBroker] =
    componentClient
      .forEventSourcedEntity(EntityId(projectId))
      .call(ProjectEntity.brokers)
      .invoke()

  /** Every grant the project has made (feature 040); the projection keeps the accepted ones. */
  def grantsOf(
      projectId: String
  ): Vector[com.thinkmorestupidless.ankka.controlplane.domain.Grant] =
    componentClient
      .forEventSourcedEntity(EntityId(projectId))
      .call(ProjectEntity.grants)
      .invoke()

  private def registryOf(projectId: String): Option[RegistryRef] =
    try
      componentClient
        .forEventSourcedEntity(EntityId(projectId))
        .call(ProjectEntity.registry)
        .invoke()
    catch
      case NonFatal(failure) =>
        log.debug("no registry for project {}: {}", projectId, failure.getMessage)
        None

  /**
   * One service: make the cluster match the record, then say what happened.
   *
   * Level-triggered — it reads desired state and makes the resource match, rather than acting on
   * what changed — so running it twice is harmless and a lost trigger costs only latency.
   */
  def projectOne(key: ServiceKey): Unit =
    val namespace = config.namespaceFor(key.projectId)
    try
      entity(key).call(ServiceEntity.desiredState).invoke() match
        case None =>
          // Deleted, or never applied. Removing the resource takes its Deployment with it,
          // because the children carry an owner reference.
          client.delete(namespace, key.name)

        case Some(service) =>
          ServiceProjection.project(service, config, registryOf(key.projectId)) match
            case Left(problems) =>
              log.warn("cannot project {}: {}", key.id, problems.mkString("; "))
              observe(key, ClusterView.Refused(problems.mkString("; ")))
            case Right(spec) =>
              // Before the resource, not after: a namespaced object cannot be created in a
              // namespace that does not exist yet.
              client.ensureNamespace(namespace)
              client.put(namespace, key.name, spec)
              // Nothing may have reported on it yet, which is a distinct thing to say.
              client
                .list()
                .find(r => r.namespace == namespace && r.name == key.name)
                .foreach {
                  case r if r.status.isEmpty => observe(key, ClusterView.NoReport)
                  case r => r.status.foreach(s => observe(key, ClusterView.Reported(s)))
                }
    catch
      case NonFatal(failure) =>
        val reason = Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)
        log.warn(s"projecting ${key.id} failed; reporting it unconfirmed", failure)
        observe(key, ClusterView.Unreachable(reason))

  /** A status changed in the cluster. */
  def onStatus(resource: AnkkaServiceResource): Unit =
    ServiceKey
      .parse(s"${resource.spec.projectId}/${resource.spec.serviceName}")
      .foreach { key =>
        val view =
          resource.status.fold[ClusterView](ClusterView.NoReport)(ClusterView.Reported.apply)
        observe(key, view)
      }

  /**
   * Everything, re-examined.
   *
   * The union of what the control plane wants and what the cluster holds, not just the former: a
   * service deleted from the control plane no longer has a view row, so a desired-only sweep would
   * never look at it again and its resource would survive forever.
   */
  def sweep(): Unit =
    reconcileSuspensions()
    val desired = knownServices
    val existing =
      try client.list().map(r => ServiceKey(r.spec.projectId, r.spec.serviceName))
      catch
        case NonFatal(failure) =>
          log.warn("could not list resources; sweeping what the control plane knows", failure)
          Vector.empty

    (desired ++ existing).distinct.foreach(projectOne)

  /**
   * Every service in the organization's projects, suspended or reinstated (feature 008, FR-033).
   *
   * Enumerated through the listing views, which lag: a service applied moments before the disable
   * may have no row yet. That one is caught by `reconcileSuspensions` on the next sweep — the
   * correctness half — so this half only has to be prompt, and idempotent.
   */
  def setSuspended(organizationId: String, suspended: Boolean, by: Option[Attribution]): Unit =
    val metadata = by.getOrElse(Attribution(Attribution.platform, java.time.Instant.now())).metadata
    for
      project <- projectsOf(organizationId)
      row     <- servicesIn(project)
    do
      val key = ServiceKey(row.projectId, row.name)
      try
        val handle = if suspended then ServiceEntity.suspend else ServiceEntity.reinstate
        val _      = entity(key).call(handle).withMetadata(metadata).invoke()
        projectOne(key)
      catch
        case NonFatal(failure) =>
          log.warn(s"could not ${if suspended then "suspend" else "reinstate"} ${key.id}", failure)

  /**
   * The correctness half of disabling an organization: any running service whose organization is
   * disabled is suspended, and any suspended one whose organization is enabled is reinstated —
   * whatever the trigger managed to see at the time. Runs before the projection sweep so the
   * resources rendered below already reflect it.
   *
   * Attributed to the administrator who disabled or enabled the organization, as the trigger's
   * events are: whether the trigger or this sweep reaches a service first is a race (a lagging
   * listing view decides it), and a service's history must read the same either way. The platform
   * is the actor only for an organization row that predates the attribution being recorded.
   */
  private def reconcileSuspensions(): Unit =
    try
      val organizations =
        viewClient.forView(OrganizationRows).ordered(SqlFragment.empty, order = jsonText("name"))
      val disabled = organizations.filter(_.disabled).map(_.id).toSet
      val enabled  = organizations.filterNot(_.disabled).map(_.id).toSet
      val attribution: Map[String, Metadata] =
        organizations.map { o =>
          o.id -> Attribution
            .from(o.disabledBy, o.disabledAt)
            .getOrElse(Attribution(Attribution.platform, java.time.Instant.now()))
            .metadata
        }.toMap
      val projectToOrganization =
        viewClient
          .forView(ProjectRows)
          .ordered(SqlFragment.empty, order = jsonText("name"))
          .map(p => p.id -> p.organizationId)
          .toMap
      viewClient.forView(ServiceRows).ordered(SqlFragment.empty, order = jsonText("name")).foreach {
        row =>
          projectToOrganization.get(row.projectId).foreach { organizationId =>
            val key   = ServiceKey(row.projectId, row.name)
            val stamp = attribution(organizationId)
            if disabled.contains(organizationId) && !row.suspended then
              val _ = entity(key).call(ServiceEntity.suspend).withMetadata(stamp).invoke()
            else if enabled.contains(organizationId) && row.suspended then
              val _ = entity(key).call(ServiceEntity.reinstate).withMetadata(stamp).invoke()
          }
      }
    catch
      case NonFatal(failure) =>
        log.warn("could not reconcile suspensions; the next sweep will", failure)

  private def projectsOf(organizationId: String): Vector[String] =
    viewClient
      .forView(ProjectRows)
      .where(jsonText("organizationId") ++ sql" = $organizationId")
      .map(_.id)

  private def servicesIn(projectId: String) =
    viewClient.forView(ServiceRows).where(jsonText("projectId") ++ sql" = $projectId")

  /** Every service the control plane knows about, from the listing view. */
  private def knownServices: Vector[ServiceKey] =
    try
      viewClient
        .forView(ServiceRows)
        .ordered(SqlFragment.empty, order = jsonText("name"))
        .map(row => ServiceKey(row.projectId, row.name))
        .toVector
    catch
      case NonFatal(failure) =>
        log.warn("could not list services from the view", failure)
        Vector.empty

  private def observe(key: ServiceKey, view: ClusterView): Unit =
    try
      val service = entity(key).call(ServiceEntity.desiredState).invoke()
      service.foreach { current =>
        val _ = entity(key).call(ServiceEntity.observe).invoke(StatusIngest.observe(current, view))
      }
    catch
      case NonFatal(failure) =>
        log.warn(s"could not record an observation for ${key.id}", failure)

  /** Whether the cluster is currently readable, for the unconfirmed decision. */
  def connected: Boolean = client.connected

/**
 * The cluster singleton that sweeps.
 *
 * One batch at a time: overlapping sweeps would project the same service twice concurrently, and a
 * tick arriving mid-sweep is dropped rather than queued because the next one is moments away and
 * there is nothing to catch up on. The same shape as `TimerSweeper`, for the same reasons.
 */
private[deploy] object ProjectionSweeper:

  private sealed trait Command
  private case object Tick                            extends Command
  private case object Finished                        extends Command
  private final case class Failed(failure: Throwable) extends Command

  def apply(projection: Projection, interval: FiniteDuration): Behavior[Nothing] =
    Behaviors
      .setup[Command] { ctx =>
        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Tick, interval)

          def idle: Behavior[Command] = Behaviors.receiveMessage {
            case Tick =>
              ctx.pipeToSelf(
                scala.concurrent.Future(projection.sweep())(using
                  com.thinkmorestupidless.ankka.runtime.AnkkaExecutors.virtual
                )
              ) {
                case scala.util.Success(_)       => Finished
                case scala.util.Failure(failure) => Failed(failure)
              }
              busy
            case _ => Behaviors.same
          }

          def busy: Behavior[Command] = Behaviors.receiveMessage {
            case Tick     => Behaviors.same
            case Finished => idle
            case Failed(failure) =>
              ctx.log.warn("projection sweep failed; retrying on the next tick", failure)
              idle
          }

          idle
        }
      }
      .narrow
