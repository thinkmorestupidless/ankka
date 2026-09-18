package nakka.operator

import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.api.model.{NamespaceBuilder, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import nakka.crd.{NakkaService, NakkaServiceStatus}
import nakka.operator.cnpg.{
  CnpgObjectState,
  DatabaseObservation,
  PostgresCluster,
  PostgresDatabase,
  PostgresDatabaseRole
}
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Performs an [[Action]], and answers the questions a reconcile pass needs to ask.
 *
 * An interface rather than a concrete class so the reconcile loop can be driven in a unit test
 * without a cluster: everything above this line is pure, everything below it is I/O.
 */
trait Executor:
  def execute(action: Action): Unit

  /** One service's Deployment, or None if nakka owns nothing under that name. */
  def snapshot(namespace: String, name: String): Option[ClusterSnapshot]

  /**
   * Whether something nakka does **not** own already holds this name.
   *
   * Asked before writing, so a collision is reported rather than resolved by overwriting.
   */
  def foreignObjectAt(namespace: String, name: String): Boolean

  /** Pods backing a service, read only to explain why it is not ready. */
  def podProblems(namespace: String, serviceName: String, projectId: String): Vector[PodProblem]

  /** The gateway's verdict on a service's route, if a route exists (feature 005). */
  def observeRoute(namespace: String, name: String): Option[RouteView]

  /** Everything `Provisioning.decide` needs, read in one place. */
  def observeDatabase(
      namespace: String,
      clusterName: String,
      serviceName: String
  ): DatabaseObservation

  /**
   * When the `NakkaService` resource's current incarnation was created — for the recovered check.
   */
  def resourceCreatedAt(namespace: String, name: String): Option[Instant]

/**
 * The only thing in the operator that touches the cluster.
 *
 * Everything above it — rendering, classification, the decision to act at all — is a total function
 * over data. Keeping the I/O to one file is what makes that claim checkable by reading rather than
 * by trusting.
 *
 * Blocking by design: callers run on virtual threads, where a blocking call parks the thread and
 * releases its carrier. Failures throw; the reconcile loop classifies and backs off. Nothing here
 * retries internally, because a nested retry makes the loop's backoff budget meaningless.
 */
final class Fabric8Executor(client: KubernetesClient) extends Executor:

  private val log: Logger = LoggerFactory.getLogger("nakka.operator.executor")

  /**
   * Distinct from the control plane's `nakka-controlplane`.
   *
   * Server-side apply records which manager owns which field. Two managers with one name would
   * fight over every field; two names let each revert edits to its own without touching the other's
   * — the control plane owns the resource's spec, the operator owns the Deployment's.
   */
  private val FieldManager = "nakka-operator"

  def execute(action: Action): Unit = action match
    case Action.NoAction => ()

    case Action.EnsureNamespace(name) =>
      val namespace = new NamespaceBuilder()
        .withMetadata(
          new ObjectMetaBuilder()
            .withName(name)
            .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByNakka).asJava)
            .build()
        )
        .build()
      val _ =
        client.resource(namespace).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug("ensured namespace {}", name)

    case Action.ApplyDeployment(deployment) =>
      // forceConflicts because the drift policy is enforce: a conflict means something else
      // claimed a field this manager owns, and the resource is the source of truth.
      // No migration path for a Deployment feature 003 rendered with `Recreate`: an apply that
      // *sets* `rollingUpdate` owns the field, so the API server accepts it as it is. Proven by
      // the operator suite's migration case; the reverse direction once needed a merge patch.
      val _ =
        client.resource(deployment).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "applied deployment {}/{}",
        deployment.getMetadata.getNamespace,
        deployment.getMetadata.getName
      )

    case Action.DeleteDeployment(namespace, name) =>
      val _ = client.apps().deployments().inNamespace(namespace).withName(name).delete()
      log.debug("deleted deployment {}/{}", namespace, name)

    case Action.EnsureService(service) =>
      val _ =
        client.resource(service).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured service {}/{}",
        service.getMetadata.getNamespace,
        service.getMetadata.getName
      )

    case Action.EnsureServiceAccount(serviceAccount) =>
      val _ = client
        .resource(serviceAccount)
        .fieldManager(FieldManager)
        .forceConflicts()
        .serverSideApply()
      log.debug("ensured serviceaccount {}", serviceAccount.getMetadata.getName)

    case Action.EnsureRole(role) =>
      val _ = client.resource(role).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug("ensured role {}", role.getMetadata.getName)

    case Action.EnsureRoleBinding(roleBinding) =>
      val _ =
        client.resource(roleBinding).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug("ensured rolebinding {}", roleBinding.getMetadata.getName)

    case Action.RemoveService(namespace, name, ownerUid) =>
      // Read first, for two reasons. This is rendered on *every* pass for a service that serves
      // no HTTP, and an unconditional DELETE each time would break "an unchanged service writes
      // nothing". And the delete has to be conditional on ownership anyway: a Service of this name
      // that this resource does not own is somebody else's, and removing it would be the operator
      // destroying an object it never created.
      val existing = Option(client.services().inNamespace(namespace).withName(name).get())
      val owned = existing.exists { service =>
        ownerUid.nonEmpty &&
        Option(service.getMetadata.getOwnerReferences)
          .exists(_.asScala.exists(_.getUid == ownerUid))
      }
      if owned then
        val _ = client.services().inNamespace(namespace).withName(name).delete()
        log.debug("removed service {}/{}, which no longer serves HTTP", namespace, name)
      else if existing.isDefined then
        log.debug("left service {}/{} alone: not owned by this resource", namespace, name)

    case Action.EnsureHttpRoute(route) =>
      val _ = client.resource(route).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured httproute {}/{} for {}",
        route.getMetadata.getNamespace,
        route.getMetadata.getName,
        route.getSpec.getHostnames
      )

    case Action.RemoveHttpRoute(namespace, name, ownerUid) =>
      // Read-first and owner-checked, exactly as RemoveService: rendered on every pass for a
      // service that is not exposed, and never allowed to delete a route this resource did not
      // create.
      val routes   = client.resources(classOf[HTTPRoute]).inNamespace(namespace).withName(name)
      val existing = routeIfAny(namespace, name)
      val owned = existing.exists { route =>
        ownerUid.nonEmpty &&
        Option(route.getMetadata.getOwnerReferences).exists(_.asScala.exists(_.getUid == ownerUid))
      }
      if owned then
        val _ = routes.delete()
        log.debug("removed httproute {}/{}: the service is no longer exposed", namespace, name)
      else if existing.isDefined then
        log.debug("left httproute {}/{} alone: not owned by this resource", namespace, name)

    case Action.SetStatus(namespace, name, status) =>
      // Through the status subresource, so the operator never rewrites desired state. Its
      // RBAC grants `nakkaservices/status: update` and not `nakkaservices: update`, which
      // makes that structural rather than a discipline.
      val resources = client.resources(classOf[NakkaService]).inNamespace(namespace).withName(name)
      if resources.get() == null then
        log.debug("resource {}/{} vanished before its status could be written", namespace, name)
      else
        // editStatus rather than updateStatus/patchStatus, both of which fabric8 7.x
        // deprecates: it re-reads and patches in one call, so a spec change landing
        // between the reconciler's read and this write is not clobbered.
        val _ = resources.editStatus { (current: NakkaService) =>
          current.setStatus(status)
          current
        }
        log.debug("set status {}/{} to {}", namespace, name, status.lifecycle)

    case Action.EnsureCluster(cluster) =>
      val _ = client.resource(cluster).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured cluster {}/{}",
        cluster.getMetadata.getNamespace,
        cluster.getMetadata.getName
      )

    case Action.EnsureCredentials(secret) =>
      // The one action in this file that is not server-side apply, on purpose: this must
      // create-if-absent, never overwrite. Regenerating a password under a running service on
      // every reconcile pass is a self-inflicted outage on a timer.
      val existing = client
        .secrets()
        .inNamespace(secret.getMetadata.getNamespace)
        .withName(secret.getMetadata.getName)
        .get()
      if existing == null then
        val _ = client.resource(secret).create()
        log.debug(
          "created credentials {}/{}",
          secret.getMetadata.getNamespace,
          secret.getMetadata.getName
        )
      else
        log.debug(
          "credentials {}/{} already exist; not overwriting",
          secret.getMetadata.getNamespace,
          secret.getMetadata.getName
        )

    case Action.EnsureDatabaseRole(role) =>
      val _ = client.resource(role).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured database role {}/{}",
        role.getMetadata.getNamespace,
        role.getMetadata.getName
      )

    case Action.EnsureDatabase(database) =>
      val _ =
        client.resource(database).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured database {}/{}",
        database.getMetadata.getNamespace,
        database.getMetadata.getName
      )

    case Action.EnsureSchemaConfig(configMap) =>
      val _ =
        client.resource(configMap).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured schema config {}/{}",
        configMap.getMetadata.getNamespace,
        configMap.getMetadata.getName
      )

  /**
   * Reads what the cluster currently has for one service.
   *
   * Returns `None` for an object nakka does not own, which is how a name collision with a foreign
   * Deployment becomes "there is nothing here" rather than "adopt it".
   */
  override def snapshot(namespace: String, name: String): Option[ClusterSnapshot] =
    Option(client.apps().deployments().inNamespace(namespace).withName(name).get())
      .filter(d => Labels.ownedByNakka(d.getMetadata.getLabels))
      .map(ClusterSnapshot.of)

  /** Whether a Deployment exists under this name that nakka does **not** own. */
  override def foreignObjectAt(namespace: String, name: String): Boolean =
    Option(client.apps().deployments().inNamespace(namespace).withName(name).get())
      .exists(d => !Labels.ownedByNakka(d.getMetadata.getLabels))

  /** Pods backing a Deployment, read only to explain why it is not ready. */
  override def podProblems(
      namespace: String,
      serviceName: String,
      projectId: String
  ): Vector[PodProblem] =
    client
      .pods()
      .inNamespace(namespace)
      .withLabels(Labels.identity(projectId, serviceName).asJava)
      .list()
      .getItems
      .asScala
      .toVector
      .flatMap(PodProblem.of)

  /** The status currently recorded on a resource, for the "has anything changed" check. */
  def recordedStatus(namespace: String, name: String): Option[NakkaServiceStatus] =
    Option(client.resources(classOf[NakkaService]).inNamespace(namespace).withName(name).get())
      .flatMap(r => Option(r.getStatus))

  /**
   * The route, or None — also when the cluster has no Gateway API at all. Both the removal and the
   * status read happen on every pass for every service, so a cluster without the CRDs installed
   * must read as "no route", not fail every reconcile; only an *exposed* service needs the API, and
   * its EnsureHttpRoute fails loudly on its own.
   */
  private def routeIfAny(namespace: String, name: String): Option[HTTPRoute] =
    try Option(client.resources(classOf[HTTPRoute]).inNamespace(namespace).withName(name).get())
    catch
      case e: KubernetesClientException if e.getCode == 404 =>
        log.debug(
          "no Gateway API in this cluster; treating httproute {}/{} as absent",
          namespace,
          name
        )
        None

  override def observeRoute(namespace: String, name: String): Option[RouteView] =
    routeIfAny(namespace, name)
      .map { route =>
        val parent = Option(route.getStatus)
          .flatMap(s => Option(s.getParents))
          .map(_.asScala)
          .getOrElse(Nil)
          .find { p =>
            val ref = p.getParentRef
            ref != null && ref.getName == Rendering.GatewayName &&
            Option(ref.getNamespace).forall(_ == Rendering.GatewayNamespace)
          }
        def condition(kind: String): Option[(Boolean, String)] =
          parent
            .flatMap(p => Option(p.getConditions))
            .map(_.asScala)
            .getOrElse(Nil)
            .find(_.getType == kind)
            .map(c => (c.getStatus == "True", Option(c.getReason).getOrElse("")))
        RouteView(accepted = condition("Accepted"), resolvedRefs = condition("ResolvedRefs"))
      }

  override def observeDatabase(
      namespace: String,
      clusterName: String,
      serviceName: String
  ): DatabaseObservation =
    val secret = Option(client.secrets().inNamespace(namespace).withName(s"$serviceName-db").get())
    val cluster =
      Option(
        client
          .resources(classOf[PostgresCluster])
          .inNamespace(namespace)
          .withName(clusterName)
          .get()
      )
    val role =
      Option(
        client
          .resources(classOf[PostgresDatabaseRole])
          .inNamespace(namespace)
          .withName(serviceName)
          .get()
      )
    val database =
      Option(
        client
          .resources(classOf[PostgresDatabase])
          .inNamespace(namespace)
          .withName(serviceName)
          .get()
      )

    DatabaseObservation(
      clusterReadyInstances =
        cluster.flatMap(c => Option(c.getStatus)).map(_.readyInstances).getOrElse(0),
      secretExists = secret.isDefined,
      role = objectState(role.map(r => Option(r.getStatus))),
      database = objectState(database.map(d => Option(d.getStatus))),
      secretCreatedAt = secret.flatMap(s => parseTimestamp(s.getMetadata.getCreationTimestamp))
    )

  override def resourceCreatedAt(namespace: String, name: String): Option[Instant] =
    Option(client.resources(classOf[NakkaService]).inNamespace(namespace).withName(name).get())
      .flatMap(r => parseTimestamp(r.getMetadata.getCreationTimestamp))

  /**
   * `resource` is `None` when the object does not exist, `Some(None)` when it exists with no status
   * yet (just created), `Some(Some(status))` once CNPG has reconciled it at least once.
   */
  private def objectState(
      resource: Option[Option[nakka.operator.cnpg.CnpgReconcileStatus]]
  ): CnpgObjectState =
    resource match
      case None       => CnpgObjectState.absent
      case Some(None) => CnpgObjectState(exists = true, applied = false, message = None)
      case Some(Some(status)) =>
        CnpgObjectState(exists = true, applied = status.applied, message = status.message)

  private def parseTimestamp(raw: String): Option[Instant] =
    Option(raw).flatMap(t => Try(Instant.parse(t)).toOption)
