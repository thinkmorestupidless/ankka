package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{OrganizationEntity, ProjectRows}
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.controlplane.domain.{AddMember, ChangeRole}
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

/**
 * Organizations: create, list, rename, delete — and, since feature 008, who belongs to them.
 *
 * Writes go to the entity, which is the source of truth; the list comes from a view, because "every
 * organization" is a question no single entity can answer. That split is visible in the consistency
 * too — a create is immediately readable by id, while it reaches the list only once the projection
 * has caught up.
 *
 * Who may do what is `Authorization`'s answer, from the entity, on every route: a listing shows the
 * caller's organizations, a read of one they are not in answers exactly as for one that never
 * existed, and every write is stamped with who asked.
 */
final class OrganizationEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    protected val clock: java.time.Clock = java.time.Clock.systemUTC()
) extends HttpEndpoint("/organizations")
    with Attributing:

  private val projects = clients.viewClient.forView(ProjectRows)
  private val authz    = Authorization(clients, clock)

  get("/") { () =>
    authz
      .visible(principal)
      .map(row =>
        OrganizationSummary.of(row.detail, projectCount(row.id), row.roleOf(principal.subject))
      )
  }

  get("/{organizationId}") { (organizationId: String) =>
    val access = authz.requireMember(principal, organizationId, write = false)
    val detail = entity(organizationId).call(OrganizationEntity.get).invoke()
    OrganizationSummary.of(
      detail,
      projectCount(detail.id),
      Option.when(!access.actor.administrative)(access.role)
    )
  }

  /** Anyone logged in may create one; they become its first owner (FR-013). */
  postBody("/{organizationId}") { (organizationId: String, request: CreateOrganization) =>
    entity(organizationId)
      .call(OrganizationEntity.createOrganization)
      .withMetadata(authz.anyone(principal))
      .invoke(request.name)
  }

  putBody("/{organizationId}/name") { (organizationId: String, request: Rename) =>
    val access = authz.requireOwner(principal, organizationId, write = true)
    entity(organizationId)
      .call(OrganizationEntity.rename)
      .withMetadata(authz.metadata(access))
      .invoke(request.name)
  }

  /**
   * Refuses to delete an organization that still has projects.
   *
   * The entity cannot enforce this — it cannot see its projects — so the check lives here. It is a
   * guard against the obvious mistake rather than a guarantee: the count comes from a projection,
   * so a project created moments ago might not be counted yet. Allowed while disabled: the entity
   * decides that, and an administrator shutting a tenant down may be removing it.
   */
  delete("/{organizationId}") { (organizationId: String) =>
    val access    = authz.requireOwner(principal, organizationId, write = false)
    val remaining = projectCount(organizationId)
    if remaining > 0 then
      throw CommandError(
        s"organization '$organizationId' still has $remaining project(s)",
        ErrorCode.Conflict
      )
    entity(organizationId)
      .call(OrganizationEntity.delete)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
  }

  // ── members ───────────────────────────────────────────────────────────────

  get("/{organizationId}/members") { (organizationId: String) =>
    authz.requireMember(principal, organizationId, write = false)
    entity(organizationId).call(OrganizationEntity.members).invoke()
  }

  postBody("/{organizationId}/members") { (organizationId: String, request: Invite) =>
    val access = authz.requireOwner(principal, organizationId, write = true)
    entity(organizationId)
      .call(OrganizationEntity.invite)
      .withMetadata(authz.metadata(access))
      .invoke(request): Done
  }

  delete("/{organizationId}/members/{subject}") { (organizationId: String, subject: String) =>
    val access = authz.requireOwner(principal, organizationId, write = true)
    entity(organizationId)
      .call(OrganizationEntity.removeMember)
      .withMetadata(authz.metadata(access))
      .invoke(subject): Done
  }

  putBody("/{organizationId}/members/{subject}/role") {
    (organizationId: String, subject: String, request: RoleChange) =>
      val access = authz.requireOwner(principal, organizationId, write = true)
      entity(organizationId)
        .call(OrganizationEntity.changeRole)
        .withMetadata(authz.metadata(access))
        .invoke(ChangeRole(subject, request.role)): Done
  }

  delete("/{organizationId}/invitations/{email}") { (organizationId: String, email: String) =>
    val access = authz.requireOwner(principal, organizationId, write = true)
    entity(organizationId)
      .call(OrganizationEntity.revokeInvitation)
      .withMetadata(authz.metadata(access))
      .invoke(email): Done
  }

  /** The administrative repair path: an owner for an organization whose owners have all left. */
  postBody("/{organizationId}/members/{subject}/repair") {
    (organizationId: String, subject: String, request: Repair) =>
      val access = authz.requireAdmin(principal, organizationId)
      entity(organizationId)
        .call(OrganizationEntity.addMember)
        .withMetadata(authz.metadata(access))
        .invoke(AddMember(subject, request.role)): Done
  }

  // ── disabling ─────────────────────────────────────────────────────────────

  post("/{organizationId}/disable") { (organizationId: String) =>
    val access = authz.requireAdmin(principal, organizationId)
    entity(organizationId)
      .call(OrganizationEntity.disable)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
  }

  post("/{organizationId}/enable") { (organizationId: String) =>
    val access = authz.requireAdmin(principal, organizationId)
    entity(organizationId)
      .call(OrganizationEntity.enable)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
  }

  private def projectCount(organizationId: String): Int =
    projects.count(jsonText("organizationId") ++ sql" = $organizationId").toInt

  private def entity(organizationId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(organizationId))
