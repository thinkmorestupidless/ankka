package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.gatewayapi.v1.{BackendTLSPolicy, HTTPRoute}
import io.fabric8.kubernetes.api.model.{NamespaceBuilder, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceStatus, CloudResource}
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
   * Everything `ObjectStorage.decide` needs about one bucket (feature 034). A store that cannot be
   * reached is an answer, not a failure: the pass goes on and the status says why it waits. An
   * executor with no store has nothing to say.
   */
  def observeObjectStorage(@scala.annotation.unused bucket: String): ObjectStorageObservation =
    ObjectStorageObservation.empty

  /**
   * How one phase's Job of a move ended, if it has (feature 039): its counts, and the mover's
   * report from its pod's termination message. A Job its time-to-live removed is `Absent`, which is
   * why the move's state is kept in the status rather than read back from the Job.
   */
  def observeMoveJob(
      @scala.annotation.unused namespace: String,
      @scala.annotation.unused name: String
  ): StorageMove.JobOutcome = StorageMove.JobOutcome.Absent

  /**
   * One cloud request as the API server holds it (feature 044): its generation, when it was made
   * and the provider's answer. None when it, or the type, does not exist yet.
   */
  def observeCloudResource(namespace: String, name: String): Option[CloudObservation] =
    val _ = (namespace, name)
    None

  /**
   * What `BrokerProvisioning.decide` needs: the service's user and each of `topics`, by the name
   * the broker holds it under, in the broker's namespace (feature 027).
   */
  def observeBroker(namespace: String, user: String): BrokerObservation

  /**
   * What `TopicProvisioning.decide` needs for a project: each of `topics`, by the name the broker
   * holds it under, in the broker's namespace.
   */
  def observeTopics(namespace: String, topics: Vector[String]): Map[String, TopicState]

  /**
   * What `BackupStatus.of` needs about one archiving cluster (feature 041): the cluster's status,
   * the plugin's recovery window for its line, and its newest base backup. A cluster without the
   * plugin's or CNPG's types installed reads as nothing found.
   */
  def observeBackups(
      @scala.annotation.unused namespace: String,
      @scala.annotation.unused cluster: String,
      @scala.annotation.unused objectStore: String
  ): BackupObservation = BackupObservation.empty

  /**
   * Runs one of `DatabaseQueries`' statements by `psql` as `postgres` in `pod`'s database
   * container, against `database`, and answers its output; `None` when it could not be run. The
   * operator's only read inside a database (research R11).
   */
  def query(
      @scala.annotation.unused namespace: String,
      @scala.annotation.unused pod: String,
      @scala.annotation.unused database: String,
      @scala.annotation.unused sql: String
  ): Option[String] = None

  /**
   * The services of a project's namespace and the database cluster each is switched to, `None` for
   * the project database (feature 041): who a restore is verified for, and who uses it.
   */
  def servicesIn(
      @scala.annotation.unused namespace: String
  ): Vector[(String, Option[String])] = Vector.empty

  /** The project's resource (feature 041): its database settings and backups; none without one. */
  def projectSpec(
      @scala.annotation.unused namespace: String,
      @scala.annotation.unused projectId: String
  ): Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec] = None

  /**
   * The database clusters of a rehearsal namespace (feature 041), each with when it expires, as its
   * annotation says; none when the namespace or the type is not there.
   */
  def rehearsalClusters(
      @scala.annotation.unused namespace: String
  ): Vector[(String, Option[Instant])] = Vector.empty

  /**
   * Removes a rehearsal's cluster (feature 041), the one delete the operator is granted, and only
   * in a rehearsal namespace. Answers why, rather than throwing, when it could not: a rehearsal
   * that could not be removed is reported, and removed when its time to live passes.
   */
  def deleteRehearsalCluster(
      @scala.annotation.unused namespace: String,
      @scala.annotation.unused name: String
  ): Either[String, Unit] = Left("this executor removes nothing")

  /** The brokers a project declares (feature 037), from its `AnkkaProject`; none without one. */
  def projectBrokers(
      namespace: String,
      projectId: String
  ): Vector[com.thinkmorestupidless.ankka.crd.ProjectBrokerEntry] =
    val _ = (namespace, projectId)
    Vector.empty

  /**
   * Where a project's new buckets are made (feature 039), from its `AnkkaProject`; none without
   * one, and then the installation's location.
   */
  def projectLocation(namespace: String, projectId: String): Option[String] =
    val _ = (namespace, projectId)
    None

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
/**
 * @param telemetryHeaders
 *   what the installation sends with its telemetry, which `EnsureTelemetrySecret` writes into each
 *   service's own Secret: held here and in no action, since actions are printed.
 * @param store
 *   the installation's object store (feature 034), which the bucket and credential actions reach
 */
object Fabric8Executor:

  /**
   * The executor an installation's settings describe: the telemetry credential, and the object
   * store with its region. Every reconciler builds its executor here, so none can be missing the
   * store: the project reconciler's was, and every rehearsal failed on its backup credential.
   */
  def of(client: KubernetesClient, settings: Settings): Fabric8Executor =
    new Fabric8Executor(
      client,
      settings.otlpHeaders,
      settings.objectStore.map(store => GarageStore(store.adminUrl, store.adminToken)),
      settings.rotationGrace,
      settings.objectStore.map(_.region).getOrElse("garage")
    )

final class Fabric8Executor(
    client: KubernetesClient,
    telemetryHeaders: Option[Settings.Credential] = None,
    store: Option[ObjectStore] = None,
    /** How long an old storage credential works after a new one is in place (feature 039). */
    rotationGrace: scala.concurrent.duration.FiniteDuration = Settings.default.rotationGrace,
    /** The store's region, which a database's archiver signs its requests for (feature 041). */
    objectStoreRegion: String = "garage"
) extends Executor:

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

  /** The database credential Secrets this process has created or found created, as above. */
  private val credentialsKnown =
    java.util.concurrent.ConcurrentHashMap.newKeySet[(String, String)]()
  private val random = new java.security.SecureRandom()

  /**
   * The storage credentials this process has issued or found issued (feature 034). Only the
   * `create` of a Secret is ever sent; whether it met one is the only thing learned about it.
   */
  private val storageCredentials = store.map(s =>
    StorageCredential(
      s,
      new SecretWriter:
        def create(secret: io.fabric8.kubernetes.api.model.Secret): SecretWriter.Outcome =
          try
            client.resource(secret).create(): Unit
            SecretWriter.Outcome.Created
          catch case e: KubernetesClientException if e.getCode == 409 => SecretWriter.Outcome.Exists
        def patch(namespace: String, name: String, entries: Map[String, String]): Unit =
          val body = new io.fabric8.kubernetes.api.model.SecretBuilder()
            .withStringData(entries.asJava)
            .build()
          client
            .secrets()
            .inNamespace(namespace)
            .withName(name)
            .patch(
              io.fabric8.kubernetes.client.dsl.base.PatchContext
                .of(io.fabric8.kubernetes.client.dsl.base.PatchType.JSON_MERGE),
              body
            ): Unit
    )
  )

  /**
   * The credential generation this process last issued each backup key at (feature 041): a higher
   * one on the project's resource is a member's re-issue. Not remembered across a restart, which
   * then issues nothing: a re-issue asked for while no operator ran happens at the next one asked.
   */
  private val backupGenerations =
    new java.util.concurrent.ConcurrentHashMap[(String, String), Int]()

  private def requireStore(): ObjectStore =
    store.getOrElse(
      throw new IllegalStateException("an object storage action was rendered with no store")
    )

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

    case Action.SetProjectStatus(namespace, name, status) =>
      val resources = client
        .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaProject])
        .inNamespace(namespace)
        .withName(name)
      if resources.get() == null then
        log.debug("project {}/{} vanished before its status could be written", namespace, name)
      else
        val _ = resources.editStatus { (current: com.thinkmorestupidless.ankka.crd.AnkkaProject) =>
          current.setStatus(status)
          current
        }
        log.debug("set project status {}/{}", namespace, name)

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
      // Created, never read, never overwritten: a `create` of a Secret that exists is a 409, which
      // is how the operator learns it is there — its grant on Secrets has no `get`. Regenerating a
      // credential under a running service on every pass would be an outage on a timer.
      val key = (secret.getMetadata.getNamespace, secret.getMetadata.getName)
      if !credentialsKnown.contains(key) then
        try
          client.resource(secret).create(): Unit
          log.debug("created credentials {}/{}", key._1, key._2)
        catch
          case e: KubernetesClientException if e.getCode == 409 =>
            log.debug("credentials {}/{} already exist; not overwriting", key._1, key._2)
        credentialsKnown.add(key): Unit

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

    case Action.EnsureTelemetrySecret(namespace, name, labels, owner) =>
      // Applied on every pass, so a change to the installation's headers reaches every service at
      // its next reconcile; a running pod reads the new value when it next starts. Owned by the
      // service, so it goes when the service does. The value is never logged.
      telemetryHeaders match
        case None =>
          log.warn("asked for the telemetry secret {}/{} with no headers to write", namespace, name)
        case Some(headers) =>
          val secret = new io.fabric8.kubernetes.api.model.SecretBuilder()
            .withMetadata(
              new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .withLabels(labels.asJava)
                .withOwnerReferences(owner)
                .build()
            )
            .withType("Opaque")
            .withStringData(java.util.Map.of(Names.TelemetryHeadersEntry, headers.value))
            .build()
          val _ =
            client.resource(secret).fieldManager(FieldManager).forceConflicts().serverSideApply()
          log.debug("ensured telemetry secret {}/{}", namespace, name)

    case Action.EnsureDatabaseRole(role) =>
      val _ = client.resource(role).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured database role {}/{}",
        role.getMetadata.getNamespace,
        role.getMetadata.getName
      )

    case Action.EnsureKafkaUser(user) =>
      val _ = client.resource(user).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug("ensured kafka user {}/{}", user.getMetadata.getNamespace, user.getMetadata.getName)

    case Action.EnsureKafkaTopic(topic) =>
      val _ = client.resource(topic).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured kafka topic {}/{}",
        topic.getMetadata.getNamespace,
        topic.getMetadata.getName
      )

    case Action.EnsureCloudResource(request) =>
      val _ =
        client.resource(request).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured cloud request {} {}/{}",
        request.getSpec.kind,
        request.getMetadata.getNamespace,
        request.getMetadata.getName
      )

    case Action.EnsureDatabase(database) =>
      val _ =
        client.resource(database).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured database {}/{}",
        database.getMetadata.getNamespace,
        database.getMetadata.getName
      )

    case Action.EnsureBucket(bucket) =>
      val s = requireStore()
      s.bucket(bucket) match
        case None =>
          s.createBucket(bucket): Unit
          log.info("made bucket {}", bucket)
        case Some(info) if info.allowedKeys.isEmpty =>
          // Made again after its service deleted it: the service's keys reach it once more, of
          // whatever generation (feature 039), unless they have ended.
          s.keysOf(bucket).filterNot(_.expired).foreach(k => s.allow(info.id, k.accessKeyId))
        case Some(_) => ()

    case Action.EnsureObjectStore(store) =>
      client.resource(store).fieldManager(FieldManager).forceConflicts().serverSideApply(): Unit

    case Action.EnsureScheduledBackup(schedule) =>
      // Not until the cluster archives: its first base backup is taken the moment the schedule
      // exists, and while CNPG is still adding the plugin it fails ("requested plugin is not
      // available", or the instance restarted under it) and the next is a day away. The pass is
      // repeated at every resync, so the schedule follows within seconds of the archive working.
      val namespace = schedule.getMetadata.getNamespace
      val cluster   = schedule.getSpec.cluster.name
      val archiving = ifTypeExists(
        client.resources(classOf[PostgresCluster]).inNamespace(namespace).withName(cluster).get()
      ).flatMap(c => Option(c.getStatus))
        .exists(_.conditions.exists(c => c.`type` == "ContinuousArchiving" && c.status == "True"))
      if archiving then
        client
          .resource(schedule)
          .fieldManager(FieldManager)
          .forceConflicts()
          .serverSideApply(): Unit
        baseBackupAgain(namespace, cluster)
      else log.debug("{}/{}: no base backup scheduled until it archives", namespace, cluster)

    case Action.EnsureBackupCredential(namespace, bucket, keyName, permission, generation) =>
      requireStore(): Unit
      val entries  = StorageCredential.backupEntries(objectStoreRegion)
      val issuedAt = backupGenerations.getOrDefault((namespace, keyName), -1)
      if issuedAt >= 0 && generation > issuedAt then
        storageCredentials.get.reissueKey(
          namespace,
          com.thinkmorestupidless.ankka.crd.Buckets.BackupSecret,
          bucket,
          keyName,
          permission,
          entries
        )
        log.info("issued the backup credential {}/{} again, at {}", namespace, keyName, generation)
      else
        storageCredentials.get.ensure(
          namespace,
          com.thinkmorestupidless.ankka.crd.Buckets.BackupSecret,
          Map(Labels.ManagedByKey -> Labels.ManagedByAnkka),
          bucket,
          keyName = Some(keyName),
          permission = permission,
          entries = entries
        ) match
          case StorageCredential.Result.Created =>
            log.info("issued the backup credential {}/{}", namespace, keyName)
          case StorageCredential.Result.Replaced =>
            log.warn("the object store held no key {}; a new one was written", keyName)
          case StorageCredential.Result.Unchanged => ()
      backupGenerations.put((namespace, keyName), generation): Unit

    case Action.EnsureStorageCredential(namespace, name, labels, bucket, generation) =>
      requireStore(): Unit
      storageCredentials.get.ensure(namespace, name, labels, bucket, generation) match
        case StorageCredential.Result.Created =>
          log.info("issued the storage credential {}/{}", namespace, name)
        case StorageCredential.Result.Replaced =>
          log.warn(
            "the object store held no key for {}/{}; a new one was written, and running " +
              "instances read it when they restart",
            namespace,
            name
          )
        case StorageCredential.Result.Unchanged => ()

    case Action.ReissueStorageCredential(namespace, name, bucket, generation) =>
      requireStore(): Unit
      storageCredentials.get.reissue(
        namespace,
        name,
        bucket,
        generation,
        java.time.Instant.now().plusSeconds(rotationGrace.toSeconds)
      )
      log.info(
        "issued the storage credential {}/{} again, at generation {}; the old one ends in {}",
        namespace,
        name,
        generation,
        rotationGrace
      )

    case Action.DeleteExpiredKeys(bucket) =>
      requireStore(): Unit
      storageCredentials.get.deleteExpired(bucket)

    case Action.PauseWrites(bucket, generation) =>
      requireStore(): Unit
      storageCredentials.get.pauseWrites(bucket, generation)
      log.info("paused writes to bucket {}", bucket)

    case Action.ResumeWrites(bucket, generation) =>
      requireStore(): Unit
      storageCredentials.get.resumeWrites(bucket, generation)
      log.info("resumed writes to bucket {}", bucket)

    case Action.EnsureMoveJob(job) =>
      // A Job's template is immutable once it exists, and the one a pass renders is the same one
      // the last pass did, so an existing Job is left as it is.
      val jobs = client.batch().v1().jobs().inNamespace(job.getMetadata.getNamespace)
      if jobs.withName(job.getMetadata.getName).get() == null then
        val _ = jobs.resource(job).fieldManager(FieldManager).forceConflicts().serverSideApply()
        log.info("started {}/{}", job.getMetadata.getNamespace, job.getMetadata.getName)

    case Action.SetBucketCors(bucket, origins) =>
      val s = requireStore()
      s.bucket(bucket).foreach { info =>
        if info.corsOrigins.toSet != origins.toSet then
          s.setCors(info.id, origins)
          log.info("set the cors rule of bucket {} to {}", bucket, origins.mkString(", "))
      }

    case Action.EnsureReferenceGrant(grant) =>
      val _ = client.resource(grant).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured referencegrant {}/{}",
        grant.getMetadata.getNamespace,
        grant.getMetadata.getName
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

    case Action.EnsureProjectConfig(configMap) =>
      val _ =
        client.resource(configMap).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug(
        "ensured project config {}/{}",
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
      role = objectState(role.map(r => Option(r.getStatus))),
      database = objectState(database.map(d => Option(d.getStatus))),
      databaseCreatedAt = database.flatMap(d => parseTimestamp(d.getMetadata.getCreationTimestamp)),
      roleHasPassword = role
        .flatMap(r => Option(r.getSpec))
        .exists(spec => !spec.disablePassword.contains(true))
    )

  override def observeBackups(
      namespace: String,
      cluster: String,
      objectStore: String
  ): BackupObservation =
    val status = ifTypeExists(
      client.resources(classOf[PostgresCluster]).inNamespace(namespace).withName(cluster).get()
    ).flatMap(c => Option(c.getStatus))
    val window = ifTypeExists(
      client
        .resources(classOf[cnpg.BarmanObjectStore])
        .inNamespace(namespace)
        .withName(objectStore)
        .get()
    ).flatMap(s => Option(s.getStatus)).flatMap(_.serverRecoveryWindow.get(cluster))
    val newest = ifTypeExists(
      client.resources(classOf[cnpg.PostgresBackup]).inNamespace(namespace).list().getItems
    ).map(_.asScala.toVector)
      .getOrElse(Vector.empty)
      .filter(b => Option(b.getSpec).exists(_.cluster.name == cluster))
      .sortBy(b => Option(b.getMetadata.getCreationTimestamp).getOrElse(""))
      .lastOption
      .flatMap(b => Option(b.getStatus))
    BackupObservation(status, window, newest)

  override def rehearsalClusters(namespace: String): Vector[(String, Option[Instant])] =
    ifTypeExists(client.resources(classOf[PostgresCluster]).inNamespace(namespace).list().getItems)
      .map(_.asScala.toVector)
      .getOrElse(Vector.empty)
      .map { c =>
        val expires = Option(c.getMetadata.getAnnotations)
          .flatMap(a => Option(a.get(RehearsalRendering.ExpiresAt)))
          .flatMap(t => scala.util.Try(Instant.parse(t)).toOption)
        c.getMetadata.getName -> expires
      }

  override def deleteRehearsalCluster(namespace: String, name: String): Either[String, Unit] =
    if !namespace.endsWith(com.thinkmorestupidless.ankka.crd.Recovery.RehearsalSuffix) then
      Left(s"$namespace is not a rehearsal namespace; the operator removes no other database")
    else
      try
        client.resources(classOf[PostgresCluster]).inNamespace(namespace).withName(name).delete()
        log.info(s"removed the rehearsal's database $namespace/$name")
        Right(())
      catch
        case scala.util.control.NonFatal(e) =>
          Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  override def query(
      namespace: String,
      pod: String,
      database: String,
      sql: String
  ): Option[String] =
    val out = new java.io.ByteArrayOutputStream
    val err = new java.io.ByteArrayOutputStream
    try
      val watch = client
        .pods()
        .inNamespace(namespace)
        .withName(pod)
        .inContainer("postgres")
        .writingOutput(out)
        .writingError(err)
        .exec("psql", "-U", "postgres", "-d", database, "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql)
      try
        val code = watch.exitCode().get(30, java.util.concurrent.TimeUnit.SECONDS)
        if code == 0 then Some(out.toString(java.nio.charset.StandardCharsets.UTF_8))
        else
          log.debug(
            s"psql in $namespace/$pod against $database exited $code: " +
              err.toString(java.nio.charset.StandardCharsets.UTF_8).trim
          )
          None
      finally watch.close()
    catch
      case e: Exception =>
        log.debug(s"psql in $namespace/$pod could not be run: ${e.getMessage}")
        None

  /** One of Strimzi's objects as found, or absent. */
  private def strimziState(
      found: Option[io.fabric8.kubernetes.api.model.HasMetadata],
      status: => Option[com.thinkmorestupidless.ankka.operator.strimzi.StrimziStatus]
  ): StrimziObjectState =
    found match
      case None => StrimziObjectState.absent
      case Some(resource) =>
        StrimziObjectState.found(
          Option(resource.getMetadata.getGeneration).map(_.longValue),
          status,
          parseTimestamp(resource.getMetadata.getCreationTimestamp)
        )

  /** A cluster without Strimzi's resource types reads as nothing made yet, not as a failure. */
  /**
   * A cluster whose every base backup failed is given another (`BaseBackupRetry`): its schedule's
   * first can fail while CNPG is still adding the plugin, and its next is a day away.
   */
  private def baseBackupAgain(namespace: String, cluster: String): Unit =
    def instant(text: String) = scala.util.Try(java.time.Instant.parse(text)).toOption
    val attempts = ifTypeExists(
      client.resources(classOf[cnpg.PostgresBackup]).inNamespace(namespace).list().getItems
    ).map(_.asScala.toVector)
      .getOrElse(Vector.empty)
      .filter(b => Option(b.getSpec).exists(_.cluster.name == cluster))
      .map { b =>
        val status = Option(b.getStatus)
        BaseBackupRetry.Attempt(
          status.flatMap(_.phase),
          status
            .flatMap(_.stoppedAt)
            .flatMap(instant)
            .orElse(Option(b.getMetadata.getCreationTimestamp).flatMap(instant))
        )
      }
    val now = java.time.Instant.now()
    if BaseBackupRetry.due(attempts, now) then
      val backup = new cnpg.PostgresBackup
      backup.setMetadata(
        new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(BaseBackupRetry.name(cluster, now))
          .build()
      )
      backup.setSpec(
        cnpg.BackupSpec(
          cnpg.ClusterRef(cluster),
          pluginConfiguration = Some(cnpg.BackupPluginConfiguration())
        )
      )
      client.resource(backup).create(): Unit
      log.info("{}/{}: every base backup failed; taking another", namespace, cluster)

  private def ifTypeExists[A](read: => A): Option[A] =
    try Option(read)
    catch case e: KubernetesClientException if e.getCode == 404 => None

  override def observeBroker(namespace: String, user: String): BrokerObservation =
    val found = ifTypeExists(
      client
        .resources(classOf[com.thinkmorestupidless.ankka.operator.strimzi.KafkaUserResource])
        .inNamespace(namespace)
        .withName(user)
        .get()
    )
    BrokerObservation(user = strimziState(found, found.flatMap(u => Option(u.getStatus))))

  override def servicesIn(namespace: String): Vector[(String, Option[String])] =
    client
      .resources(classOf[AnkkaService])
      .inNamespace(namespace)
      .list()
      .getItems
      .asScala
      .toVector
      .flatMap(r => Option(r.getSpec))
      .filter(spec => spec.provisionDatabase && spec.database != "none")
      .map(spec => spec.serviceName -> spec.databaseCluster)

  override def projectSpec(
      namespace: String,
      projectId: String
  ): Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec] =
    ifTypeExists(
      client
        .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaProject])
        .inNamespace(namespace)
        .withName(projectId)
        .get()
    ).flatMap(p => Option(p.getSpec))

  override def projectBrokers(
      namespace: String,
      projectId: String
  ): Vector[com.thinkmorestupidless.ankka.crd.ProjectBrokerEntry] =
    ifTypeExists(
      client
        .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaProject])
        .inNamespace(namespace)
        .withName(projectId)
        .get()
    ).flatMap(p => Option(p.getSpec)).map(_.brokers.toVector).getOrElse(Vector.empty)

  override def projectLocation(namespace: String, projectId: String): Option[String] =
    ifTypeExists(
      client
        .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaProject])
        .inNamespace(namespace)
        .withName(projectId)
        .get()
    ).flatMap(p => Option(p.getSpec)).flatMap(_.bucketLocation).filter(_.nonEmpty)

  override def observeTopics(namespace: String, topics: Vector[String]): Map[String, TopicState] =
    val topicClient = client
      .resources(classOf[com.thinkmorestupidless.ankka.operator.strimzi.KafkaTopicResource])
      .inNamespace(namespace)
    topics.map { name =>
      val found = ifTypeExists(topicClient.withName(name).get())
      val spec  = found.flatMap(t => Option(t.getSpec))
      name -> TopicState(
        strimziState(found, found.flatMap(t => Option(t.getStatus))),
        spec.map(_.partitions),
        spec.map(s => StrimziRendering.compacted(s.config))
      )
    }.toMap

  override def observeObjectStorage(bucket: String): ObjectStorageObservation =
    store.fold(ObjectStorageObservation.empty) { s =>
      try
        s.bucket(bucket) match
          case None => ObjectStorageObservation.empty
          case Some(info) =>
            ObjectStorageObservation(
              bucketCreated = Some(info.created),
              keyAllowed = info.allowedKeys.nonEmpty
            )
      catch
        case e: ObjectStoreUnavailable =>
          ObjectStorageObservation(unreachable = Some(e.getMessage))
    }

  override def observeMoveJob(namespace: String, name: String): StorageMove.JobOutcome =
    Option(client.batch().v1().jobs().inNamespace(namespace).withName(name).get()) match
      case None => StorageMove.JobOutcome.Absent
      case Some(job) =>
        val status    = Option(job.getStatus)
        val succeeded = status.flatMap(s => Option(s.getSucceeded)).exists(_.intValue > 0)
        val failed = status.flatMap(s => Option(s.getFailed)).exists(_.intValue > 0) ||
          status
            .flatMap(s => Option(s.getConditions))
            .exists(_.asScala.exists(c => c.getType == "Failed" && c.getStatus == "True"))
        if !succeeded && !failed then StorageMove.JobOutcome.Running
        else
          // The mover's report, from its pod's termination message (FallbackToLogsOnError).
          val message = client
            .pods()
            .inNamespace(namespace)
            .withLabel("job-name", name)
            .list()
            .getItems
            .asScala
            .flatMap(p => Option(p.getStatus).flatMap(s => Option(s.getContainerStatuses)))
            .flatMap(_.asScala)
            .flatMap(c => Option(c.getState).flatMap(s => Option(s.getTerminated)))
            .flatMap(t => Option(t.getMessage))
            .lastOption
          val report = message.flatMap(StorageMove.report)
          if succeeded then
            StorageMove.JobOutcome.Succeeded(report.getOrElse(StorageMove.MoveReport()))
          else
            val deadline = status
              .flatMap(s => Option(s.getConditions))
              .exists(_.asScala.exists(_.getReason == "DeadlineExceeded"))
            StorageMove.JobOutcome.Failed(
              report.getOrElse(
                StorageMove.MoveReport(reason =
                  Some(
                    if deadline then "it did not finish within the write pause bound"
                    else message.getOrElse("it stopped without a report")
                  )
                )
              )
            )

  override def observeCloudResource(namespace: String, name: String): Option[CloudObservation] =
    ifTypeExists(
      client.resources(classOf[CloudResource]).inNamespace(namespace).withName(name).get()
    ).map { found =>
      CloudObservation(
        generation = Option(found.getMetadata.getGeneration).map(_.longValue).getOrElse(0L),
        createdAt = parseTimestamp(found.getMetadata.getCreationTimestamp).getOrElse(Instant.EPOCH),
        status = Option(found.getStatus),
        spec = Option(found.getSpec),
        annotations =
          Option(found.getMetadata.getAnnotations).map(_.asScala.toMap).getOrElse(Map.empty)
      )
    }

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
