package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{OrganizationDetail, Role}
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, OrganizationEvent}
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One row per organization, for listings — and, since feature 008, for *scoping* them: the row
 * carries the subjects of its members and owners and the emails it has invited, so
 * `GET /organizations` can ask the view "which organizations is this caller in" with one
 * containment query, and never has to open an entity to draw a list.
 *
 * A view lags; that is fine for a listing and never for enforcement, which reads the entity
 * (FR-021). The row holds subject ids only, never a role's worth of trust: whether the caller *may*
 * do something is always the entity's answer.
 *
 * `disabledBy` and `disabledAt` are the actor and time of the most recent disable or enable. The
 * projector's sweep reads them so that a suspension it performs is attributed to the administrator
 * who disabled the organization, exactly as the trigger attributes one — which path got there first
 * is a race, and the history must not depend on it. A row written before these fields existed
 * decodes with `None`, and the sweep then attributes to the platform.
 */
final case class OrganizationRow(
    id: String,
    name: String,
    disabled: Boolean = false,
    members: Vector[String] = Vector.empty,
    owners: Vector[String] = Vector.empty,
    invitations: Vector[String] = Vector.empty,
    disabledBy: Option[Actor] = None,
    disabledAt: Option[Instant] = None
):
  def detail: OrganizationDetail = OrganizationDetail(id, name, disabled)
  def roleOf(subject: String): Option[Role] =
    if owners.contains(subject) then Some(Role.Owner)
    else if members.contains(subject) then Some(Role.Member)
    else None

final class OrganizationRowsView extends View[OrganizationEvent, OrganizationRow]:

  private def row = rowState.getOrElse(OrganizationRow(updateContext.subject, ""))

  private def withMember(subject: String, role: Role): OrganizationRow =
    val r = row
    r.copy(
      members = (r.members :+ subject).distinct,
      owners =
        if role == Role.Owner then (r.owners :+ subject).distinct
        else r.owners.filterNot(_ == subject)
    )

  def onChange(event: OrganizationEvent): Effect = event match
    case OrganizationCreated(name, creator, _) =>
      val created = row.copy(name = name)
      effects.updateRow(
        creator.fold(created)(a =>
          created.copy(members = Vector(a.subject), owners = Vector(a.subject))
        )
      )
    case OrganizationRenamed(name, _, _) => effects.updateRow(row.copy(name = name))
    case _: OrganizationDeleted          => effects.deleteRow()
    case MemberInvited(email, _, _, _) =>
      effects.updateRow(row.copy(invitations = (row.invitations :+ email).distinct))
    case InvitationRevoked(email, _, _) =>
      effects.updateRow(row.copy(invitations = row.invitations.filterNot(_ == email)))
    case InvitationClaimed(email, subject, _, _, _) =>
      // The role is on the entity; the row learns it from the invitation it saw invited. Owner
      // invitations are rare, and a claim of one is followed by the entity's own answer anyway.
      effects.updateRow(
        row.copy(
          invitations = row.invitations.filterNot(_ == email),
          members = (row.members :+ subject).distinct
        )
      )
    case MemberAdded(subject, role, _, _, _, _) => effects.updateRow(withMember(subject, role))
    case MemberRemoved(subject, _, _) =>
      effects.updateRow(
        row.copy(
          members = row.members.filterNot(_ == subject),
          owners = row.owners.filterNot(_ == subject)
        )
      )
    case MemberRoleChanged(subject, role, _, _) => effects.updateRow(withMember(subject, role))
    case OrganizationDisabled(actor, at) =>
      effects.updateRow(row.copy(disabled = true, disabledBy = actor, disabledAt = at))
    case OrganizationEnabled(actor, at) =>
      effects.updateRow(row.copy(disabled = false, disabledBy = actor, disabledAt = at))

object OrganizationRows
    extends View.Companion[OrganizationRowsView, OrganizationEvent, OrganizationRow](
      componentId = ComponentId("organization-rows"),
      source = ChangeSource.eventsOf(OrganizationEntity),
      rowSerializer = Codecs.serializer[OrganizationRow]("organization-row")
    ):
  def create(ctx: ViewComponentContext) = new OrganizationRowsView
