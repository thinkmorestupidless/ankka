package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceDefinition, AnkkaServiceSpec}
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Clock, Instant}

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
    def status(
        snapshot: Option[ClusterSnapshot],
        problems: Vector[String],
        resource: AnkkaService
    ) = this.status(spec, snapshot, problems, resource, databasePlan, brokerPlan)

    Rendering.render(
      resource,
      settings,
      databasePlan,
      BrokerProvisioning.topicsToRender(spec, settings.broker, brokerSeen)
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
      if spec.provisionDatabase && spec.hosting != Rendering.WebHosting then
        executor
          .observeDatabase(ref.namespace, CnpgRendering.projectClusterName, ref.name)
          .copy(resourceCreatedAt = executor.resourceCreatedAt(ref.namespace, ref.name))
      else com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation.empty
    Provisioning.decide(spec, observed)

  /**
   * What the broker has for this service (feature 027), read only when the service is known to an
   * installation's broker: no Strimzi read is made for any other.
   */
  private def observeBroker(ref: ServiceRef, spec: AnkkaServiceSpec): BrokerObservation =
    settings.broker.filter(_ => BrokerProvisioning.known(spec, settings.broker)) match
      case None => BrokerObservation.empty
      case Some(broker) =>
        executor
          .observeBroker(
            broker.namespace,
            BrokerNames.user(spec.projectId, spec.serviceName),
            spec.topics.toVector.map(t => BrokerNames.topic(spec.projectId, t.name))
          )
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
      brokerPlan: BrokerPlan
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
      broker = LifecycleRules.brokerStatus(brokerPlan, spec),
      // A broker that failed says why where a member looks first, without changing the
      // service's lifecycle: a service whose topics cannot be had is still deployed.
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
  def apply(client: KubernetesClient, settings: Settings): ServiceReconciler =
    new ServiceReconciler(client, settings, new Fabric8Executor(client, settings.otlpHeaders))
