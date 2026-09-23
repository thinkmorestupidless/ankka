package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.{
  Compatibility,
  InstanceType,
  ProjectId,
  Protocol,
  ServiceDescriptor,
  Version
}
import com.thinkmorestupidless.ankka.controlplane.domain.Service
import com.thinkmorestupidless.ankka.crd.{AutoscalingSpec, EnvEntry, AnkkaServiceSpec}

/**
 * Desired state becomes a resource spec.
 *
 * Total and pure, reporting every problem at once in the style of `ServiceDescriptor.problems` — an
 * operator fixing a project should not have to fix it once per mistake.
 *
 * Note what crosses the boundary: the *resolved* CPU and memory, not the instance type name. The
 * named sizes are this module's vocabulary, defined in `controlplane-api` where the CLI can
 * validate them; the operator cannot see that module and must not need to. Adding a size later is
 * then a control plane change alone.
 */
object ServiceProjection:

  /**
   * A declared runtime outside the platform's supported range refuses the projection: the service
   * is reported `Unavailable` naming both versions and no resource is written, so no pod ever
   * starts against a schema it may not match. Undeclared means unchecked (feature 006).
   */
  private def runtimeProblems(descriptor: ServiceDescriptor, config: DeployConfig): Vector[String] =
    descriptor.service.declaredRuntime match
      case Some(Right(runtime)) =>
        Version.parse(config.platformVersion) match
          case Right(platform) if !Compatibility.supports(platform, runtime) =>
            Vector(
              s"runtime $runtime is outside the platform's supported range: " +
                Compatibility.describe(platform)
            )
          case _ => Vector.empty
      case _ => Vector.empty // absent, or malformed (already a descriptor problem)

  /** The same refusal for a declared protocol (feature 009), before any resource is written. */
  private def protocolProblems(descriptor: ServiceDescriptor): Vector[String] =
    descriptor.service.declaredProtocol match
      case Some(Right(declared)) if !Compatibility.supportsProtocol(Protocol.version, declared) =>
        Vector(
          s"protocol $declared is outside the platform's supported range: " +
            Compatibility.describeProtocol(Protocol.version)
        )
      case _ => Vector.empty

  def project(service: Service, config: DeployConfig): Either[Vector[String], AnkkaServiceSpec] =
    service.descriptor match
      case None =>
        Left(Vector(s"service '${service.name}' has no descriptor to project"))

      case Some(descriptor) =>
        val namespace = config.namespaceFor(service.projectId)

        val problems =
          ProjectId.problems(service.projectId) ++
            Option
              .when(namespace.length > 63)(
                s"namespace '$namespace' is ${namespace.length} characters, over the 63 limit; " +
                  "shorten the project id or the namespace prefix"
              )
              .toVector ++
            descriptor.problems ++
            runtimeProblems(descriptor, config) ++
            protocolProblems(descriptor)

        if problems.nonEmpty then Left(problems)
        else
          val resources = descriptor.service.resources
          // `problems` above already rejected an unknown instance type, so the lookup
          // cannot miss — but defaulting rather than throwing keeps this total.
          val instance = InstanceType.byName(resources.instanceType).getOrElse(InstanceType.Small)

          Right(
            AnkkaServiceSpec(
              projectId = service.projectId,
              serviceName = service.name,
              generation = service.generation,
              // Paused by its members or suspended by its organization (feature 008): the
              // operator scales to zero for either, and does not need to know which.
              paused = service.isPaused || service.suspended,
              image = descriptor.service.image,
              env = descriptor.service.env
                .map(entry =>
                  EnvEntry(
                    name = entry.name,
                    value = entry.value,
                    secretName = entry.secretKeyRef.map(_.name),
                    secretKey = entry.secretKeyRef.map(_.key)
                  )
                )
                .toList,
              labels = descriptor.service.labels,
              annotations = descriptor.service.annotations,
              instanceType = instance.name,
              cpuMillis = instance.cpuMillis,
              memoryMiB = instance.memoryMiB,
              autoscaling = AutoscalingSpec(
                minInstances = resources.autoscaling.minInstances,
                maxInstances = resources.autoscaling.maxInstances,
                targetCpuPercent = resources.autoscaling.targetCpuPercent
              ),
              progressDeadlineSeconds = config.progressDeadline.toSeconds.toInt,
              // The control plane's call, not the operator's, because descriptor validation
              // already lives here (research R11): a caller who names their own ANKKA_DB_*
              // variable is bringing their own database, so nothing should be provisioned for
              // it — checked by name, not value, so a secretKeyRef-sourced value still counts.
              provisionDatabase = !descriptor.service.env.exists(_.name.startsWith("ANKKA_DB_")),
              // Resolved here, once. The operator never sees `http` or the descriptor's `port`,
              // only the answer — the same split `instanceType` → cpu/memory already uses.
              port = descriptor.service.resolvedPort,
              restarts = service.restarts,
              exposed = service.exposed,
              hosting = descriptor.service.hosting
            )
          )
