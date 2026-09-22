package com.thinkmorestupidless.ankka.controlplane.domain

import com.thinkmorestupidless.ankka.controlplane.api.*

import java.time.Instant

/**
 * A composite entity id.
 *
 * Service names are unique within a project, not globally, so the sharded entity is keyed by both.
 * The separator is `/` because it cannot appear in either half: project ids and service names are
 * both DNS labels.
 */
final case class ServiceKey(projectId: String, name: String):
  def id: String = s"$projectId/$name"

object ServiceKey:
  private val Separator = '/'

  def parse(id: String): Option[ServiceKey] =
    id.indexOf(Separator.toInt) match
      case -1 => None
      case at =>
        val projectId = id.substring(0, at)
        val name      = id.substring(at + 1)
        Option.when(projectId.nonEmpty && name.nonEmpty)(ServiceKey(projectId, name))

/**
 * Every command-produced event carries `actor` and `at` (feature 008): who asked, and when. Both
 * default to `None` so a journal written before this feature decodes — and reads as unattributed,
 * which is the truth about it (FR-024). Observations from the operator carry neither.
 */
enum OrganizationEvent:
  /** `actor`, when present, is the creator — and the first owner (feature 008, FR-013). */
  case OrganizationCreated(name: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case OrganizationRenamed(name: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case OrganizationDeleted(actor: Option[Actor] = None, at: Option[Instant] = None)

  // Membership (feature 008). Emails are stored as `Organization.key` writes them.
  case MemberInvited(
      email: String,
      role: Role,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case InvitationRevoked(email: String, actor: Option[Actor] = None, at: Option[Instant] = None)

  /** The claimant is the actor; `display` is what the members listing will show for them. */
  case InvitationClaimed(
      email: String,
      subject: String,
      display: Option[String] = None,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /**
   * A platform administrator adding a member directly — the repair path for an ownerless
   * organization.
   */
  case MemberAdded(
      subject: String,
      role: Role,
      email: Option[String] = None,
      display: Option[String] = None,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case MemberRemoved(subject: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case MemberRoleChanged(
      subject: String,
      role: Role,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  // Only a platform administrator; every service in the organization's projects is suspended.
  case OrganizationDisabled(actor: Option[Actor] = None, at: Option[Instant] = None)
  case OrganizationEnabled(actor: Option[Actor] = None, at: Option[Instant] = None)

/**
 * `OrganizationEntity.claimInvitation`: who is claiming, with the verified email they presented.
 */
final case class ClaimInvitation(subject: String, email: String, display: Option[String] = None)

/** `OrganizationEntity.addMember`: the administrative repair path. */
final case class AddMember(
    subject: String,
    role: Role,
    email: Option[String] = None,
    display: Option[String] = None
)

/** `OrganizationEntity.changeRole`. */
final case class ChangeRole(subject: String, role: Role)

/**
 * `OrganizationEntity.roleOf`, in one answer: the caller's role if any, whether the organization
 * exists at all, and whether it is disabled — everything an endpoint needs to authorize a request
 * in one entity call (research R8).
 */
final case class MembershipAnswer(
    role: Option[Role] = None,
    exists: Boolean = false,
    disabled: Boolean = false
)

enum ProjectEvent:
  case ProjectCreated(
      name: String,
      organizationId: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case ProjectRenamed(name: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case ProjectDeleted(actor: Option[Actor] = None, at: Option[Instant] = None)

enum ServiceEvent:
  /**
   * A descriptor was applied.
   *
   * Carries the generation it produced rather than letting the fold compute it. An event that
   * states its own generation can be read by a view or a consumer without replaying everything
   * before it.
   */
  case ServiceApplied(
      projectId: String,
      descriptor: ServiceDescriptor,
      generation: Long,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /** An operator asked for the running instances to be replaced. */
  case ServiceRestarted(generation: Long, actor: Option[Actor] = None, at: Option[Instant] = None)

  case ServicePaused(actor: Option[Actor] = None, at: Option[Instant] = None)
  case ServiceResumed(actor: Option[Actor] = None, at: Option[Instant] = None)

  /**
   * An operator asked for the service to answer outside the cluster, or to stop. Desired state
   * beside the descriptor, like pause: `apply` never touches it, and neither bumps the generation —
   * exposure is not a deployment.
   */
  case ServiceExposed(actor: Option[Actor] = None, at: Option[Instant] = None)
  case ServiceUnexposed(actor: Option[Actor] = None, at: Option[Instant] = None)

  /** What the reconciler saw. `generation` is the desired state being reported on. */
  case ServiceObserved(
      generation: Long,
      lifecycle: ServiceLifecycle,
      readyInstances: Int,
      desiredInstances: Int,
      detail: Option[String],
      /**
       * Whether the cluster was actually read.
       *
       * Defaulted so events already in a journal decode. `false` restates what was last known,
       * either because the cluster was unreachable or because nothing has reported on the service.
       */
      confirmed: Boolean = true,
      /**
       * The operator's reported database phase, verbatim — see
       * `com.thinkmorestupidless.ankka.crd.DatabaseStatus.phase`. `None` for the escape hatch and
       * for events already in a journal from before this field existed.
       */
      database: Option[String] = None
  )

  case ServiceDeleted(actor: Option[Actor] = None, at: Option[Instant] = None)

  /**
   * The service's organization was disabled, or enabled again (feature 008). Desired state owned by
   * the organization, kept apart from `ServicePaused`, which its members own: re-enabling restores
   * exactly what the members had chosen.
   */
  case ServiceSuspended(actor: Option[Actor] = None, at: Option[Instant] = None)
  case ServiceReinstated(actor: Option[Actor] = None, at: Option[Instant] = None)

/** What an operator submits to change a service. */
final case class ApplyService(projectId: String, descriptor: ServiceDescriptor)

/** What the reconciler reports back. */
final case class ServiceObservation(
    generation: Long,
    lifecycle: ServiceLifecycle,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String] = None,
    confirmed: Boolean = true,
    database: Option[String] = None
)
