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

    Rendering.render(resource, settings, databasePlan, Passwords.generate()) match
      case Left(problems) =>
        // A resource that cannot be rendered leaves nothing half-applied. The status says
        // why, which is the only way an operator finds out.
        log.warn("{} cannot be rendered: {}", ref, problems.mkString("; "))
        report(ref, resource, status(spec, None, problems, resource, databasePlan))

      case Right(actions) =>
        if executor.foreignObjectAt(namespace, spec.serviceName) then
          // Never adopt, never overwrite. A name collision with something ankka does not
          // own is reported and left strictly alone.
          val problem = Vector(
            s"a deployment named '${spec.serviceName}' already exists in '$namespace' and is " +
              "not managed by ankka"
          )
          report(ref, resource, status(spec, None, problem, resource, databasePlan))
        else
          actions.foreach(executor.execute)
          report(
            ref,
            resource,
            status(spec, snapshotOf(namespace, spec), Vector.empty, resource, databasePlan)
          )

  /**
   * Reads what the cluster has for this service's database and decides what to do about it — once
   * per reconcile pass, ahead of rendering, so `Rendering.render` sees an already-decided plan
   * rather than reading the cluster itself.
   */
  private def decideDatabasePlan(ref: ServiceRef, spec: AnkkaServiceSpec): ProvisioningPlan =
    val observed =
      if spec.provisionDatabase then
        executor
          .observeDatabase(ref.namespace, CnpgRendering.projectClusterName, ref.name)
          .copy(resourceCreatedAt = executor.resourceCreatedAt(ref.namespace, ref.name))
      else com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation.empty
    Provisioning.decide(spec, observed)

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
      databasePlan: ProvisioningPlan
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
      database = Some(
        LifecycleRules.databaseStatus(
          databasePlan,
          CnpgRendering.projectClusterName,
          spec.serviceName
        )
      ),
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
    new ServiceReconciler(client, settings, new Fabric8Executor(client))
