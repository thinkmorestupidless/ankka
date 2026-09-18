package nakka.controlplane.deploy

import nakka.controlplane.api.{InstanceType, ProjectId}
import nakka.controlplane.domain.Service
import nakka.crd.{AutoscalingSpec, EnvEntry, NakkaServiceSpec}

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

  def project(service: Service, config: DeployConfig): Either[Vector[String], NakkaServiceSpec] =
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
            descriptor.problems

        if problems.nonEmpty then Left(problems)
        else
          val resources = descriptor.service.resources
          // `problems` above already rejected an unknown instance type, so the lookup
          // cannot miss — but defaulting rather than throwing keeps this total.
          val instance = InstanceType.byName(resources.instanceType).getOrElse(InstanceType.Small)

          Right(
            NakkaServiceSpec(
              projectId = service.projectId,
              serviceName = service.name,
              generation = service.generation,
              paused = service.isPaused,
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
              // already lives here (research R11): a caller who names their own NAKKA_DB_*
              // variable is bringing their own database, so nothing should be provisioned for
              // it — checked by name, not value, so a secretKeyRef-sourced value still counts.
              provisionDatabase = !descriptor.service.env.exists(_.name.startsWith("NAKKA_DB_")),
              // Resolved here, once. The operator never sees `http` or the descriptor's `port`,
              // only the answer — the same split `instanceType` → cpu/memory already uses.
              port = descriptor.service.resolvedPort,
              restarts = service.restarts
            )
          )
