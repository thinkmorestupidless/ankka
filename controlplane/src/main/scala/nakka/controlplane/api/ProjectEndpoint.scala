package nakka.controlplane.api

import nakka.controlplane.api.Wire.given
import nakka.controlplane.application.{OrganizationEntity, ProjectEntity, ProjectRows, ServiceRows}
import nakka.core.{CommandError, Done, EntityId, ErrorCode}
import nakka.http.*
import nakka.runtime.SqlFragment
import nakka.runtime.SqlSyntax.{jsonText, sql}

/** Projects, optionally filtered by organization. */
final class ProjectEndpoint(clients: EndpointClients, val acl: Acl)
    extends HttpEndpoint("/projects"):

  private val projects = clients.viewClient.forView(ProjectRows)
  private val services = clients.viewClient.forView(ServiceRows)

  /** `GET /projects?organization=acme` — the filter is optional. */
  get("/") { () =>
    val condition = request.query.optional[String]("organization") match
      case Some(organizationId) => jsonText("organizationId") ++ sql" = $organizationId"
      case None                 => SqlFragment.empty
    projects
      .ordered(condition, order = jsonText("name"))
      .map(detail => ProjectSummary.of(detail, serviceCount(detail.id)))
  }

  get("/{projectId}") { (projectId: String) =>
    val detail = entity(projectId).call(ProjectEntity.get).invoke()
    ProjectSummary.of(detail, serviceCount(detail.id))
  }

  /**
   * Creates a project, rejecting an unknown organization.
   *
   * Checked here rather than in the entity: a project entity cannot see an organization, and a
   * command handler that called out to one would be making a check it could not hold — the
   * organization could be deleted a moment later either way. Doing it at the edge catches the typo,
   * which is what this check is actually for.
   */
  postBody("/{projectId}") { (projectId: String, request: CreateProject) =>
    // The id becomes part of a Kubernetes namespace name, so one that cannot be expressed
    // there has to be refused when the project is created rather than when its first
    // service silently fails to deploy. Same rule the CLI applies before the round trip.
    val idProblems = ProjectId.problems(projectId)
    if idProblems.nonEmpty then throw CommandError(idProblems.mkString("; "), ErrorCode.BadRequest)
    val organization = clients.componentClient
      .forEventSourcedEntity(EntityId(request.organizationId))
      .call(OrganizationEntity.exists)
      .invoke()
    if !organization then
      throw CommandError(s"no such organization '${request.organizationId}'", ErrorCode.NotFound)
    entity(projectId).call(ProjectEntity.createProject).invoke(request): Done
  }

  putBody("/{projectId}/name") { (projectId: String, request: Rename) =>
    entity(projectId).call(ProjectEntity.rename).invoke(request.name)
  }

  delete("/{projectId}") { (projectId: String) =>
    val remaining = serviceCount(projectId)
    if remaining > 0 then
      throw CommandError(
        s"project '$projectId' still has $remaining service(s)",
        ErrorCode.Conflict
      )
    entity(projectId).call(ProjectEntity.delete).invoke(): Done
  }

  private def serviceCount(projectId: String): Int =
    services.count(jsonText("projectId") ++ sql" = $projectId").toInt

  private def entity(projectId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(projectId))
