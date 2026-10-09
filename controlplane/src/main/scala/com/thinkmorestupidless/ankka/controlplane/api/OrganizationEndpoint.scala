package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  DeployTokenEntity,
  DeployTokenRows,
  MachineEntity,
  MachineRows,
  OrganizationEntity,
  ProjectEntity,
  ProjectRows,
  ServiceEntity,
  ServiceRows
}
import com.thinkmorestupidless.ankka.controlplane.auth.{
  Authorization,
  DeployTokenIndex,
  DeployTokens,
  MachineSecrets,
  MachineSettings
}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  AddMember,
  ChangeRole,
  CreateForOwner,
  DeployToken,
  Machine,
  RecordDeployToken,
  RegisterMachine,
  SetMachineByteRates,
  ServiceKey,
  SetQuota
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
    tokenIndex: Option[DeployTokenIndex] = None,
    /** Where a machine's token comes from, and the limits on machines (feature 040). */
    machineSettings: MachineSettings = MachineSettings.local
) extends HttpEndpoint("/organizations")
    with Attributing:

  private val projects = clients.viewClient.forView(ProjectRows)
  private val services = clients.viewClient.forView(ServiceRows)
  private val tokens   = clients.viewClient.forView(DeployTokenRows)
  private val machines = clients.viewClient.forView(MachineRows)
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

  // ── grants received (feature 040) ─────────────────────────────────────────

  /**
   * What the organization's machines and the services of every project it owns hold, or are
   * offered, from other projects: each with every change to it, and a topic grant with its topic's
   * settings, read from the granting project. Members only.
   */
  get("/{organizationId}/grants") { (organizationId: String) =>
    authz.requireMember(principal, organizationId, write = false)
    ProjectEndpoint.receivedDetails(
      received(organizationId),
      granting =>
        clients.componentClient
          .forEventSourcedEntity(EntityId(granting))
          .call(ProjectEntity.topics)
          .invoke()
    )
  }

  /** Every grant the organization's machines and its projects' services hold or are offered. */
  private def received(
      organizationId: String
  ): Vector[com.thinkmorestupidless.ankka.controlplane.domain.ReceivedGrant] =
    val own = entity(organizationId).call(OrganizationEntity.receivedGrants).invoke()
    val ofProjects = projects
      .ordered(jsonText("organizationId") ++ sql" = $organizationId", order = jsonText("id"))
      .flatMap(row =>
        try
          clients.componentClient
            .forEventSourcedEntity(EntityId(row.id))
            .call(ProjectEntity.receivedGrants)
            .invoke()
        catch case _: CommandError => Vector.empty
      )
    (own ++ ofProjects).distinctBy(_.id)

  /**
   * Answers a grant offered to or held by the organization, from the grantee's side: only an owner
   * of the grantee organization may, and a grant the organization does not hold or was not offered
   * is not there for it — answered as one that does not exist, so its id discloses nothing.
   */
  private def answer(
      organizationId: String,
      grantId: String,
      handle: com.thinkmorestupidless.ankka.sdk.CommandHandle[ProjectEntity, String, Done]
  ): Done =
    val access = authz.requireOwner(principal, organizationId, write = true)
    val grant = received(organizationId)
      .find(_.id == grantId)
      .getOrElse(
        throw CommandError(
          s"no grant '$grantId' is offered to '$organizationId'",
          ErrorCode.NotFound
        )
      )
    clients.componentClient
      .forEventSourcedEntity(EntityId(grant.grantingProject))
      .call(handle)
      .withMetadata(authz.metadata(access))
      .invoke(grantId)

  post("/{organizationId}/grants/{grantId}/accept") { (organizationId: String, grantId: String) =>
    answer(organizationId, grantId, ProjectEntity.acceptGrant)
  }

  post("/{organizationId}/grants/{grantId}/decline") { (organizationId: String, grantId: String) =>
    answer(organizationId, grantId, ProjectEntity.declineGrant)
  }

  post("/{organizationId}/grants/{grantId}/relinquish") { (organizationId: String, grantId: String) =>
    answer(organizationId, grantId, ProjectEntity.relinquishGrant)
  }

  // ── machines (feature 040) ────────────────────────────────────────────────
  //
  // An owner registers, limits and deletes; a member lists; a deploy token, which is a member,
  // does none of the writes. No reply but the registration's carries the secret, and that reply is
  // the only time it exists outside the machine that holds it.

  get("/{organizationId}/machines") { (organizationId: String) =>
    authz.requireMember(principal, organizationId, write = false)
    machines
      .where(jsonText("organizationId") ++ sql" = $organizationId")
      .sortBy(_.name)
      .map(_.summary)
  }

  postBody("/{organizationId}/machines") { (organizationId: String, request: MachineRegistration) =>
    val access   = authz.requireOwner(principal, organizationId, write = true)
    val problems = Machines.nameProblems(request.name)
    if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
    val minted = MachineSecrets.mint()
    machine(organizationId, request.name)
      .call(MachineEntity.register)
      .withMetadata(authz.metadata(access))
      .invoke(RegisterMachine(organizationId, request.name, minted.digest)): MachineSummary
    MachineRegistered(
      name = request.name,
      clientId = Machines.clientId(organizationId, request.name),
      clientSecret = minted.secret,
      tokenUrl = machineSettings.tokenUrl,
      brokerBootstrap = machineSettings.brokerBootstrap
    )
  }

  delete("/{organizationId}/machines/{name}") { (organizationId: String, name: String) =>
    val access = authz.requireOwner(principal, organizationId, write = true)
    machine(organizationId, name)
      .call(MachineEntity.delete)
      .withMetadata(authz.metadata(access))
      .invoke()
  }

  putBody("/{organizationId}/machines/{name}/byte-rates") {
    (organizationId: String, name: String, request: ByteRatesRequest) =>
      val access   = authz.requireOwner(principal, organizationId, write = true)
      val problems = Machines.byteRateProblems(request, machineSettings.byteRateCeiling)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      machine(organizationId, name)
        .call(MachineEntity.setByteRates)
        .withMetadata(authz.metadata(access))
        .invoke(
          SetMachineByteRates(
            request.produceBytesPerSecond,
            request.consumeBytesPerSecond,
            request.requestPercentage
          )
        )
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

  // ── quotas (feature 015) ──────────────────────────────────────────────────

  /**
   * Sets the quota, whole, and makes the organization's usage what exists right now: an
   * organization created before quotas existed has never been told about its projects and services,
   * and its usage would otherwise read zero under a fresh quota — exactly when a wrong count
   * matters. Read from the views and each service's own entity; the entity replaces its record.
   * Never checked against usage: lowering a quota below what runs is accepted and refuses only what
   * is asked for next.
   */
  putBody("/{organizationId}/quota") { (organizationId: String, quota: Quota) =>
    val access   = authz.requireAdmin(principal, organizationId)
    val problems = Quota.problems(quota)
    if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
    val (projectIds, instances) = existing(organizationId)
    entity(organizationId)
      .call(OrganizationEntity.setQuota)
      .withMetadata(authz.metadata(access))
      .invoke(SetQuota(quota, projectIds, instances)): Done
  }

  delete("/{organizationId}/quota") { (organizationId: String) =>
    val access = authz.requireAdmin(principal, organizationId)
    entity(organizationId)
      .call(OrganizationEntity.clearQuota)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
  }

  /**
   * What the organization holds: its project ids from the listing, and for every service listed in
   * them the `minInstances` its own entity says it was applied with — the descriptor, not the
   * cluster's report, since the quota counts what was asked for.
   */
  private def existing(organizationId: String): (Set[String], Map[String, Int]) =
    val projectIds = projects
      .ordered(jsonText("organizationId") ++ sql" = $organizationId", order = jsonText("id"))
      .map(_.id)
    val instances = projectIds.flatMap { projectId =>
      services
        .ordered(jsonText("projectId") ++ sql" = $projectId", order = jsonText("name"))
        .flatMap { row =>
          val key = ServiceKey(projectId, row.name)
          clients.componentClient
            .forEventSourcedEntity(EntityId(key.id))
            .call(ServiceEntity.desiredState)
            .invoke()
            .flatMap(_.descriptor)
            .map(descriptor => key.id -> descriptor.service.resources.autoscaling.minInstances)
        }
    }
    (projectIds.toSet, instances.toMap)

  private def projectCount(organizationId: String): Int =
    projects.count(jsonText("organizationId") ++ sql" = $organizationId").toInt

  private def entity(organizationId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(organizationId))

  private def machine(organizationId: String, name: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(Machine.key(organizationId, name)))

  private def token(tokenId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(tokenId))
