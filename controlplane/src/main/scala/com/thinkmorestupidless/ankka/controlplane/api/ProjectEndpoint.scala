package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  ProjectEntity,
  ProjectRows,
  ServiceRows
}
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.controlplane.deploy.{ProjectSecretWriter, RegistryWriter}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  ConfigureRegistry,
  RemoveSecretEntry,
  SetSecretEntries
}
import com.thinkmorestupidless.ankka.controlplane.tenancy.OrganizationUsage
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
    registryWriter: Option[RegistryWriter] = None,
    /** Where a project secret's entries go; `None` as for the registry, with the same answer. */
    secretWriter: Option[ProjectSecretWriter] = None
) extends HttpEndpoint("/projects")
    with Attributing:

  private val projects = clients.viewClient.forView(ProjectRows)
  private val services = clients.viewClient.forView(ServiceRows)
  private val authz    = Authorization(clients, clock)
  private val usage    = OrganizationUsage(clients)

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
    val by     = authz.metadata(access)
    // The organization is asked first (feature 015): a refusal for quota creates nothing, and a
    // create that then fails gives the slot back — only if this request was the one that took it.
    val reserved = usage.reserveProject(request.organizationId, projectId, by)
    try entity(projectId).call(ProjectEntity.createProject).withMetadata(by).invoke(request): Done
    catch
      case NonFatal(failure) =>
        if reserved then
          usage.undo(s"release project '$projectId'") {
            usage.releaseProject(request.organizationId, projectId, by)
          }
        throw failure
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
    val by = authz.metadata(access)
    entity(projectId).call(ProjectEntity.delete).withMetadata(by).invoke(): Done
    usage.releaseProject(access.organizationId, projectId, by)
    Done: Done
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

  // ── Project secrets ────────────────────────────────────────────────────────

  private def writing(what: String)(write: ProjectSecretWriter => Unit): Unit =
    val writer = secretWriter.getOrElse(
      throw CommandError("this control plane cannot reach a cluster", ErrorCode.Unavailable)
    )
    try write(writer)
    catch
      case error: CommandError => throw error
      case NonFatal(error) =>
        throw CommandError(s"could not $what: ${error.getMessage}", ErrorCode.Unavailable)

  /**
   * Sets entries of a project secret, merged into what it holds, in the project's namespace.
   *
   * In the registry's order: who may, what is wrong (all of it, at once), the cluster, and only
   * then the record — so the journal never names an entry the cluster does not hold. The values go
   * no further than the Secret: the record, the reply and the listing carry names alone.
   */
  putBody("/{projectId}/secrets/{name}") {
    (projectId: String, name: String, request: SetProjectSecret) =>
      val access   = authz.project(principal, projectId, write = true)
      val problems = ProjectSecrets.problems(name, request.entries)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      writing(s"write project secret '$name'")(_.setEntries(projectId, name, request.entries))
      entity(projectId)
        .call(ProjectEntity.setSecretEntries)
        .withMetadata(authz.metadata(access))
        .invoke(SetSecretEntries(name, request.entries.keys.toVector)): Done
  }

  /**
   * Removes one entry, named by `?entry=`. One the record does not have is not found, and the
   * cluster is not touched. The Secret itself is never deleted: the grant has no `delete`, and one
   * with no entry is inert and no longer listed.
   */
  delete("/{projectId}/secrets/{name}") { (projectId: String, name: String) =>
    val access = authz.project(principal, projectId, write = true)
    val entry  = query.required[String]("entry")
    val known  = entity(projectId).call(ProjectEntity.secrets).invoke()
    if !known.exists(s => s.name == name && s.entries.contains(entry)) then
      throw CommandError(s"project secret '$name' has no entry '$entry'", ErrorCode.NotFound)
    writing(s"remove entry '$entry' of project secret '$name'")(
      _.removeEntry(projectId, name, entry)
    )
    entity(projectId)
      .call(ProjectEntity.removeSecretEntry)
      .withMetadata(authz.metadata(access))
      .invoke(RemoveSecretEntry(name, entry)): Done
  }

  /**
   * The project's secrets, by name, from the project's own record: names and entries, never a
   * value.
   */
  get("/{projectId}/secrets") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    entity(projectId).call(ProjectEntity.secrets).invoke()
  }

  private def serviceCount(projectId: String): Int =
    services.count(jsonText("projectId") ++ sql" = $projectId").toInt

  private def entity(projectId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(projectId))
