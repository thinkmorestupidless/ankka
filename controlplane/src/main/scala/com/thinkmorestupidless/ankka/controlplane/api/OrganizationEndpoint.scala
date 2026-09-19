package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  OrganizationEntity,
  OrganizationRows,
  ProjectRows
}
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

/**
 * Organizations: create, list, rename, delete.
 *
 * Writes go to the entity, which is the source of truth; the list comes from a view, because "every
 * organization" is a question no single entity can answer. That split is visible in the consistency
 * too — a create is immediately readable by id, while it reaches the list only once the projection
 * has caught up.
 */
final class OrganizationEndpoint(clients: EndpointClients, val acl: Acl)
    extends HttpEndpoint("/organizations"):

  private val organizations = clients.viewClient.forView(OrganizationRows)
  private val projects      = clients.viewClient.forView(ProjectRows)

  get("/") { () =>
    organizations
      .ordered(SqlFragment.empty, order = jsonText("name"))
      .map(detail => OrganizationSummary.of(detail, projectCount(detail.id)))
  }

  get("/{organizationId}") { (organizationId: String) =>
    val detail = entity(organizationId).call(OrganizationEntity.get).invoke()
    OrganizationSummary.of(detail, projectCount(detail.id))
  }

  postBody("/{organizationId}") { (organizationId: String, request: CreateOrganization) =>
    entity(organizationId).call(OrganizationEntity.createOrganization).invoke(request.name)
  }

  putBody("/{organizationId}/name") { (organizationId: String, request: Rename) =>
    entity(organizationId).call(OrganizationEntity.rename).invoke(request.name)
  }

  /**
   * Refuses to delete an organization that still has projects.
   *
   * The entity cannot enforce this — it cannot see its projects — so the check lives here. It is a
   * guard against the obvious mistake rather than a guarantee: the count comes from a projection,
   * so a project created moments ago might not be counted yet.
   */
  delete("/{organizationId}") { (organizationId: String) =>
    val remaining = projectCount(organizationId)
    if remaining > 0 then
      throw CommandError(
        s"organization '$organizationId' still has $remaining project(s)",
        ErrorCode.Conflict
      )
    entity(organizationId).call(OrganizationEntity.delete).invoke(): Done
  }

  private def projectCount(organizationId: String): Int =
    projects.count(jsonText("organizationId") ++ sql" = $organizationId").toInt

  private def entity(organizationId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(organizationId))
