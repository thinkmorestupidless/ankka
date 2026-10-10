package com.thinkmorestupidless.ankka.controlplane.domain

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.core.Contract

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
  /**
   * `actor`, when present, is the creator — and the first owner (feature 008, FR-013), unless
   * `owner` names someone else: a platform administrator creating the organization *for* a subject
   * (feature 011). Then the actor is the administrator and the owner is the one member seated.
   * Absent on every event written before feature 011, which folds exactly as it did.
   */
  case OrganizationCreated(
      name: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None,
      owner: Option[Owner] = None
  )
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

  // Quotas (feature 015). The quota is the administrator's; the usage events are written on a
  // member's behalf by the endpoint that creates, applies or deletes — before the thing is created
  // (a reservation) and after it is gone. `UsageReconciled` replaces the record with what the
  // views say exists, written when a quota is set.
  case QuotaSet(quota: Quota, actor: Option[Actor] = None, at: Option[Instant] = None)
  case QuotaCleared(actor: Option[Actor] = None, at: Option[Instant] = None)
  case ProjectReserved(projectId: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case ProjectReleased(projectId: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case ServiceReserved(
      key: String,
      instances: Int,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case ServiceReleased(key: String, actor: Option[Actor] = None, at: Option[Instant] = None)
  case UsageReconciled(
      projects: Set[String],
      services: Map[String, Int],
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

/**
 * `OrganizationEntity.claimInvitation`: who is claiming, with the verified email they presented.
 */
final case class ClaimInvitation(subject: String, email: String, display: Option[String] = None)

/**
 * `OrganizationEntity.createForOwner`: a platform administrator creating an organization whose
 * first owner is `owner`, not themselves. A command of its own rather than a changed payload on
 * `create`, because a wire name is a versioning boundary: during a rolling update an in-flight
 * `create` must decode on either version.
 */
final case class CreateForOwner(name: String, owner: Owner)

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
 * `OrganizationEntity.setQuota` (feature 015): the quota, and a snapshot of what exists — project
 * ids and each service's `minInstances` by key — read by the endpoint from the views, so that an
 * organization created before quotas existed starts with a true record rather than an empty one.
 */
final case class SetQuota(quota: Quota, projects: Set[String], services: Map[String, Int])

/** `OrganizationEntity.reserveService`: a service about to be applied, with its `minInstances`. */
final case class ReserveService(key: String, instances: Int)

/**
 * `OrganizationEntity.recordService`: the unchecked write. `Some(n)` records the service at `n`
 * instances whatever the quota, `None` removes it. Used after a delete, and to put back what a
 * reservation replaced when the apply behind it failed.
 */
final case class RecordService(key: String, instances: Option[Int])

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

  /**
   * A registry credential was put in the cluster for this project.
   *
   * The password is deliberately absent: it was written to a Kubernetes Secret before this event
   * was persisted, and the journal's job is to remember that the credential exists, not to hold a
   * second copy of it. `secretName` is what the operator will name on every pod in the project.
   */
  case RegistryConfigured(
      server: String,
      username: String,
      secretName: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /** The project no longer claims a registry. The Secret itself is left in the cluster. */
  case RegistryCleared(actor: Option[Actor] = None, at: Option[Instant] = None)

  /**
   * Entries of a project secret were set in the cluster: `entries` names them, merged into what the
   * secret already had.
   *
   * No value is here, and there is no field that could hold one: the values were written to a
   * Kubernetes Secret in the project's namespace before this event was persisted, and the journal
   * remembers only that the entries exist. The project is the entity's own id.
   */
  case ProjectSecretEntriesSet(
      name: String,
      entries: Vector[String],
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /** One entry of a project secret was removed from the cluster. The Secret itself stays. */
  case ProjectSecretEntryRemoved(
      name: String,
      entry: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /**
   * A topic declared on the project, or its partitions raised (feature 027); its compaction and
   * contract since feature 037, defaulted so an older journal decodes.
   */
  case ProjectTopicDeclared(
      name: String,
      partitions: Int,
      actor: Option[Actor] = None,
      at: Option[Instant] = None,
      compacted: Boolean = false,
      contract: Option[Contract] = None
  )

  /** The project no longer declares a topic. The topic stays on the broker. */
  case ProjectTopicRemoved(
      name: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /** A broker declared on the project beside the installation's (feature 037). */
  case ProjectBrokerDeclared(
      name: String,
      bootstrap: String,
      shape: String,
      secretName: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /** The project no longer declares a broker; a service naming it is refused at its next start. */
  case ProjectBrokerRemoved(
      name: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /**
   * A member named where the project's new buckets in Google Cloud Storage are made, or took the
   * name back so the installation's default applies (`None`) (feature 039). A bucket's location is
   * fixed when it is made: this moves none.
   */
  case ProjectLocationSet(
      location: Option[String],
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

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
      at: Option[Instant] = None,
      /**
       * On a rollback, the generation whose descriptor this is (feature 033). A rollback is an
       * apply in every other respect, so everything that reacts to one reacts to the other, and a
       * build that predates the field skips it and reads an apply.
       */
      rolledBackTo: Option[Long] = None
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
      database: Option[String] = None,
      /**
       * The operator's reported broker phase, verbatim (feature 027) — see
       * `com.thinkmorestupidless.ankka.crd.BrokerStatus.phase`. `None` when there is nothing to
       * report and for events from before this field existed.
       */
      broker: Option[String] = None,
      /**
       * The operator's reported object storage phase, verbatim (feature 034). `None` for a service
       * with none and for events from before it existed.
       */
      objectStorage: Option[String] = None,
      /**
       * What the operator reported of the bucket beyond its phase (feature 039): which store it is
       * in, its name and address as reported, its location and soft-delete window, and a move.
       * `None` for events from before it existed, which a reader takes as a bucket in Garage.
       */
      storage: Option[StorageReport] = None
  )

  case ServiceDeleted(actor: Option[Actor] = None, at: Option[Instant] = None)

  /**
   * The service's organization was disabled, or enabled again (feature 008). Desired state owned by
   * the organization, kept apart from `ServicePaused`, which its members own: re-enabling restores
   * exactly what the members had chosen.
   */
  case ServiceSuspended(actor: Option[Actor] = None, at: Option[Instant] = None)
  case ServiceReinstated(actor: Option[Actor] = None, at: Option[Instant] = None)

  /**
   * A member asked for the service's storage credential to be issued again (feature 039). Desired
   * state beside the descriptor, like exposure: no deployment generation; the operator issues a new
   * credential, rolls the service onto it, and ends the old one after the rotation grace.
   * `generation` is the credential's, not the service's.
   */
  case StorageCredentialReissued(
      generation: Int,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /**
   * A member asked for the service's bucket to be moved from Garage to Google Cloud Storage
   * (feature 039), its writes paused for at most `writePauseBound` (`10m` when the member named
   * none). `generation` is the move's, raised per request, so a move that failed can be asked for
   * again.
   */
  case StorageMoveRequested(
      generation: Int,
      writePauseBound: String,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /**
   * A member asked for the installation's current bucket settings — its soft-delete window and its
   * wrapping key — to be applied to the service's bucket in Google Cloud Storage (feature 039). A
   * changed setting reaches a new bucket by itself and an existing one only so, because shortening
   * the window over regulated documents is a decision, not a reconcile.
   */
  case StorageSettingsReapplied(
      generation: Int,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

/** What an operator submits to change a service. */
final case class ApplyService(projectId: String, descriptor: ServiceDescriptor)

/**
 * A rollback to a named generation. The endpoint resolves a request that names none before sending
 * this, so that two people asking at once make one rollback and one refusal, not a rollback and its
 * undoing.
 */
final case class RollbackService(generation: Long)

/** What the reconciler reports back. */
final case class ServiceObservation(
    generation: Long,
    lifecycle: ServiceLifecycle,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String] = None,
    confirmed: Boolean = true,
    database: Option[String] = None,
    broker: Option[String] = None,
    objectStorage: Option[String] = None,
    storage: Option[StorageReport] = None
)

/**
 * What the operator reported of a service's bucket beyond its phase (feature 039). The bucket's
 * name and address are the operator's, as the cloud provider reported them in Google Cloud Storage,
 * so the control plane shows them rather than deriving them. Never a key.
 *
 * @param move
 *   a move's state, as the operator wrote it: `Requested`, `Copying`, `Pausing`, `Verifying`,
 *   `Switched` or `Failed`; its detail is the observation's
 */
final case class StorageReport(
    store: Option[String] = None,
    bucket: Option[String] = None,
    bucketAddress: Option[String] = None,
    location: Option[String] = None,
    softDeleteDays: Option[Int] = None,
    move: Option[String] = None,
    moveGeneration: Option[Int] = None,
    /** When the move's write pause began, as the operator wrote it, RFC 3339. */
    movePausedAt: Option[String] = None
)

/**
 * A deploy token's life: created, used (at most once a day), revoked.
 *
 * `DeployTokenUsed` carries no actor, unlike every other command-produced event here, because no
 * caller asks for it — a control plane node records it from its own background task after the token
 * authenticated a request. The digest is on the created event and nowhere else; the secret is on
 * nothing.
 */
enum DeployTokenEvent:
  case DeployTokenCreated(
      organizationId: String,
      label: String,
      digest: String,
      expiresAt: Option[Instant] = None,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )

  /** The date, not the instant: see `DeployToken.lastUsed`. */
  case DeployTokenUsed(date: java.time.LocalDate)

  case DeployTokenRevoked(actor: Option[Actor] = None, at: Option[Instant] = None)

/** `DeployTokenEntity.create`. The secret never appears; only what was derived from it. */
final case class RecordDeployToken(
    organizationId: String,
    label: String,
    digest: String,
    expiresAt: Option[Instant] = None
)

/**
 * `ProjectEntity.configureRegistry` — what the entity records once the Secret is in the cluster.
 *
 * No password: the endpoint writes the credential first and this says only that it did, so nothing
 * reaching the journal is worth stealing.
 */
final case class ConfigureRegistry(server: String, username: String, secretName: String)

/**
 * `ProjectEntity.setSecretEntries`: the names of entries the cluster already holds. Never a value.
 */
final case class SetSecretEntries(name: String, entries: Vector[String])

/** `ProjectEntity.removeSecretEntry`: an entry the cluster no longer holds. */
final case class RemoveSecretEntry(name: String, entry: String)

/**
 * `declare-topic`: a topic on the project, with its partitions (feature 027), compaction and
 * contract (feature 037).
 */
final case class DeclareTopic(
    name: String,
    partitions: Int,
    compacted: Boolean = false,
    contract: Option[Contract] = None
)

/** `remove-topic`: stop declaring a topic. */
final case class RemoveTopic(name: String)

/** `declare-broker`: a broker on the project (feature 037). */
final case class DeclareBroker(name: String, bootstrap: String, shape: String, secretName: String)

/** `remove-broker`: stop declaring a broker. */
final case class RemoveBroker(name: String)

/** `DeployTokenEntity.get` — everything the entity knows except the digest. */
final case class DeployTokenDetail(
    id: String,
    organizationId: String,
    label: String,
    subject: String,
    createdBy: Option[String] = None,
    createdAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    lastUsed: Option[java.time.LocalDate] = None
)
