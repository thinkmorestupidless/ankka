package nakka.controlplane.api

import nakka.controlplane.api.Wire.given
import nakka.controlplane.application.{ProjectEntity, ServiceEntity, ServiceRows}
import nakka.controlplane.deploy.DeployConfig
import nakka.controlplane.domain.{ApplyService, ServiceKey}
import nakka.core.{CommandError, Done, EntityId, ErrorCode}
import nakka.crd.Hostnames
import nakka.http.*
import nakka.runtime.SqlSyntax.{jsonText, sql}

/**
 * Services, addressed by project and name.
 *
 * The path mirrors the entity key — `/services/{projectId}/{name}` is `projectId/name` — rather
 * than nesting under `/projects`. That is a routing constraint, not a preference: nakka matches an
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
    deploy: DeployConfig = DeployConfig.default
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
