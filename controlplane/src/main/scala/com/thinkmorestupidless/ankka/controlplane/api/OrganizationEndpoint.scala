package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  DeployTokenEntity,
  DeployTokenRows,
  OrganizationEntity,
  ProjectRows
}
import com.thinkmorestupidless.ankka.controlplane.auth.{
  Authorization,
  DeployTokenIndex,
  DeployTokens
}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  AddMember,
  ChangeRole,
  CreateForOwner,
  DeployToken,
  RecordDeployToken
}
import com.thinkmorestupidless.ankka.controlplane.tenancy.{OrganizationCreation, OrganizationPolicy}
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
    policy: OrganizationPolicy = OrganizationPolicy.default,
    protected val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /**
     * This node's deploy token index, so a revoke can evict write-through (feature 013).
     *
     * Optional because a control plane can be assembled without deploy tokens at all — several
     * suites do — and because the *only* thing lost without it is immediacy on this node: the
     * revocation still reaches every index through the journal.
     */
    tokenIndex: Option[DeployTokenIndex] = None
) extends HttpEndpoint("/organizations")
    with Attributing:

  private val projects = clients.viewClient.forView(ProjectRows)
  private val tokens   = clients.viewClient.forView(DeployTokenRows)
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

  /**
   * Anyone logged in may create one and becomes its first owner (feature 008) — unless the
   * installation's policy says organizations are the platform administrator's to create, in which
   * case everyone else is refused with the reason and, when the installation names one, where to
   * sign up (feature 011). A platform administrator may name the first owner, so a tenant is
   * provisioned in one request and is never, even briefly, the administrator's; that option from
   * anyone else is refused first, before the policy, so the answer does not depend on it.
   */
  postBody("/{organizationId}") { (organizationId: String, request: CreateOrganization) =>
    val admin = authz.isAdmin(principal)
    request.owner match
      case Some(_) if !admin =>
        throw CommandError(
          "platform administrator role required to name an owner",
          ErrorCode.Forbidden
        )
      case _ if policy.creation == OrganizationCreation.PlatformAdmin && !admin =>
        throw CommandError(policy.refusal, ErrorCode.Forbidden)
      case Some(owner) =>
        entity(organizationId)
          .call(OrganizationEntity.createForOwner)
          .withMetadata(authz.administrator(principal))
          .invoke(CreateForOwner(request.name, owner))
      case None =>
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

  // ── deploy tokens (feature 013) ───────────────────────────────────────────
  //
  // On this endpoint rather than one of their own because two endpoints cannot share a prefix —
  // `HttpServer.validate` refuses it, so that dispatch can never depend on registration order.
  //
  // Owner-only, every one of them. A deploy token is always a *member*, so a leaked CI credential
  // cannot mint itself a second token, list its siblings, or revoke the one that would stop it.

  get("/{organizationId}/tokens") { (organizationId: String) =>
    authz.requireOwner(principal, organizationId, write = false)
    tokens
      .where(jsonText("organizationId") ++ sql" = $organizationId")
      .sortBy(row => row.createdAt.map(_.toEpochMilli).getOrElse(0L))
      .reverse
      .map(row =>
        DeployTokenSummary(
          id = row.id,
          label = row.label,
          subject = row.subject,
          createdBy = row.createdBy,
          createdAt = row.createdAt,
          expiresAt = row.expiresAt,
          lastUsed = row.lastUsed
        )
      )
  }

  /**
   * Mints a token and makes it a member, in that order.
   *
   * Two commands, and the order is the safe one: a token that exists but is not yet a member
   * authorizes nothing anywhere, so a failure between them leaves something harmless that the owner
   * can see in the listing and revoke. The reverse order would briefly grant membership to a
   * subject no credential could yet be checked against.
   */
  postBody("/{organizationId}/tokens") { (organizationId: String, request: CreateDeployToken) =>
    val access   = authz.requireOwner(principal, organizationId, write = true)
    val problems = DeployTokenRules.problems(request.label, request.expiresIn)
    if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)

    val minted = DeployTokens.mint()
    // The server's clock decides when a lifetime ends; a client sending an instant would be
    // asserting its own. `0` is the deliberate "never".
    val expiresAt = request.expiresIn.getOrElse(DeployTokenRules.DefaultLifetime) match
      case 0       => None
      case seconds => Some(clock.instant().plusSeconds(seconds))

    val by = authz.metadata(access)
    token(minted.id)
      .call(DeployTokenEntity.createToken)
      .withMetadata(by)
      .invoke(
        RecordDeployToken(organizationId, request.label.trim, minted.digest, expiresAt)
      ): Done

    entity(organizationId)
      .call(OrganizationEntity.addMember)
      .withMetadata(by)
      .invoke(
        AddMember(
          DeployToken.subjectOf(minted.id),
          Role.Member,
          display = Some(request.label.trim)
        )
      ): Done

    // Usable on this node at once, rather than at the next read-refresh. Without it the obvious
    // script — create a token, then use it — fails against the very node that minted it.
    tokenIndex.foreach(
      _.admit(minted.id, minted.digest, organizationId, request.label.trim, expiresAt)
    )

    DeployTokenCreated(
      id = minted.id,
      label = request.label.trim,
      secret = minted.presented,
      subject = DeployToken.subjectOf(minted.id),
      expiresAt = expiresAt
    )
  }

  /**
   * Revokes, evicts, then unmembers.
   *
   * `evict` is why "revoke, then the next call fails" is true on the node a CLI is talking to: the
   * revocation reaches other nodes through the journal a refresh interval later, but this node
   * forgets it now. Each step refuses independently, so a partial failure still denies.
   */
  delete("/{organizationId}/tokens/{tokenId}") { (organizationId: String, tokenId: String) =>
    val access = authz.requireOwner(principal, organizationId, write = true)
    val detail = token(tokenId).call(DeployTokenEntity.get).invoke()
    // A token of another organization is not this organization's to revoke, and saying so would
    // disclose that the id exists at all.
    if detail.organizationId != organizationId then
      throw CommandError(s"no such deploy token '$tokenId'", ErrorCode.NotFound)

    token(tokenId)
      .call(DeployTokenEntity.revoke)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
    tokenIndex.foreach(_.evict(tokenId))

    val unmembered: Done =
      try
        entity(organizationId)
          .call(OrganizationEntity.removeMember)
          .withMetadata(authz.metadata(access))
          .invoke(detail.subject)
      catch
        // It was never added — the create failed between its two commands. The token is revoked,
        // which is what was asked for.
        case failure: CommandError if failure.code == ErrorCode.NotFound => Done
    unmembered
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

  private def token(tokenId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(tokenId))
