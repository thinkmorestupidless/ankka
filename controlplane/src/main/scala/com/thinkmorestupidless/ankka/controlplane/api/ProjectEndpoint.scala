package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  ProjectEntity,
  ProjectRows,
  ServiceRows
}
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.controlplane.deploy.RegistryWriter
import com.thinkmorestupidless.ankka.controlplane.domain.ConfigureRegistry
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

import scala.util.control.NonFatal

/**
 * Projects, optionally filtered by organization — the caller's organizations, that is: a project is
 * visible to the members of the organization that owns it and to nobody else (feature 008).
 */
final class ProjectEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    protected val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /**
     * Where a registry credential goes. `None` in a control plane with no cluster behind it, and
     * the registry routes then answer unavailable rather than recording a credential nothing holds.
     */
    registryWriter: Option[RegistryWriter] = None
) extends HttpEndpoint("/projects")
    with Attributing:

  private val projects = clients.viewClient.forView(ProjectRows)
  private val services = clients.viewClient.forView(ServiceRows)
  private val authz    = Authorization(clients, clock)

  /**
   * `GET /projects?organization=acme` — the filter is optional, and one outside the caller's
   * organizations yields an empty list rather than a refusal, for the reason a read of one does.
   */
  get("/") { () =>
    val mine = authz.visible(principal).map(_.id).toSet
    val condition = request.query.optional[String]("organization") match
      case Some(organizationId) => jsonText("organizationId") ++ sql" = $organizationId"
      case None                 => SqlFragment.empty
    projects
      .ordered(condition, order = jsonText("name"))
      .filter(detail => mine.contains(detail.organizationId))
      .map(detail => ProjectSummary.of(detail, serviceCount(detail.id)))
  }

  get("/{projectId}") { (projectId: String) =>
    authz.project(principal, projectId, write = false)
    val detail = entity(projectId).call(ProjectEntity.get).invoke()
    ProjectSummary.of(detail, serviceCount(detail.id))
  }

  /**
   * Creates a project, in an organization the caller is a member of.
   *
   * Checked here rather than in the entity: a project entity cannot see an organization, and a
   * command handler that called out to one would be making a check it could not hold — the
   * organization could be deleted a moment later either way. Doing it at the edge catches the typo,
   * which is what this check is actually for — and an organization the caller cannot see answers
   * exactly as one that does not exist.
   */
  postBody("/{projectId}") { (projectId: String, request: CreateProject) =>
    // The id becomes part of a Kubernetes namespace name, so one that cannot be expressed
    // there has to be refused when the project is created rather than when its first
    // service silently fails to deploy. Same rule the CLI applies before the round trip.
    val idProblems = ProjectId.problems(projectId)
    if idProblems.nonEmpty then throw CommandError(idProblems.mkString("; "), ErrorCode.BadRequest)
    val access = authz.requireMember(principal, request.organizationId, write = true)
    entity(projectId)
      .call(ProjectEntity.createProject)
      .withMetadata(authz.metadata(access))
      .invoke(request): Done
  }

  putBody("/{projectId}/name") { (projectId: String, request: Rename) =>
    val access = authz.project(principal, projectId, write = true)
    entity(projectId)
      .call(ProjectEntity.rename)
      .withMetadata(authz.metadata(access))
      .invoke(request.name)
  }

  delete("/{projectId}") { (projectId: String) =>
    val access    = authz.project(principal, projectId, write = true)
    val remaining = serviceCount(projectId)
    if remaining > 0 then
      throw CommandError(
        s"project '$projectId' still has $remaining service(s)",
        ErrorCode.Conflict
      )
    entity(projectId).call(ProjectEntity.delete).withMetadata(authz.metadata(access)).invoke(): Done
  }

  /**
   * Registers a credential the cluster will pull this project's private images with.
   *
   * The cluster is written **first**, and a failure there is the end of it: the journal never
   * claims a credential the cluster does not hold, which is the one ordering that cannot leave a
   * service pointing at a Secret that does not exist. The password goes no further than the Secret
   * — not into the event, not into the reply, not into the listing.
   *
   * `write = true` membership, not ownership: a pipeline that pushes images to a registry is the
   * natural thing to register it, and a deploy token is a member.
   */
  putBody("/{projectId}/registry") { (projectId: String, request: SetRegistry) =>
    val access   = authz.project(principal, projectId, write = true)
    val problems = Registries.problems(request.server, request.username, request.password)
    if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)

    val writer = registryWriter.getOrElse(
      throw CommandError("this control plane cannot reach a cluster", ErrorCode.Unavailable)
    )
    try writer.writePullSecret(projectId, request.server, request.username, request.password)
    catch
      case error: CommandError => throw error
      case NonFatal(error) =>
        throw CommandError(
          s"could not write the registry credential: ${error.getMessage}",
          ErrorCode.Unavailable
        )

    entity(projectId)
      .call(ProjectEntity.configureRegistry)
      .withMetadata(authz.metadata(access))
      .invoke(ConfigureRegistry(request.server, request.username, Registries.SecretName)): Done
  }

  /**
   * Stops claiming a registry. The Secret stays where it is — the control plane holds no `delete`
   * on secrets, and one nothing names is inert. The next projection of each service in the project
   * drops the reference; `services restart` is what makes a running service notice.
   */
  delete("/{projectId}/registry") { (projectId: String) =>
    val access = authz.project(principal, projectId, write = true)
    entity(projectId)
      .call(ProjectEntity.clearRegistry)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
  }

  private def serviceCount(projectId: String): Int =
    services.count(jsonText("projectId") ++ sql" = $projectId").toInt

  private def entity(projectId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(projectId))
