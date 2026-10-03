package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.gatewayapi.v1.{BackendTLSPolicy, HTTPRoute}
import io.fabric8.kubernetes.api.model.{NamespaceBuilder, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceStatus}
import com.thinkmorestupidless.ankka.operator.cnpg.{
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

  /** One service's Deployment, or None if ankka owns nothing under that name. */
  def snapshot(namespace: String, name: String): Option[ClusterSnapshot]

  /**
   * Whether something ankka does **not** own already holds this name.
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
   * When the `AnkkaService` resource's current incarnation was created — for the recovered check.
   */
  def resourceCreatedAt(namespace: String, name: String): Option[Instant]

  /**
   * The labels on an ankka-owned Deployment's pod template, or None when there is no such
   * Deployment.
   */
  def podTemplateLabels(namespace: String, name: String): Option[Map[String, String]]

  /**
   * Waits, up to `timeout`, until no pod matches `selector`; true once none does. For the one
   * transition that must not overlap old pods with new ones.
   */
  def awaitNoPods(
      namespace: String,
      selector: Map[String, String],
      timeout: scala.concurrent.duration.FiniteDuration
  ): Boolean

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

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.executor")

  /**
   * Distinct from the control plane's `ankka-controlplane`.
   *
   * Server-side apply records which manager owns which field. Two managers with one name would
   * fight over every field; two names let each revert edits to its own without touching the other's
   * — the control plane owns the resource's spec, the operator owns the Deployment's.
   */
  private val FieldManager = "ankka-operator"

  /**
   * The secret keys this process has made or found made, so that a reconcile pass, which renders
   * `EnsureSecretKey` every time, asks the API server once per service and not once per pass.
   */
  private val secretKeysKnown = java.util.concurrent.ConcurrentHashMap.newKeySet[(String, String)]()
  private val random          = new java.security.SecureRandom()

  def execute(action: Action): Unit = action match
    case Action.NoAction => ()

    case Action.EnsureNamespace(name) =>
      val namespace = new NamespaceBuilder()
        .withMetadata(
          new ObjectMetaBuilder()
            .withName(name)
            .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava)
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

    case Action.EnsureGrpcPeers(service, ownerUid) =>
      val namespace = service.getMetadata.getNamespace
      val name      = service.getMetadata.getName
      val existing  = Option(client.services().inNamespace(namespace).withName(name).get())
      val ours = existing.forall { found =>
        ownerUid.nonEmpty &&
        Option(found.getMetadata.getOwnerReferences).exists(_.asScala.exists(_.getUid == ownerUid))
      }
      if ours then
        val _ =
          client.resource(service).fieldManager(FieldManager).forceConflicts().serverSideApply()
        log.debug("ensured grpc peers {}/{}", namespace, name)
      else
        // Another service's address, by the name this one derives. Leaving it is the only safe
        // answer; callers of this service then balance per connection, through its own address.
        log.warn(
          "left service {}/{} alone: it is not this resource's, so the gRPC peers address was not created",
          namespace,
          name
        )

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
      // RBAC grants `ankkaservices/status: update` and not `ankkaservices: update`, which
      // makes that structural rather than a discipline.
      val resources = client.resources(classOf[AnkkaService]).inNamespace(namespace).withName(name)
      if resources.get() == null then
        log.debug("resource {}/{} vanished before its status could be written", namespace, name)
      else
        // editStatus rather than updateStatus/patchStatus, both of which fabric8 7.x
        // deprecates: it re-reads and patches in one call, so a spec change landing
        // between the reconciler's read and this write is not clobbered.
        val _ = resources.editStatus { (current: AnkkaService) =>
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

    case Action.EnsureSecretKey(namespace, name, labels) =>
      // Created, never read: a create of a Secret that exists is a 409, which is the answer
      // "already made" — so the operator never brings a key back over the wire, and never makes a
      // second one under a service that has kept secrets with the first. The bytes are made here and
      // nowhere else, and never logged.
      if !secretKeysKnown.contains((namespace, name)) then
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        val secret = new io.fabric8.kubernetes.api.model.SecretBuilder()
          .withMetadata(
            new ObjectMetaBuilder()
              .withNamespace(namespace)
              .withName(name)
              .withLabels(labels.asJava)
              .build()
          )
          .withType("Opaque")
          .withStringData(
            java.util.Map
              .of(Names.SecretKeyEntry, java.util.Base64.getEncoder.encodeToString(bytes))
          )
          .build()
        try
          val _ = client.resource(secret).create()
          log.info("made the secret key {}/{}", namespace, name)
        catch
          case e: KubernetesClientException if e.getCode == 409 =>
            log.debug("the secret key {}/{} exists; leaving it as it is", namespace, name)
        secretKeysKnown.add((namespace, name)): Unit

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

    case Action.EnsureCertificate(certificate) =>
      val _ =
        client.resource(certificate).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured certificate {}/{}",
        certificate.getMetadata.getNamespace,
        certificate.getMetadata.getName
      )

    case Action.EnsureIssuer(issuer) =>
      val _ = client.resource(issuer).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug("ensured {} {}", issuer.getKind, issuer.getMetadata.getName)

    case Action.EnsureNetworkPolicy(policy) =>
      val _ = client.resource(policy).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured networkpolicy {}/{}",
        policy.getMetadata.getNamespace,
        policy.getMetadata.getName
      )

    case Action.RemoveNetworkPolicy(namespace, name, ownerUid) =>
      val policies = client.network().v1().networkPolicies().inNamespace(namespace).withName(name)
      val existing = Option(policies.get())
      if existing.exists(p => ownedBy(p.getMetadata, ownerUid)) then
        val _ = policies.delete()
        log.debug("removed networkpolicy {}/{}", namespace, name)

    case Action.EnsureBackendTlsPolicy(policy) =>
      val _ = client.resource(policy).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured backendtlspolicy {}/{}",
        policy.getMetadata.getNamespace,
        policy.getMetadata.getName
      )

    case Action.RemoveBackendTlsPolicy(namespace, name, ownerUid) =>
      // Absent-safe for the same reason as the route: rendered on every pass for every unexposed
      // service, so a cluster without the Gateway API must read as "no policy", not fail.
      val policies =
        client.resources(classOf[BackendTLSPolicy]).inNamespace(namespace).withName(name)
      val existing =
        try Option(policies.get())
        catch case e: KubernetesClientException if e.getCode == 404 => None
      if existing.exists(p => ownedBy(p.getMetadata, ownerUid)) then
        val _ = policies.delete()
        log.debug("removed backendtlspolicy {}/{}", namespace, name)

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
   * Returns `None` for an object ankka does not own, which is how a name collision with a foreign
   * Deployment becomes "there is nothing here" rather than "adopt it".
   */
  override def snapshot(namespace: String, name: String): Option[ClusterSnapshot] =
    Option(client.apps().deployments().inNamespace(namespace).withName(name).get())
      .filter(d => Labels.ownedByAnkka(d.getMetadata.getLabels))
      .map(ClusterSnapshot.of)

  /** Whether a Deployment exists under this name that ankka does **not** own. */
  override def foreignObjectAt(namespace: String, name: String): Boolean =
    Option(client.apps().deployments().inNamespace(namespace).withName(name).get())
      .exists(d => !Labels.ownedByAnkka(d.getMetadata.getLabels))

  /** Pods backing a Deployment, read only to explain why it is not ready. */
  override def podProblems(
      namespace: String,
      serviceName: String,
      projectId: String
  ): Vector[PodProblem] =
    val pods = client
      .pods()
      .inNamespace(namespace)
      .withLabels(Labels.identity(projectId, serviceName).asJava)
      .list()
      .getItems
      .asScala
      .toVector
    pods.flatMap(pod => PodProblem.of(pod).orElse(fromEvents(namespace, pod)))

  /**
   * For a pod no container state explains — running and never ready, or waiting on a volume — the
   * kubelet's own words, from its newest readiness or mount event. That is what turns "the deadline
   * passed" into "the readiness probe was refused" or "the certificate's Secret does not exist
   * yet".
   */
  private def fromEvents(
      namespace: String,
      pod: io.fabric8.kubernetes.api.model.Pod
  ): Option[PodProblem] =
    val uid = Option(pod.getMetadata).flatMap(m => Option(m.getUid)).getOrElse("")
    if uid.isEmpty || PodProblem.isReady(pod) then None
    else
      val events =
        try
          client
            .v1()
            .events()
            .inNamespace(namespace)
            .withField("involvedObject.uid", uid)
            .list()
            .getItems
            .asScala
            .toVector
        catch case _: KubernetesClientException => Vector.empty
      events
        .filter(e => PodProblem.FromEvents.contains(Option(e.getReason).getOrElse("")))
        .sortBy(e =>
          Option(e.getLastTimestamp).orElse(Option(e.getEventTime).map(_.getTime)).getOrElse("")
        )
        .lastOption
        .map(e =>
          PodProblem(
            pod = Option(pod.getMetadata).map(_.getName).getOrElse(""),
            reason = e.getReason,
            message = Option(e.getMessage).getOrElse("").trim
          )
        )

  override def podTemplateLabels(namespace: String, name: String): Option[Map[String, String]] =
    Option(client.apps().deployments().inNamespace(namespace).withName(name).get())
      .filter(d =>
        Labels.ownedByAnkka(Option(d.getMetadata.getLabels).getOrElse(java.util.Map.of()))
      )
      .map(d =>
        Option(d.getSpec)
          .flatMap(s => Option(s.getTemplate))
          .flatMap(t => Option(t.getMetadata))
          .flatMap(m => Option(m.getLabels))
          .map(_.asScala.toMap)
          .getOrElse(Map.empty)
      )

  override def awaitNoPods(
      namespace: String,
      selector: Map[String, String],
      timeout: scala.concurrent.duration.FiniteDuration
  ): Boolean =
    val deadline = timeout.fromNow
    def remaining =
      client.pods().inNamespace(namespace).withLabels(selector.asJava).list().getItems.size
    while remaining > 0 && deadline.hasTimeLeft() do Thread.sleep(1000)
    remaining == 0

  private def ownedBy(meta: io.fabric8.kubernetes.api.model.ObjectMeta, ownerUid: String): Boolean =
    ownerUid.nonEmpty &&
      Option(meta.getOwnerReferences).exists(_.asScala.exists(_.getUid == ownerUid))

  /** The status currently recorded on a resource, for the "has anything changed" check. */
  def recordedStatus(namespace: String, name: String): Option[AnkkaServiceStatus] =
    Option(client.resources(classOf[AnkkaService]).inNamespace(namespace).withName(name).get())
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
      secretCreatedAt = secret.flatMap(s => parseTimestamp(s.getMetadata.getCreationTimestamp)),
      roleHasPassword = role
        .flatMap(r => Option(r.getSpec))
        .exists(spec => !spec.disablePassword.contains(true))
    )

  override def resourceCreatedAt(namespace: String, name: String): Option[Instant] =
    Option(client.resources(classOf[AnkkaService]).inNamespace(namespace).withName(name).get())
      .flatMap(r => parseTimestamp(r.getMetadata.getCreationTimestamp))

  /**
   * `resource` is `None` when the object does not exist, `Some(None)` when it exists with no status
   * yet (just created), `Some(Some(status))` once CNPG has reconciled it at least once.
   */
  private def objectState(
      resource: Option[Option[com.thinkmorestupidless.ankka.operator.cnpg.CnpgReconcileStatus]]
  ): CnpgObjectState =
    resource match
      case None       => CnpgObjectState.absent
      case Some(None) => CnpgObjectState(exists = true, applied = false, message = None)
      case Some(Some(status)) =>
        CnpgObjectState(exists = true, applied = status.applied, message = status.message)

  private def parseTimestamp(raw: String): Option[Instant] =
    Option(raw).flatMap(t => Try(Instant.parse(t)).toOption)
