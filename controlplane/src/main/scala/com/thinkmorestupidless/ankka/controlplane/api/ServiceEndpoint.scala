package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  ProjectEntity,
  ServiceEntity,
  ServiceRows
}
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, PodLogs}
import com.thinkmorestupidless.ankka.controlplane.domain.{ApplyService, ServiceKey}
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.crd.Hostnames
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

/**
 * Services, addressed by project and name.
 *
 * The path mirrors the entity key — `/services/{projectId}/{name}` is `projectId/name` — rather
 * than nesting under `/projects`. That is a routing constraint, not a preference: ankka matches an
 * endpoint by prefix, so a second endpoint under `/projects/...` would be shadowed by the one that
 * owns `/projects`.
 *
 * `PUT` is the apply: the request body is the whole desired state, and the response is the status
 * that resulted. Nothing here touches Kubernetes — an apply records intent and returns, and the
 * reconciler closes the gap afterwards.
 */
final class ServiceEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    deploy: DeployConfig = DeployConfig.default,
    logs: PodLogs = PodLogs(DeployConfig.default.namespacePrefix)
) extends HttpEndpoint("/services"):

  private val services = clients.viewClient.forView(ServiceRows)

  get("/{projectId}") { (projectId: String) =>
    services
      .ordered(
        jsonText("projectId") ++ sql" = $projectId",
        order = jsonText("name")
      )
      .map(withHostname)
  }

  get("/{projectId}/{name}") { (projectId: String, name: String) =>
    withHostname(entity(projectId, name).call(ServiceEntity.get).invoke())
  }

  putBody("/{projectId}/{name}") {
    (projectId: String, name: String, descriptor: ServiceDescriptor) =>
      requireProject(projectId)
      withHostname(
        entity(projectId, name)
          .call(ServiceEntity.applyDescriptor)
          .invoke(ApplyService(projectId, descriptor))
      )
  }

  post("/{projectId}/{name}/pause") { (projectId: String, name: String) =>
    withHostname(entity(projectId, name).call(ServiceEntity.pause).invoke())
  }

  post("/{projectId}/{name}/resume") { (projectId: String, name: String) =>
    withHostname(entity(projectId, name).call(ServiceEntity.resume).invoke())
  }

  post("/{projectId}/{name}/restart") { (projectId: String, name: String) =>
    withHostname(entity(projectId, name).call(ServiceEntity.restart).invoke())
  }

  /**
   * Exposure (feature 005). Whether the service *may* be exposed is decided here — no HTTP, a label
   * too long, a hostname another service holds, no base domain — because two of those are
   * cross-entity or platform-level, which an entity cannot see. The entity records the answer.
   */
  post("/{projectId}/{name}/expose") { (projectId: String, name: String) =>
    val current = entity(projectId, name)
      .call(ServiceEntity.desiredState)
      .invoke()
      .getOrElse(
        throw CommandError(s"no such service '$name' in project '$projectId'", ErrorCode.NotFound)
      )
    ExposureRules.refusal(current, deploy, hostnameHolder(current.key)).foreach { reason =>
      throw CommandError(reason, ErrorCode.Conflict)
    }
    withHostname(entity(projectId, name).call(ServiceEntity.expose).invoke())
  }

  post("/{projectId}/{name}/unexpose") { (projectId: String, name: String) =>
    withHostname(entity(projectId, name).call(ServiceEntity.unexpose).invoke())
  }

  /** The URL an exposed service answers at, added on the way out: the entity does not know it. */
  private def withHostname(status: ServiceStatus): ServiceStatus =
    if status.exposed then status.copy(hostname = deploy.hostnameFor(status.projectId, status.name))
    else status

  /**
   * Another exposed service whose derived label equals this one's — `a-b` in `c` against `a` in
   * `b-c`. From the listing view, so it can lag a moment behind a just-exposed service; the gateway
   * then accepts one route and rejects the other, visibly, which is the backstop.
   */
  private def hostnameHolder(key: ServiceKey): Option[ServiceKey] =
    val label = Hostnames.label(key.name, key.projectId)
    services
      .ordered(jsonText("exposed") ++ sql" = 'true'", order = jsonText("name"))
      .map(row => ServiceKey(row.projectId, row.name))
      .find(other => other != key && Hostnames.label(other.name, other.projectId) == label)

  /**
   * A deployed service's output.
   *
   * On `ServiceEndpoint` rather than an endpoint of its own so it is governed by exactly the same
   * acl and the same project scoping as every other service command. Logs are not a side channel
   * around who may see what: if a caller cannot `get` a service, it cannot read what that service
   * printed either.
   *
   * Reading is all this does. The control plane holds `get` on pods and pods/log and no mutating
   * verb at all — see controlplane-rbac.yaml for why that keeps the split intact.
   */
  get("/{projectId}/{name}/logs") { (projectId: String, name: String) =>
    // Confirms the service exists, and 404s with the same message `services get` gives when it
    // does not — one vocabulary, rather than a second way of saying the same thing.
    val _ = entity(projectId, name).call(ServiceEntity.get).invoke()

    // Read on the handler's own thread, which is where the request context lives.
    val instance = query.optional[String]("instance")
    val previous = query.flag("previous")
    val tail     = query.optional[Int]("tail")
    val since    = query.optional[Int]("since")

    val instances = instance.map(Vector(_)).getOrElse(logs.instances(projectId, name))

    if instances.isEmpty then
      // Never an empty success: "this service logged nothing" and "this service is not running"
      // are different facts, and a reader who cannot tell them apart will chase the wrong one.
      throw CommandError(
        s"service '$name' has no running instance; it may be paused",
        ErrorCode.NotFound
      )
    else
      LogsResponse(
        instances.map { pod =>
          logs.read(projectId, pod, tail, since, previous) match
            case Right(output) => InstanceLogs(pod, output, error = None)
            case Left(problem) => InstanceLogs(pod, "", error = Some(problem))
        }
      )
  }

  delete("/{projectId}/{name}") { (projectId: String, name: String) =>
    entity(projectId, name).call(ServiceEntity.delete).invoke(): Done
  }

  /**
   * Only checked on apply.
   *
   * An apply is the one operation that can bring a service into existence, so it is the only one
   * where a mistyped project id would silently create something unreachable. Every other route
   * addresses a service that already exists or returns 404 anyway.
   */
  private def requireProject(projectId: String): Unit =
    val known = clients.componentClient
      .forEventSourcedEntity(EntityId(projectId))
      .call(ProjectEntity.exists)
      .invoke()
    if !known then throw CommandError(s"no such project '$projectId'", ErrorCode.NotFound)

  private def entity(projectId: String, name: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(ServiceKey(projectId, name).id))
