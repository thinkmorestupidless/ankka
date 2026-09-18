package nakka.controlplane.deploy

import nakka.controlplane.application.{ServiceEntity, ServiceRows}
import nakka.controlplane.domain.ServiceKey
import nakka.core.EntityId
import nakka.runtime.SqlSyntax.jsonText
import nakka.runtime.{NakkaService as RunningService, RuntimeExtension, SqlFragment, ViewClient}
import nakka.sdk.ComponentClient
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * Keeps the cluster's `NakkaService` resources matching the control plane's record, and folds the
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
    clientFactory: DeployConfig => NakkaServiceClient
) extends RuntimeExtension:

  private val log: Logger = LoggerFactory.getLogger("nakka.controlplane.projector")

  @volatile private var projection: Option[Projection]     = None
  @volatile private var client: Option[NakkaServiceClient] = None
  @volatile private var watching: Option[AutoCloseable]    = None

  def name: String = "service-projector"

  /**
   * Projects one service now, without waiting for the sweep.
   *
   * Called by `ProjectionTrigger` the moment desired state changes, which is what keeps
   * apply-to-running off the sweep interval.
   */
  def project(key: ServiceKey): Unit = projection.foreach(_.projectOne(key))

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
      SingletonActor(ProjectionSweeper(work, config.sweepInterval), "nakka-service-projector")
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
    new ServiceProjector(config, c => Fabric8NakkaServiceClient(c.namespacePrefix))

  /** For tests: supply a fake so the whole projector runs with no cluster. */
  def withClient(config: DeployConfig, client: NakkaServiceClient): ServiceProjector =
    new ServiceProjector(config, _ => client)

/**
 * The off-actor half.
 *
 * Nothing here touches `ActorContext`. That is not stylistic: doing so from a callback in the timer
 * sweeper once turned "retry with backoff" into "retry immediately, forever", and the same shape is
 * followed here so it cannot recur.
 */
private[deploy] final class Projection(
    client: NakkaServiceClient,
    config: DeployConfig,
    componentClient: ComponentClient,
    viewClient: ViewClient
):

  private val log: Logger = LoggerFactory.getLogger("nakka.controlplane.projector")

  private def entity(key: ServiceKey) =
    componentClient.forEventSourcedEntity(EntityId(key.id))

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
          ServiceProjection.project(service, config) match
            case Left(problems) =>
              log.warn("cannot project {}: {}", key.id, problems.mkString("; "))
              observe(key, ClusterView.Unreachable(problems.mkString("; ")))
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
  def onStatus(resource: NakkaServiceResource): Unit =
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
    val desired = knownServices
    val existing =
      try client.list().map(r => ServiceKey(r.spec.projectId, r.spec.serviceName))
      catch
        case NonFatal(failure) =>
          log.warn("could not list resources; sweeping what the control plane knows", failure)
          Vector.empty

    (desired ++ existing).distinct.foreach(projectOne)

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
                  nakka.runtime.NakkaExecutors.virtual
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
