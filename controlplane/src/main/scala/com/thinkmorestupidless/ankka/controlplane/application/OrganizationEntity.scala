package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * An organization, event sourced.
 *
 * Tenancy is event sourced for one reason: an operator asking "who changed this, and to what" is
 * the normal case in a control plane, and a journal answers it without a separate audit trail to
 * keep in step. Since feature 008 that includes who belongs: members, pending invitations and the
 * disabled flag are folded from events here, and every rule an organization can enforce about
 * itself — the last owner stays, a disabled organization refuses changes, an invitation is claimed
 * only by a verified email — lives in these handlers.
 *
 * What it does *not* decide is who may issue a command. The caller's role is the endpoint's
 * question, answered through `roleOf`; the entity is told the actor and records it.
 */
final class OrganizationEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Organization, OrganizationEvent]:

  def emptyState: Organization = Organization.empty(context.entityId)

  def applyEvent(event: OrganizationEvent): Organization = event match
    case OrganizationCreated(name, creator, at) => currentState.onCreated(name, creator, at)
    case OrganizationRenamed(name, _, _)        => currentState.onRenamed(name)
    case _: OrganizationDeleted                 => currentState.onDeleted
    case MemberInvited(email, role, actor, at)  => currentState.onInvited(email, role, actor, at)
    case InvitationRevoked(email, _, _)         => currentState.onInvitationRevoked(email)
    case InvitationClaimed(email, subject, display, _, at) =>
      currentState.onClaimed(email, subject, display, at)
    case MemberAdded(subject, role, email, display, actor, at) =>
      currentState.onMemberAdded(subject, role, email, display, actor, at)
    case MemberRemoved(subject, _, _)           => currentState.onMemberRemoved(subject)
    case MemberRoleChanged(subject, role, _, _) => currentState.onRoleChanged(subject, role)
    case _: OrganizationDisabled                => currentState.onDisabled
    case _: OrganizationEnabled                 => currentState.onEnabled

  // ── lifecycle ─────────────────────────────────────────────────────────────

  def create(name: String): Effect[Done] =
    if currentState.deleted then
      effects.error(
        s"organization '${context.entityId}' was deleted; its id is not reused",
        ErrorCode.Conflict
      )
    else if currentState.known then
      effects.error(s"organization '${context.entityId}' already exists", ErrorCode.Conflict)
    else if name.isEmpty then effects.error("organization name must not be empty")
    else effects.persist(OrganizationCreated(name, actor, at)).thenReply(_ => Done)

  def rename(name: String): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.disabled then disabled
    else if name.isEmpty then effects.error("organization name must not be empty")
    else effects.persist(OrganizationRenamed(name, actor, at)).thenReply(_ => Done)

  /**
   * Marks the organization deleted without removing the entity.
   *
   * The journal is the audit trail, so deletion is a tombstone rather than a `deleteEntity()`. It
   * also keeps the id from being silently reused by a later create, which for a tenancy boundary
   * would be the wrong default. Allowed while disabled: an administrator shutting a tenant down may
   * well be on the way to removing it.
   */
  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(OrganizationDeleted(actor, at)).thenReply(_ => Done)

  // ── membership ────────────────────────────────────────────────────────────

  def invite(request: Invite): Effect[Done] =
    val email = Organization.key(request.email)
    if !currentState.exists then notFound
    else if currentState.disabled then disabled
    else if email.isEmpty || !email.contains('@') then
      effects.error(s"'${request.email}' is not an email address")
    else if currentState.members.values.exists(_.email.contains(email)) then
      effects.error(s"'$email' is already a member of '${context.entityId}'", ErrorCode.Conflict)
    else if currentState.invitations.contains(email) then
      effects.error(s"'$email' is already invited to '${context.entityId}'", ErrorCode.Conflict)
    else effects.persist(MemberInvited(email, request.role, actor, at)).thenReply(_ => Done)

  def revokeInvitation(email: String): Effect[Done] =
    val key = Organization.key(email)
    if !currentState.exists then notFound
    else if currentState.disabled then disabled
    else if !currentState.invitations.contains(key) then
      effects.error(s"no invitation for '$key' in '${context.entityId}'", ErrorCode.NotFound)
    else effects.persist(InvitationRevoked(key, actor, at)).thenReply(_ => Done)

  /**
   * Spends a pending invitation on the subject presenting its email. The endpoint has already
   * checked the email is *verified* (FR-017); an unverified one never reaches here. The role
   * claimed is the reply, so the caller can proceed with it in the same request.
   */
  def claimInvitation(claim: ClaimInvitation): Effect[Option[Role]] =
    val key = Organization.key(claim.email)
    if !currentState.exists || currentState.disabled then effects.reply(None)
    else
      currentState.invitations.get(key) match
        case None => effects.reply(None)
        case Some(invitation) =>
          effects
            .persist(InvitationClaimed(key, claim.subject, claim.display, actor, at))
            .thenReply(_ => Some(invitation.role))

  /**
   * A platform administrator adding a member directly. Allowed while disabled — it is the repair
   * path.
   */
  def addMember(request: AddMember): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.members.contains(request.subject) then
      effects.error(
        s"'${request.subject}' is already a member of '${context.entityId}'",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(
          MemberAdded(request.subject, request.role, request.email, request.display, actor, at)
        )
        .thenReply(_ => Done)

  def removeMember(subject: String): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.disabled then disabled
    else if !currentState.members.contains(subject) then
      effects.error(s"'$subject' is not a member of '${context.entityId}'", ErrorCode.NotFound)
    else if currentState.isLastOwner(subject) then lastOwner
    else effects.persist(MemberRemoved(subject, actor, at)).thenReply(_ => Done)

  def changeRole(request: ChangeRole): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.disabled then disabled
    else if !currentState.members.contains(request.subject) then
      effects.error(
        s"'${request.subject}' is not a member of '${context.entityId}'",
        ErrorCode.NotFound
      )
    else if currentState.roleOf(request.subject).contains(request.role) then effects.reply(Done)
    else if request.role == Role.Member && currentState.isLastOwner(request.subject) then lastOwner
    else
      effects
        .persist(MemberRoleChanged(request.subject, request.role, actor, at))
        .thenReply(_ => Done)

  // ── disabling ─────────────────────────────────────────────────────────────

  def disable: Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.disabled then
      effects.error(s"organization '${context.entityId}' is already disabled", ErrorCode.Conflict)
    else effects.persist(OrganizationDisabled(actor, at)).thenReply(_ => Done)

  def enable: Effect[Done] =
    if !currentState.exists then notFound
    else if !currentState.disabled then
      effects.error(s"organization '${context.entityId}' is not disabled", ErrorCode.Conflict)
    else effects.persist(OrganizationEnabled(actor, at)).thenReply(_ => Done)

  // ── queries ───────────────────────────────────────────────────────────────

  def get: ReadOnlyEffect[OrganizationDetail] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(OrganizationDetail(currentState.id, currentState.name, currentState.disabled))

  def exists: ReadOnlyEffect[Boolean] = effects.reply(currentState.exists)

  /** The one call an endpoint needs to authorize a request against this organization. */
  def roleOf(subject: String): ReadOnlyEffect[MembershipAnswer] =
    effects.reply(
      MembershipAnswer(currentState.roleOf(subject), currentState.exists, currentState.disabled)
    )

  def pendingFor(email: String): ReadOnlyEffect[Option[Role]] =
    effects.reply(
      Option
        .when(currentState.exists && !currentState.disabled)(
          currentState.pendingFor(email).map(_.role)
        )
        .flatten
    )

  def members: ReadOnlyEffect[MembersResponse] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        MembersResponse(
          currentState.members.toVector
            .sortBy((subject, m) => (Role.name(m.role), m.display.getOrElse(subject)))
            .map((subject, m) =>
              MemberSummary(subject, m.role, m.email, m.display, m.since, m.addedBy)
            ),
          currentState.invitations.toVector
            .sortBy(_._1)
            .map((email, i) => InvitationSummary(email, i.role, i.invitedAt, i.invitedBy))
        )
      )

  // ── plumbing ──────────────────────────────────────────────────────────────

  // Who is asking, from the command's metadata (see Attribution); absent for an unattributed call.
  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  private def at: Option[java.time.Instant]    = attribution.map(_.at)

  private def notFoundMessage = s"no such organization '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)
  private def disabled =
    effects.error(s"organization '${context.entityId}' is disabled", ErrorCode.Conflict)
  private def lastOwner =
    effects.error(s"'${context.entityId}' would be left with no owner", ErrorCode.Conflict)

object OrganizationEntity
    extends EventSourcedEntity.Companion[OrganizationEntity, Organization, OrganizationEvent](
      componentId = ComponentId("organization"),
      stateSerializer = Codecs.serializer[Organization]("organization"),
      eventSerializer = Codecs.serializer[OrganizationEvent]("organization-event")
    ):

  given Serializer[OrganizationDetail] =
    Codecs.serializer[OrganizationDetail]("organization-detail")
  given Serializer[Invite]           = Codecs.serializer[Invite]("invite")
  given Serializer[ClaimInvitation]  = Codecs.serializer[ClaimInvitation]("claim-invitation")
  given Serializer[AddMember]        = Codecs.serializer[AddMember]("add-member")
  given Serializer[ChangeRole]       = Codecs.serializer[ChangeRole]("change-role")
  given Serializer[MembershipAnswer] = Codecs.serializer[MembershipAnswer]("membership-answer")
  given Serializer[Option[Role]]     = Codecs.serializer[Option[Role]]("role-option")
  given Serializer[MembersResponse]  = Codecs.serializer[MembersResponse]("members")

  def create(context: EventSourcedEntityContext) = new OrganizationEntity(context)

  val createOrganization = command("create")(_.create)
  val rename             = command("rename")(_.rename)
  val delete             = command("delete")(_.delete)
  val invite             = command("invite")(_.invite)
  val revokeInvitation   = command("revoke-invitation")(_.revokeInvitation)
  val claimInvitation    = command("claim-invitation")(_.claimInvitation)
  val addMember          = command("add-member")(_.addMember)
  val removeMember       = command("remove-member")(_.removeMember)
  val changeRole         = command("change-role")(_.changeRole)
  val disable            = command("disable")(_.disable)
  val enable             = command("enable")(_.enable)
  val get                = query("get")(_.get)
  val exists             = query("exists")(_.exists)
  val roleOf             = query("role-of")(_.roleOf)
  val pendingFor         = query("pending-for")(_.pendingFor)
  val members            = query("members")(_.members)
