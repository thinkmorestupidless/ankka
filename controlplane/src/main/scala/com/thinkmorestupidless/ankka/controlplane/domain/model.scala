package com.thinkmorestupidless.ankka.controlplane.domain

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.core.Metadata

import java.time.Instant

/**
 * Who did something, recorded on the event it produced (feature 008, FR-023).
 *
 * `subject` is the identity provider's stable id. `display` is a label — email, else name — for
 * listings, and may go stale. `administrative` is true only when the caller needed the
 * installation-level `platform-admin` role for the action: the audit trail distinguishes "acted as
 * owner" from "acted as administrator".
 */
final case class Actor(
    subject: String,
    display: Option[String] = None,
    administrative: Boolean = false
)

/**
 * What every command carries about its caller: who, and when.
 *
 * Carried as command *metadata* rather than in each payload, so that adding it changed no command's
 * shape and no existing test — and so that an entity reads it from `commandContext` exactly as it
 * reads its own id. Absent on a command that came from nowhere (a test, or a platform process
 * acting on its own behalf); the events it produces then carry no actor, which is exactly how
 * events from before this feature read (FR-024).
 */
final case class Attribution(actor: Actor, at: Instant):
  def metadata: Metadata =
    val base = Metadata.empty
      .set(Attribution.SubjectKey, actor.subject)
      .set(Attribution.AtKey, at.toString)
      .set(Attribution.AdministrativeKey, actor.administrative.toString)
    actor.display.fold(base)(display => base.set(Attribution.DisplayKey, display))

object Attribution:
  val SubjectKey        = "ankka.actor.subject"
  val DisplayKey        = "ankka.actor.display"
  val AdministrativeKey = "ankka.actor.administrative"
  val AtKey             = "ankka.actor.at"

  /**
   * An event's actor and time as a command's attribution — for acting *on behalf of* that event.
   */
  def from(actor: Option[Actor], at: Option[Instant]): Option[Attribution] =
    for a <- actor; t <- at yield Attribution(a, t)

  /** The platform acting for itself — the sweep converging a disabled organization, say. */
  val platform: Actor =
    Actor("ankka-controlplane", Some("the control plane"), administrative = true)

  def from(metadata: Metadata): Option[Attribution] =
    for
      subject <- metadata.get(SubjectKey)
      at      <- metadata.get(AtKey).flatMap(v => scala.util.Try(Instant.parse(v)).toOption)
    yield Attribution(
      Actor(
        subject,
        metadata.get(DisplayKey),
        metadata.get(AdministrativeKey).contains("true")
      ),
      at
    )

/** A member of an organization: their role, and the display claims recorded when they joined. */
final case class Member(
    role: Role,
    email: Option[String] = None,
    display: Option[String] = None,
    since: Option[Instant] = None,
    addedBy: Option[String] = None
)

/** A pending invitation: an email, claimed by whoever first presents it *verified*. */
final case class Invitation(
    role: Role,
    invitedAt: Option[Instant] = None,
    invitedBy: Option[String] = None
)

/**
 * An organization. The outermost tenancy boundary — and since feature 008 a real one: it records
 * its members by the identity provider's stable subject id, its pending invitations by email, and
 * whether a platform administrator has disabled it. All of it is folded from events, so it replays,
 * audits and enforces like everything else here.
 */
final case class Organization(
    id: String,
    name: String,
    deleted: Boolean = false,
    members: Map[String, Member] = Map.empty,
    invitations: Map[String, Invitation] = Map.empty,
    disabled: Boolean = false
):
  def exists: Boolean = name.nonEmpty && !deleted

  /**
   * Whether this id has ever been used.
   *
   * Distinct from `exists`, and the distinction is the point of a tombstone: a deleted organization
   * does not exist, but its id is not free either. Reusing it would let a later create inherit the
   * deleted tenant's projects and audit trail.
   */
  def known: Boolean = name.nonEmpty || deleted

  def roleOf(subject: String): Option[Role] = members.get(subject).map(_.role)
  def owners: Int                           = members.values.count(_.role == Role.Owner)
  def isLastOwner(subject: String): Boolean = roleOf(subject).contains(Role.Owner) && owners == 1
  def pendingFor(email: String): Option[Invitation] = invitations.get(Organization.key(email))

  /** The creator becomes the first owner. An event with no actor (pre-feature) creates no owner. */
  def onCreated(name: String, creator: Option[Actor], at: Option[Instant]): Organization =
    // An actor carries a display label, which is the email when the token had one — the only
    // thing this has to go on to refuse a later invitation of the owner's own address.
    val first = creator.map(a =>
      a.subject -> Member(
        Role.Owner,
        a.display.filter(_.contains('@')).map(Organization.key),
        a.display,
        at,
        a.display
      )
    )
    copy(name = name, members = members ++ first)

  def onRenamed(name: String): Organization = copy(name = name)

  /** A tombstone that also lets go of its people: nobody is a member of nothing. */
  def onDeleted: Organization = copy(deleted = true, members = Map.empty, invitations = Map.empty)

  def onInvited(
      email: String,
      role: Role,
      actor: Option[Actor],
      at: Option[Instant]
  ): Organization =
    copy(invitations =
      invitations + (Organization.key(email) -> Invitation(role, at, actor.flatMap(_.display)))
    )

  def onInvitationRevoked(email: String): Organization =
    copy(invitations = invitations - Organization.key(email))

  /** The invitation's role goes to the subject; the invitation itself is spent. */
  def onClaimed(
      email: String,
      subject: String,
      display: Option[String],
      at: Option[Instant]
  ): Organization =
    val key = Organization.key(email)
    invitations.get(key) match
      case None => this
      case Some(invitation) =>
        copy(
          invitations = invitations - key,
          members = members + (subject -> Member(
            invitation.role,
            Some(key),
            display,
            at,
            invitation.invitedBy
          ))
        )

  def onMemberAdded(
      subject: String,
      role: Role,
      email: Option[String],
      display: Option[String],
      actor: Option[Actor],
      at: Option[Instant]
  ): Organization =
    copy(members =
      members + (subject -> Member(
        role,
        email.map(Organization.key),
        display,
        at,
        actor.flatMap(_.display)
      ))
    )

  def onMemberRemoved(subject: String): Organization = copy(members = members - subject)

  def onRoleChanged(subject: String, role: Role): Organization =
    members.get(subject).fold(this)(m => copy(members = members + (subject -> m.copy(role = role))))

  def onDisabled: Organization = copy(disabled = true)
  def onEnabled: Organization  = copy(disabled = false)

object Organization:
  def empty(id: String): Organization = Organization(id, "")

  /** Emails compare case-insensitively and without surrounding space. */
  def key(email: String): String = email.trim.toLowerCase

/** A project. Services live in one. */
final case class Project(
    id: String,
    name: String,
    organizationId: String,
    deleted: Boolean = false
):
  def exists: Boolean = name.nonEmpty && !deleted

  /** As for [[Organization.known]] — a deleted project's id stays taken. */
  def known: Boolean = name.nonEmpty || deleted

  def onCreated(name: String, organizationId: String): Project =
    copy(name = name, organizationId = organizationId)

  def onRenamed(name: String): Project = copy(name = name)
  def onDeleted: Project               = copy(deleted = true)

/**
 * A service: desired state and observed state side by side.
 *
 * This split is the whole control plane in miniature. `descriptor` and `generation` are what an
 * operator asked for; `lifecycle`, `readyInstances` and `desiredInstances` are what the cluster
 * reports. Reconciliation is the act of closing the gap, and keeping the two in one entity means a
 * reader always sees both without a join.
 */
final case class Service(
    key: ServiceKey,
    descriptor: Option[ServiceDescriptor],
    /**
     * Bumped by every apply and every restart.
     *
     * An observation carries the generation it describes, so a stale report from a superseded
     * deployment can be recognised and dropped rather than overwriting the state of a newer one.
     */
    generation: Long,
    /**
     * Desired state: an operator asked for this service to stop.
     *
     * Separate from `lifecycle` deliberately. `lifecycle` is *observed* — the reconciler writes it
     * — so deriving "is this paused" from it lets a report that was already in flight when the
     * pause happened erase the pause. Pause does not bump the generation, so the staleness guard
     * cannot catch that one either. Desired state and observed state do not share a field.
     */
    paused: Boolean,
    lifecycle: ServiceLifecycle,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String],
    /**
     * Whether the last observation was a confirmed read of the cluster.
     *
     * Orthogonal to `lifecycle` — any lifecycle can be unconfirmed — which is why it is a separate
     * field rather than an eighth `ServiceLifecycle` case.
     */
    confirmed: Boolean,
    deleted: Boolean,
    /**
     * The operator's last-reported database phase, verbatim — `None` for the escape hatch and
     * before the first observation arrives.
     */
    database: Option[String] = None,
    /**
     * How many restarts have been asked for. Projected to the resource; see
     * `AnkkaServiceSpec.restarts`.
     */
    restarts: Int = 0,
    /**
     * Desired state: the service answers at its platform-derived hostname. The hostname itself is
     * not stored — the endpoint derives it from the name, the project and the base domain, and the
     * operator derives the same one to render the route.
     */
    exposed: Boolean = false,
    /**
     * Desired state, owned by the *organization*: disabled means every one of its services stops
     * (feature 008). Separate from `paused`, which the members own, so that re-enabling restores
     * exactly what they had chosen.
     */
    suspended: Boolean = false,
    /** The last `Service.HistoryLimit` command-produced changes, newest first (FR-025). */
    history: Vector[HistoryEntry] = Vector.empty
):
  def name: String      = key.name
  def projectId: String = key.projectId

  def exists: Boolean = descriptor.isDefined && !deleted

  def image: String = descriptor.fold("")(_.service.image)

  def isPaused: Boolean = paused

  /** The replica count the reconciler should aim for, which is zero while paused or suspended. */
  def targetInstances: Int =
    if isPaused || suspended then 0
    else descriptor.fold(0)(_.service.resources.autoscaling.minInstances)

  private def remembering(kind: String, actor: Option[Actor], at: Option[Instant]): Service =
    val entry = HistoryEntry(
      kind,
      generation,
      actor.map(a => HistoryActor(a.subject, a.display, a.administrative)),
      at
    )
    copy(history = (entry +: history).take(Service.HistoryLimit))

  /** Every command-produced fold remembers who asked; an observation is not a command. */
  def remember(kind: String, actor: Option[Actor], at: Option[Instant]): Service =
    remembering(kind, actor, at)

  /**
   * Applies a descriptor, which also un-deletes the service.
   *
   * Deliberately unlike an organization or a project, whose ids stay taken once deleted. A service
   * name is a deployment target rather than a tenancy boundary, and an operator re-applying a
   * descriptor for a name they previously removed means exactly what it says — the generation keeps
   * climbing, so the history is still there.
   */
  def onApplied(descriptor: ServiceDescriptor, generation: Long): Service =
    copy(
      descriptor = Some(descriptor),
      generation = generation,
      // An apply supersedes whatever was observed, so the service is in flight again
      // until the reconciler says otherwise — but a paused service stays paused, since
      // changing the descriptor is not the same as asking for it to run.
      lifecycle = if isPaused then ServiceLifecycle.Paused else ServiceLifecycle.UpdateInProgress,
      detail = None,
      // An operator action supersedes whatever staleness was recorded: the question is now
      // about the new generation, which nothing has reported on yet.
      confirmed = true,
      deleted = false
    )

  def onRestarted(generation: Long): Service =
    copy(
      generation = generation,
      restarts = restarts + 1,
      lifecycle = ServiceLifecycle.UpdateInProgress,
      readyInstances = 0,
      detail = None,
      confirmed = true
    )

  def onPaused: Service =
    copy(
      paused = true,
      lifecycle = ServiceLifecycle.Paused,
      desiredInstances = 0,
      detail = None,
      confirmed = true
    )

  def onResumed: Service =
    copy(
      paused = false,
      lifecycle = ServiceLifecycle.UpdateInProgress,
      detail = None,
      confirmed = true
    )

  /**
   * Folds in an observation, ignoring one that describes a superseded generation.
   *
   * The guard lives in the fold rather than in the command handler so that replay reproduces
   * exactly the same state — a late observation is dropped identically the first time and every
   * time after.
   */
  def onObserved(event: ServiceEvent.ServiceObserved): Service =
    if event.generation < generation then this
    else
      copy(
        // Desired state wins over the report for the two words the members and the
        // organization own: a paused or suspended service says so whatever the operator saw —
        // an operator that has scaled it to zero has nothing more specific to add.
        lifecycle =
          if isPaused then ServiceLifecycle.Paused
          else if suspended then ServiceLifecycle.Suspended
          else event.lifecycle,
        readyInstances = event.readyInstances,
        desiredInstances = event.desiredInstances,
        detail = event.detail,
        confirmed = event.confirmed,
        database = event.database
      )

  def onExposed: Service   = copy(exposed = true)
  def onUnexposed: Service = copy(exposed = false)

  /** A paused service keeps saying `Paused`: its members' choice is the more specific fact. */
  def onSuspended: Service =
    copy(
      suspended = true,
      lifecycle = if isPaused then ServiceLifecycle.Paused else ServiceLifecycle.Suspended,
      desiredInstances = 0,
      detail = None,
      confirmed = true
    )

  /** Back to what the members had chosen: paused stays paused, anything else is in flight again. */
  def onReinstated: Service =
    copy(
      suspended = false,
      lifecycle = if isPaused then ServiceLifecycle.Paused else ServiceLifecycle.UpdateInProgress,
      detail = None,
      confirmed = true
    )

  def onDeleted: Service =
    copy(
      deleted = true,
      paused = false,
      // Likewise: an organization enabled again has nothing to reinstate here, and a disabled one
      // refuses the apply that would recreate it.
      suspended = false,
      // A re-applied name starts private again: the route died with the service.
      exposed = false,
      lifecycle = ServiceLifecycle.NotDeployed,
      readyInstances = 0,
      desiredInstances = 0
    )

  def toStatus: ServiceStatus =
    ServiceStatus(
      name = name,
      projectId = projectId,
      lifecycle = lifecycle,
      generation = generation,
      image = image,
      readyInstances = readyInstances,
      desiredInstances = desiredInstances,
      detail = detail,
      confirmed = confirmed,
      database = database.map(Service.databasePhrase),
      exposed = exposed,
      suspended = suspended,
      paused = paused
    )

object Service:

  /**
   * How much history a service keeps in its state. Enough to answer "who did this"; never
   * unbounded.
   */
  val HistoryLimit = 50

  /**
   * The fold, as a pure function: the entity applies it, and a test that wants to prove replay
   * reproduces the state applies the same one rather than a second copy of it.
   */
  def fold(current: Service, event: ServiceEvent): Service =
    import ServiceEvent.*
    event match
      case ServiceApplied(_, descriptor, generation, actor, at) =>
        current.onApplied(descriptor, generation).remember("applied", actor, at)
      case ServiceRestarted(generation, actor, at) =>
        current.onRestarted(generation).remember("restarted", actor, at)
      case ServicePaused(actor, at)     => current.onPaused.remember("paused", actor, at)
      case ServiceResumed(actor, at)    => current.onResumed.remember("resumed", actor, at)
      case ServiceExposed(actor, at)    => current.onExposed.remember("exposed", actor, at)
      case ServiceUnexposed(actor, at)  => current.onUnexposed.remember("unexposed", actor, at)
      case observed: ServiceObserved    => current.onObserved(observed)
      case ServiceDeleted(actor, at)    => current.onDeleted.remember("deleted", actor, at)
      case ServiceSuspended(actor, at)  => current.onSuspended.remember("suspended", actor, at)
      case ServiceReinstated(actor, at) => current.onReinstated.remember("reinstated", actor, at)

  /**
   * The operator's reported database phase
   * (`com.thinkmorestupidless.ankka.crd.DatabaseStatus.phase`, produced by
   * `Provisioning.reportedPhase`), translated into the short phrase `ServiceStatus.database`
   * documents. A lookup, not a re-derivation — the phase itself is decided in exactly one place
   * (the operator's `Provisioning.decide`), so this can only ever reword it, never disagree with it
   * (research R11, US4's T051).
   */
  def databasePhrase(phase: String): String = phase match
    case "Waiting"     => "waiting for database"
    case "Provisioned" => "provisioned"
    case "Recovered"   => "recovered existing data"
    case "Supplied"    => "supplied"
    case "Failed"      => "database provisioning failed"
    case other         => other

  def empty(key: ServiceKey): Service =
    Service(
      key = key,
      descriptor = None,
      generation = 0L,
      paused = false,
      lifecycle = ServiceLifecycle.NotDeployed,
      readyInstances = 0,
      desiredInstances = 0,
      detail = None,
      confirmed = true,
      deleted = false
    )
