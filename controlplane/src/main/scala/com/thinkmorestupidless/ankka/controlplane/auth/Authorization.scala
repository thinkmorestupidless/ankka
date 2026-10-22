package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.controlplane.api.{ControlPlaneAcl, Role}
import com.thinkmorestupidless.ankka.controlplane.application.{OrganizationEntity, ProjectEntity}
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution}
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.http.{EndpointClients, Principal}

import java.time.Clock

/** A caller's standing in one organization, resolved and sufficient for what they asked. */
final case class Authorized(actor: Actor, role: Role, organizationId: String, disabled: Boolean)

/**
 * Who may do what, answered from the entities — never from a view (research R8).
 *
 * A project or service route resolves project → organization → the caller's role with two entity
 * calls, both in memory once the shard is warm, so removal from an organization is visible on the
 * very next request (FR-020). Non-membership on a read is the *same* answer as a missing id
 * (FR-022): an outsider learns nothing about which ids exist. A platform administrator passes every
 * check, and the attribution records that it was the role and not membership that let them through
 * (FR-012).
 */
final class Authorization(clients: EndpointClients, clock: Clock):

  private val claims = InvitationClaim(clients, clock)

  def isAdmin(principal: Principal): Boolean = Principals.isPlatformAdmin(principal)

  /**
   * Anyone logged in. What an organization's creator needs. A deploy token scoped to one project
   * creates nothing above its project, so it is refused here too.
   */
  def anyone(principal: Principal): Metadata =
    scopedTo(principal).foreach { scope =>
      throw CommandError(
        s"a deploy token scoped to project '$scope' may not create an organization",
        ErrorCode.Forbidden
      )
    }
    attribution(principal, administrative = false)

  /**
   * A platform administrator acting on nothing in particular yet — creating an organization for
   * someone else (feature 011). The attribution says it was the role that let them, so the event
   * records an administrative actor rather than a member.
   */
  def administrator(principal: Principal): Metadata =
    if !isAdmin(principal) then
      throw CommandError("platform administrator role required", ErrorCode.Forbidden)
    attribution(principal, administrative = true)

  /**
   * The one project a deploy token is scoped to (spec 057), from the claim its principal carries;
   * `None` for every other principal. Only a deploy token's claim is read: a person's or a
   * machine's token from an issuer never narrows, and never widens, through this.
   */
  def scopedTo(principal: Principal): Option[String] =
    if principal.claims.get("kind").contains("deploy-token") then
      principal.claims.get(ControlPlaneAcl.ProjectClaim)
    else None

  /**
   * A route on the organization itself. A deploy token scoped to one project acts on nothing above
   * its project, so it is refused here whatever the route asks, as beyond what it may do.
   */
  def requireMember(principal: Principal, organizationId: String, write: Boolean): Authorized =
    refuseScoped(principal, organizationId)
    require(principal, organizationId, Role.Member, write)

  def requireOwner(principal: Principal, organizationId: String, write: Boolean): Authorized =
    refuseScoped(principal, organizationId)
    require(principal, organizationId, Role.Owner, write)

  /** Platform administrators only. The organization need not be visible to them as a member. */
  def requireAdmin(principal: Principal, organizationId: String): Authorized =
    if !isAdmin(principal) then
      throw CommandError("platform administrator role required", ErrorCode.Forbidden)
    val answer =
      organization(organizationId).call(OrganizationEntity.roleOf).invoke(principal.subject)
    if !answer.exists then throw notFound(organizationId)
    Authorized(actor(principal, administrative = true), Role.Owner, organizationId, answer.disabled)

  /**
   * A project's organization, or the project's own not-found if it has none. A non-member of the
   * organization sees the *project* as missing — the organization's existence is not theirs to
   * learn either.
   */
  def project(principal: Principal, projectId: String, write: Boolean): Authorized =
    outsideScope(principal, projectId)
    val organizationId = organizationOf(projectId).getOrElse(throw noSuchProject(projectId))
    try require(principal, organizationId, Role.Member, write)
    catch
      case failure: CommandError if failure.code == ErrorCode.NotFound =>
        throw noSuchProject(projectId)

  /**
   * As `project`, for what only an owner of the project's organization may do: a restore and a
   * switch (feature 041), and grant, withdraw and revoke (feature 040). A member who is not an
   * owner is refused as member management refuses one; a non-member sees the project as missing.
   */
  def projectOwner(principal: Principal, projectId: String, write: Boolean = true): Authorized =
    outsideScope(principal, projectId)
    val organizationId = organizationOf(projectId).getOrElse(throw noSuchProject(projectId))
    try require(principal, organizationId, Role.Owner, write)
    catch
      case failure: CommandError if failure.code == ErrorCode.NotFound =>
        throw noSuchProject(projectId)

  /**
   * Making and ending a project's own grants (spec 057): an owner of the project's organization, as
   * `projectOwner`, or a deploy token scoped to this project, which a module's agent needs to open
   * what the module declares. The answers on the grantee's side stay an owner's.
   */
  def projectGrants(principal: Principal, projectId: String): Authorized =
    if scopedTo(principal).contains(projectId) then project(principal, projectId, write = true)
    else projectOwner(principal, projectId, write = true)

  /** A scoped token asking for another project is told it does not exist, as a stranger is. */
  private def outsideScope(principal: Principal, projectId: String): Unit =
    if scopedTo(principal).exists(_ != projectId) then throw noSuchProject(projectId)

  private def refuseScoped(principal: Principal, organizationId: String): Unit =
    scopedTo(principal).foreach { scope =>
      throw CommandError(
        s"a deploy token scoped to project '$scope' may not act on organization '$organizationId'",
        ErrorCode.Forbidden
      )
    }

  def organizationOf(projectId: String): Option[String] =
    try
      Some(
        clients.componentClient
          .forEventSourcedEntity(EntityId(projectId))
          .call(ProjectEntity.get)
          .invoke()
          .organizationId
      )
    catch case failure: CommandError if failure.code == ErrorCode.NotFound => None

  /**
   * The command metadata for an authorized action: who, when, and whether it was administrative.
   */
  def metadata(authorized: Authorized): Metadata =
    Attribution(authorized.actor, clock.instant()).metadata

  private def require(
      principal: Principal,
      organizationId: String,
      needed: Role,
      write: Boolean
  ): Authorized =
    val answer =
      organization(organizationId).call(OrganizationEntity.roleOf).invoke(principal.subject)
    if !answer.exists then throw notFound(organizationId)
    val role       = answer.role.orElse(claims.claimIfPending(principal, organizationId))
    val sufficient = role.exists(r => r == Role.Owner || needed == Role.Member)
    val admin      = isAdmin(principal)
    if !sufficient && !admin then
      role match
        case None => throw notFound(organizationId)
        case Some(_) =>
          throw CommandError(
            s"owner role required in organization '$organizationId'",
            ErrorCode.Forbidden
          )
    if write && answer.disabled then
      throw CommandError(s"organization '$organizationId' is disabled", ErrorCode.Conflict)
    Authorized(
      actor(principal, administrative = admin && !sufficient),
      role.getOrElse(Role.Owner),
      organizationId,
      answer.disabled
    )

  private def actor(principal: Principal, administrative: Boolean): Actor =
    Actor(principal.subject, Some(Principals.display(principal)), administrative)

  private def attribution(principal: Principal, administrative: Boolean): Metadata =
    Attribution(actor(principal, administrative), clock.instant()).metadata

  private def organization(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))

  // The exact message the entity gives for an id that was never used: the two must be indistinguishable.
  private def notFound(organizationId: String) =
    CommandError(s"no such organization '$organizationId'", ErrorCode.NotFound)

  private def noSuchProject(projectId: String) =
    CommandError(s"no such project '$projectId'", ErrorCode.NotFound)

  /** Organizations the caller may see, from the view — for listings only, never enforcement. */
  def visible(
      principal: Principal
  ): Vector[com.thinkmorestupidless.ankka.controlplane.application.OrganizationRow] =
    claims.claimAll(principal)
    val rows = clients.viewClient.forView(
      com.thinkmorestupidless.ankka.controlplane.application.OrganizationRows
    )
    if isAdmin(principal) then
      rows.ordered(
        com.thinkmorestupidless.ankka.runtime.SqlFragment.empty,
        order = com.thinkmorestupidless.ankka.runtime.SqlSyntax.jsonText("name")
      )
    else
      rows.ordered(
        com.thinkmorestupidless.ankka.runtime.SqlSyntax.jsonContains("members", principal.subject),
        order = com.thinkmorestupidless.ankka.runtime.SqlSyntax.jsonText("name")
      )
