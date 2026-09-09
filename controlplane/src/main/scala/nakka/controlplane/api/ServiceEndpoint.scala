package nakka.controlplane.api

import nakka.controlplane.api.Wire.given
import nakka.controlplane.application.{ProjectEntity, ServiceEntity, ServiceRows}
import nakka.controlplane.domain.{ApplyService, ServiceKey}
import nakka.core.{CommandError, Done, EntityId, ErrorCode}
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
final class ServiceEndpoint(clients: EndpointClients, val acl: Acl)
    extends HttpEndpoint("/services"):

  private val services = clients.viewClient.forView(ServiceRows)

  get("/{projectId}") { (projectId: String) =>
    services.ordered(
      jsonText("projectId") ++ sql" = $projectId",
      order = jsonText("name")
    )
  }

  get("/{projectId}/{name}") { (projectId: String, name: String) =>
    entity(projectId, name).call(ServiceEntity.get).invoke()
  }

  putBody("/{projectId}/{name}") {
    (projectId: String, name: String, descriptor: ServiceDescriptor) =>
      requireProject(projectId)
      entity(projectId, name)
        .call(ServiceEntity.applyDescriptor)
        .invoke(ApplyService(projectId, descriptor))
  }

  post("/{projectId}/{name}/pause") { (projectId: String, name: String) =>
    entity(projectId, name).call(ServiceEntity.pause).invoke()
  }

  post("/{projectId}/{name}/resume") { (projectId: String, name: String) =>
    entity(projectId, name).call(ServiceEntity.resume).invoke()
  }

  post("/{projectId}/{name}/restart") { (projectId: String, name: String) =>
    entity(projectId, name).call(ServiceEntity.restart).invoke()
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
