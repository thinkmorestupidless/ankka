package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import com.thinkmorestupidless.ankka.crd.{
  AnkkaService,
  AnkkaServiceDefinition,
  AnkkaServiceSpec,
  Buckets
}
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Clock, Instant}
import scala.concurrent.duration.FiniteDuration

/**
 * One pass for one service: read, render, act, report.
 *
 * The pass is level-triggered — it reads the world and makes it match, rather than responding to
 * what changed — so a lost event costs latency and never correctness, and running it twice is
 * harmless.
 */
final class ServiceReconciler(
    client: KubernetesClient,
    settings: Settings,
    executor: Executor,
    clock: Clock = Clock.systemUTC()
) extends Reconciler:

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.reconciler")

  @volatile private var later: (ServiceRef, FiniteDuration) => Unit = (_, _) => ()

  override def requeueWith(f: (ServiceRef, FiniteDuration) => Unit): Unit = later = f

  def reconcile(ref: ServiceRef): Unit =
    val resources =
      client.resources(classOf[AnkkaService]).inNamespace(ref.namespace).withName(ref.name)

    Option(resources.get()) match
      case None =>
        // Gone. Its children went with it, because they carry an owner reference — there is
        // no orphan to chase, which is the whole reason this design has no sweep.
        log.debug("{} no longer exists; nothing to do", ref)

      case Some(resource) if !supported(resource) =>
        val status = LifecycleRules.unsupportedVersion(
          Option(resource.getApiVersion).getOrElse("unknown"),
          AnkkaServiceDefinition.apiVersion
        )
        log.warn("{} has an unsupported apiVersion; refusing to act on a partial reading", ref)
        report(ref, resource, status)

      case Some(resource) => reconcileResource(ref, resource)

  private def supported(resource: AnkkaService): Boolean =
    Option(resource.getApiVersion).forall(_ == AnkkaServiceDefinition.apiVersion)

  private def reconcileResource(ref: ServiceRef, resource: AnkkaService): Unit =
    val spec      = Option(resource.getSpec).getOrElse(AnkkaServiceSpec())
    val namespace = Names.namespace(settings.namespacePrefix, spec.projectId)

    val databasePlan = decideDatabasePlan(ref, spec)
    val brokerSeen   = observeBroker(ref, spec)
    val brokerPlan   = BrokerProvisioning.decide(spec, settings.broker, brokerSeen)
    val cloudBucket  = decideCloudBucket(ref, resource, spec)
    val movePass     = decideMove(ref, resource, spec)
    val reported     = ObjectStorage.reported(resource)
    // The move's state as this pass leaves it, or as the last pass did once it is over.
    val moveStatus   = movePass.fold(reported.flatMap(_.move))(_.step.status)
    val storagePlan  = decideObjectStoragePlan(ref, spec, reported, cloudBucket.map(_.plans))
    val withheld     = ObjectStorage.withheld(storagePlan, spec, settings, reported)
    val refused      = ObjectStorage.refused(storagePlan, spec, settings, reported)
    val secretAccess = decideSecretAccess(ref, resource, spec, cloudBucket)
    val secretSyncs  = decideProjectSecrets(ref, spec)
    // A refusal says more than a wait, whichever of the two it comes from.
    val secretHold = (secretAccess.flatMap(_.hold).toVector ++ secretSyncs.holds).sortBy {
      case _: SecretAccess.Hold.Refused => 0
      case _                            => 1
    }.headOption
    def status(
        snapshot: Option[ClusterSnapshot],
        problems: Vector[String],
        resource: AnkkaService
    ) =
      val observed = this.status(
        spec,
        snapshot,
        problems,
        resource,
        databasePlan,
        brokerPlan,
        storagePlan,
        moveStatus
      )
      // A Deployment held back for a cloud bucket's answer, or for its access to its secrets, is an
      // update in progress, saying why; one held back for a bucket the provider refused is a
      // failure, whose reason the status's object storage carries, and access the provider refused
      // is a failure with its reason. A failure found by rendering or a foreign Deployment says
      // more and is kept.
      if problems.nonEmpty then observed
      else
        secretHold match
          case Some(SecretAccess.Hold.Refused(why)) =>
            observed.copy(lifecycle = "Failed", detail = Some(why))
          case waiting =>
            withheld match
              case Some(_) if refused.isDefined => observed.copy(lifecycle = "Failed")
              case held =>
                held
                  .orElse(waiting.collect { case SecretAccess.Hold.Waiting(why) => why })
                  .fold(observed)(why =>
                    observed.copy(lifecycle = "UpdateInProgress", detail = Some(why))
                  )

    Rendering.render(
      resource,
      settings,
      databasePlan,
      BrokerProvisioning.known(spec, settings.broker),
      storagePlan,
      executor.projectBrokers(ref.namespace, spec.projectId),
      // One identity per service: a bucket and the secret store ask for the same request.
      (cloudBucket.map(_.requests).getOrElse(Vector.empty) ++
        secretAccess.map(_.requests).getOrElse(Vector.empty)).distinctBy(_.getMetadata.getName),
      ServiceReconciler.serviceAccountAnnotations(cloudBucket, secretAccess),
      movePass.fold(Vector.empty)(m =>
        Rendering.moveActions(
          resource,
          spec,
          namespace,
          settings,
          m.generation,
          m.step.actions,
          m.target,
          m.bucket.requests,
          ObjectStorage.inPlace(resource)
        )
      ),
      secretHold
    ) match
      case Left(problems) =>
        // A resource that cannot be rendered leaves nothing half-applied. The status says
        // why, which is the only way an operator finds out.
        log.warn("{} cannot be rendered: {}", ref, problems.mkString("; "))
        report(ref, resource, status(None, problems, resource))

      case Right(actions) =>
        if executor.foreignObjectAt(namespace, spec.serviceName) then
          // Never adopt, never overwrite. A name collision with something ankka does not
          // own is reported and left strictly alone.
          val problem = Vector(
            s"a deployment named '${spec.serviceName}' already exists in '$namespace' and is " +
              "not managed by ankka"
          )
          report(ref, resource, status(None, problem, resource))
        else
          val transitioning =
            Transition.needed(executor.podTemplateLabels(namespace, spec.serviceName))
          def transitionStatus = status(None, Vector.empty, resource)
            .copy(lifecycle = "UpdateInProgress", detail = Some(Transition.Detail))
          val ready =
            if !transitioning then true
            else
              log.info("{} predates mutual TLS; stopping its instances before applying", ref)
              report(ref, resource, transitionStatus)
              executor.execute(
                Action.DeleteDeployment(namespace, Names.deployment(spec.serviceName))
              )
              executor.awaitNoPods(
                namespace,
                Labels.identity(spec.projectId, spec.serviceName),
                Transition.Wait
              )
          if !ready then
            // Never apply beside a live old pod: that is the split the transition exists to avoid.
            // The next reconcile finds no Deployment, so it applies straight away.
            log.warn(
              "{} still has instances after {}; retrying on the next pass",
              ref,
              Transition.Wait
            )
          else
            actions.foreach(executor.execute)
            // A request nobody has acknowledged yet: look again when the bound passes, so its
            // absence is reported then, not at the next resync (feature 044, SC-004).
            if cloudBucket.orElse(movePass.map(_.bucket)).exists(_.unacknowledged) ||
              secretAccess.exists(_.unacknowledged) || secretSyncs.unacknowledged
            then settings.cloud.foreach(cloud => later(ref, cloud.acknowledgementBound))
            val observed = status(snapshotOf(namespace, spec), Vector.empty, resource)
            report(
              ref,
              resource,
              if transitioning then observed.copy(detail = Some(Transition.Detail)) else observed
            )

  /**
   * Reads what the cluster has for this service's database and decides what to do about it — once
   * per reconcile pass, ahead of rendering, so `Rendering.render` sees an already-decided plan
   * rather than reading the cluster itself.
   */
  private def decideDatabasePlan(ref: ServiceRef, spec: AnkkaServiceSpec): ProvisioningPlan =
    val observed =
      // A web-hosted service has no database to observe: no CNPG read is made for it at all.
      if spec.provisionDatabase && spec.hosting != Rendering.WebHosting && spec.database != "none"
      then
        executor
          .observeDatabase(ref.namespace, CnpgRendering.projectClusterName, ref.name)
          .copy(resourceCreatedAt = executor.resourceCreatedAt(ref.namespace, ref.name))
      else com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation.empty
    Provisioning.decide(spec, observed)

  /**
   * Asks the store about this service's bucket, only when the service asks for one and the
   * installation has a store, and decides (feature 034).
   */
  private def decideObjectStoragePlan(
      ref: ServiceRef,
      spec: AnkkaServiceSpec,
      reported: Option[com.thinkmorestupidless.ankka.crd.ObjectStorageStatus],
      cloud: Option[CloudBucketPlans]
  ): ObjectStoragePlan =
    val observed =
      if ObjectStorage.observes(spec, settings) then
        executor
          .observeObjectStorage(Buckets.name(spec.projectId, spec.serviceName))
          .copy(resourceCreatedAt = executor.resourceCreatedAt(ref.namespace, ref.name))
      else ObjectStorageObservation.empty
    ObjectStorage.decide(spec, settings, observed, reported, cloud)

  /**
   * A bucket in the installation's cloud account (feature 044): the requests it takes, what the
   * provider has answered of each, and whether any is still unacknowledged. Read only for a service
   * on that path; every other service makes no read of a cloud request at all.
   */
  private[operator] def decideCloudBucket(
      ref: ServiceRef,
      resource: AnkkaService,
      spec: AnkkaServiceSpec
  ): Option[ServiceReconciler.CloudBucketPass] =
    for
      cloud <- settings.cloud
      if ObjectStorage.takesCloudPath(spec, settings, ObjectStorage.reported(resource))
    yield cloudBucketPass(ref, resource, spec, cloud)

  /**
   * A move of the service's bucket from Garage (feature 039), one transition per pass: the target's
   * requests and what they answered, the mover's Job for the phase in hand, and the step. None when
   * no move was asked for, and once it has switched, after which the bucket is the cloud's and
   * `decideCloudBucket` takes it.
   */
  private[operator] def decideMove(
      ref: ServiceRef,
      resource: AnkkaService,
      spec: AnkkaServiceSpec
  ): Option[ServiceReconciler.MovePass] =
    val current = ObjectStorage.reported(resource).flatMap(_.move)
    for
      cloud   <- settings.cloud
      request <- spec.objectStorageMove
      if !current.exists(_.state == StorageMove.State.Switched.toString)
    yield
      val pass               = cloudBucketPass(ref, resource, spec, cloud)
      val (requests, target) = ObjectStorage.moveRequests(pass.plans)
      val phase = current.map(_.state) match
        case Some(s) if s == StorageMove.State.Copying.toString   => Some(MovePhase.Copy)
        case Some(s) if s == StorageMove.State.Verifying.toString => Some(MovePhase.Verify)
        case _                                                    => None
      val job = phase.fold(StorageMove.JobOutcome.Absent)(p =>
        executor.observeMoveJob(
          ref.namespace,
          Names.moveJob(spec.serviceName, request.generation, p)
        )
      )
      val step = StorageMove.next(
        Some(request),
        current,
        StorageMove.MoveObservation(requests, job, Instant.now(clock))
      )
      ServiceReconciler.MovePass(pass, step, target, request.generation)

  private def cloudBucketPass(
      ref: ServiceRef,
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      cloud: CloudSettings
  ): ServiceReconciler.CloudBucketPass =
    val now = Instant.now(clock)
    // The bucket's request as it stands, read once: what it asked is kept (feature 039).
    val bucketName =
      Names.CloudRequest.ofService(spec.serviceName, Names.CloudRequest.BucketSuffix)
    val existingBucket = executor.observeCloudResource(ref.namespace, bucketName)
    def answered(request: com.thinkmorestupidless.ankka.crd.CloudResource) =
      val name = request.getMetadata.getName
      val seen =
        if name == bucketName then existingBucket
        else executor.observeCloudResource(ref.namespace, name)
      seen -> CloudProvisioning.decide(request, seen, now, cloud.acknowledgementBound)
    def output(plan: CloudPlan, key: String) = plan match
      case CloudPlan.Ready(outputs, _, _, _) => outputs.get(key)
      case _                                 => None
    val projectLocation = executor.projectLocation(ref.namespace, spec.projectId)
    val first =
      ObjectStorage.cloudRequests(
        resource,
        settings,
        cloud,
        projectLocation,
        existingBucket,
        None,
        None
      )
    val Vector(idSeen -> idPlan, bucketSeen -> bucketPlan) = first.map(answered): @unchecked
    val requests = ObjectStorage.cloudRequests(
      resource,
      settings,
      cloud,
      projectLocation,
      existingBucket,
      output(idPlan, CloudRequests.Keys.Identity),
      output(bucketPlan, CloudRequests.Keys.Bucket)
    )
    val credential = requests.drop(2).headOption.map(answered)
    ServiceReconciler.CloudBucketPass(
      requests = requests,
      plans = CloudBucketPlans(idPlan, bucketPlan, credential.map(_._2)),
      unacknowledged =
        (Vector(idSeen, bucketSeen) ++ credential.map(_._1)).exists(!_.exists(_.acknowledged))
    )

  /**
   * A service's access to its secrets in the installation's cloud account (feature 038): the
   * identity request, unless a cloud bucket's pass already renders and observes it, and the access
   * request once the identity is known. Read only for a service on that path.
   */
  private[operator] def decideSecretAccess(
      ref: ServiceRef,
      resource: AnkkaService,
      spec: AnkkaServiceSpec,
      bucket: Option[ServiceReconciler.CloudBucketPass]
  ): Option[ServiceReconciler.SecretAccessPass] =
    for cloud <- settings.cloud if SecretAccess.takesCloudPath(spec, settings)
    yield
      val now = Instant.now(clock)
      def answered(request: com.thinkmorestupidless.ankka.crd.CloudResource) =
        val seen = executor.observeCloudResource(ref.namespace, request.getMetadata.getName)
        seen -> CloudProvisioning.decide(request, seen, now, cloud.acknowledgementBound)
      val identityRequest = SecretAccess.identityRequest(resource, cloud)
      val (identitySeen, identityPlan) = bucket match
        case Some(pass) => (Some(true), pass.plans.identity)
        case None =>
          val (seen, plan) = answered(identityRequest)
          (seen.map(_.acknowledged), plan)
      val identity = identityPlan match
        case CloudPlan.Ready(outputs, _, _, _) => outputs.get(CloudRequests.Keys.Identity)
        case _                                 => None
      val access     = identity.map(SecretAccess.accessRequest(resource, cloud, _))
      val accessSeen = access.map(answered)
      ServiceReconciler.SecretAccessPass(
        requests = Vector(identityRequest) ++ access,
        identity = identityPlan,
        hold = SecretAccess.hold(identityPlan, accessSeen.map(_._2)),
        unacknowledged = !identitySeen.contains(true) ||
          accessSeen.exists((seen, _) => !seen.exists(_.acknowledged))
      )

  /**
   * The project secrets this service takes a variable from, and whether the cloud provider has each
   * in step (feature 038). Read only when project secrets are kept in the cloud account, and only
   * for the secrets the descriptor names: a service that names none waits on none.
   */
  private[operator] def decideProjectSecrets(
      ref: ServiceRef,
      spec: AnkkaServiceSpec
  ): ServiceReconciler.ProjectSecretsPass =
    settings.cloud.filter(_ => ProjectSecretSync.takesCloudPath(settings)) match
      case None => ServiceReconciler.ProjectSecretsPass(Vector.empty, unacknowledged = false)
      case Some(cloud) =>
        val now = Instant.now(clock)
        val seen = ProjectSecretSync
          .referenced(spec, executor.projectSecrets(ref.namespace, spec.projectId))
          .map { secret =>
            val observed = executor.observeCloudResource(
              ref.namespace,
              ProjectSecretSync.requestName(spec.projectId, secret.name)
            )
            val plan = CloudProvisioning.decide(
              ProjectSecretSync.provider(cloud),
              observed,
              now,
              cloud.acknowledgementBound
            )
            (ProjectSecretSync.hold(secret, plan), observed.exists(_.acknowledged))
          }
        ServiceReconciler.ProjectSecretsPass(
          holds = seen.flatMap(_._1),
          unacknowledged = seen.exists(!_._2)
        )

  /**
   * What the broker has for this service (feature 027), read only when the service is known to an
   * installation's broker: no Strimzi read is made for any other.
   */
  private def observeBroker(ref: ServiceRef, spec: AnkkaServiceSpec): BrokerObservation =
    settings.broker.filter(_ => BrokerProvisioning.known(spec, settings.broker)) match
      case None => BrokerObservation.empty
      case Some(broker) =>
        executor
          .observeBroker(broker.namespace, BrokerNames.user(spec.projectId, spec.serviceName))
          .copy(resourceCreatedAt = executor.resourceCreatedAt(ref.namespace, ref.name))

  /** Pods are read only when the Deployment is not fully ready — explaining costs an API call. */
  private def snapshotOf(namespace: String, spec: AnkkaServiceSpec): Option[ClusterSnapshot] =
    executor.snapshot(namespace, spec.serviceName).map { snapshot =>
      if snapshot.readyReplicas >= snapshot.specReplicas && !snapshot.progressDeadlineExceeded then
        snapshot
      else
        snapshot
          .copy(podProblems = executor.podProblems(namespace, spec.serviceName, spec.projectId))
    }

  private def status(
      spec: AnkkaServiceSpec,
      snapshot: Option[ClusterSnapshot],
      problems: Vector[String],
      resource: AnkkaService,
      databasePlan: ProvisioningPlan,
      brokerPlan: BrokerPlan,
      objectStoragePlan: ObjectStoragePlan,
      move: Option[com.thinkmorestupidless.ankka.crd.MoveStatus]
  ) =
    val base = LifecycleRules.observe(
      spec,
      snapshot,
      problems,
      metadataGeneration = Option(resource.getMetadata)
        .flatMap(m => Option(m.getGeneration))
        .map(_.longValue)
        .getOrElse(0L),
      now = Instant.now(clock)
    )
    val namespace = Names.namespace(settings.namespacePrefix, spec.projectId)
    base.copy(
      database = LifecycleRules.databaseStatus(
        databasePlan,
        CnpgRendering.projectClusterName,
        spec.serviceName
      ),
      broker = LifecycleRules.brokerStatus(brokerPlan),
      objectStorage = ObjectStorage
        .status(
          objectStoragePlan,
          spec,
          settings,
          ObjectStorage.reported(resource),
          ObjectStorage.inPlace(resource)
        )
        .map(_.copy(move = move)),
      // A broker that failed says why where a member looks first, without changing the
      // service's lifecycle: a service whose credential cannot be had is still deployed.
      detail = base.detail.orElse(brokerPlan match
        case BrokerPlan.Failed(problems) => Some(s"broker: ${problems.mkString("; ")}")
        case _                           => None),
      // Only an exposed service has a route to report on; the field stays absent otherwise.
      route = Option.when(spec.exposed)(
        LifecycleRules.routeStatus(
          spec.exposed,
          settings.baseDomain,
          executor.observeRoute(namespace, Names.httpRoute(spec.serviceName))
        )
      )
    )

  /**
   * Writes the status, unless it says the same thing as the one already there.
   *
   * Without this check the resync rewrites every status on every pass — not a broken test, just a
   * permanent write load against the API server proportional to service count. The comparison
   * ignores the timestamp, which otherwise differs every time and makes the check useless.
   */
  private def report(
      ref: ServiceRef,
      resource: AnkkaService,
      next: com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus
  ): Unit =
    val current = Option(resource.getStatus)
    if current.exists(_.sameReport(next)) then log.debug("{} status unchanged; no write", ref)
    else executor.execute(Action.SetStatus(ref.namespace, ref.name, next))

object ServiceReconciler:

  /** What holds a service for the project secrets it takes variables from, one per secret. */
  final case class ProjectSecretsPass(holds: Vector[SecretAccess.Hold], unacknowledged: Boolean)

  /**
   * What binds the service's ServiceAccount to its cloud identity: the provider's answer to the one
   * identity request, which a bucket or the secret store asked for. A service on Secret Manager
   * with no bucket needs it as much as one with a bucket: without it, every read is refused.
   */
  def serviceAccountAnnotations(
      bucket: Option[CloudBucketPass],
      secretAccess: Option[SecretAccessPass]
  ): Map[String, String] =
    bucket
      .map(c => ObjectStorage.serviceAccountAnnotations(c.plans))
      .orElse(secretAccess.map(a => ObjectStorage.identityAnnotations(a.identity)))
      .getOrElse(Map.empty)

  /** One pass's view of a service's access to its secrets: two requests, and what holds it. */
  final case class SecretAccessPass(
      requests: Vector[com.thinkmorestupidless.ankka.crd.CloudResource],
      identity: CloudPlan,
      hold: Option[SecretAccess.Hold],
      unacknowledged: Boolean
  )

  /** One pass's view of a cloud bucket's three requests. */
  final case class CloudBucketPass(
      requests: Vector[com.thinkmorestupidless.ankka.crd.CloudResource],
      plans: CloudBucketPlans,
      unacknowledged: Boolean
  )

  /** One pass of a move (feature 039): its target's requests, the step, and the target as made. */
  final case class MovePass(
      bucket: CloudBucketPass,
      step: StorageMove.Step,
      target: Option[CloudBucket],
      generation: Int
  )
  def apply(client: KubernetesClient, settings: Settings): ServiceReconciler =
    new ServiceReconciler(
      client,
      settings,
      new Fabric8Executor(
        client,
        settings.otlpHeaders,
        settings.objectStore.map(store => GarageStore(store.adminUrl, store.adminToken)),
        settings.rotationGrace
      )
    )
