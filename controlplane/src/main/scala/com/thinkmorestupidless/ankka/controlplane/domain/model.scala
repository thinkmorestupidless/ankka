package com.thinkmorestupidless.ankka.controlplane.domain

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.core.{CommandError, Contract, ErrorCode, Metadata}

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

  /**
   * The platform acting for itself — the sweep converging a disabled organization whose listing row
   * predates the disabling administrator being recorded on it, say. Where the row names them, the
   * sweep attributes to them instead, so the history reads the same whichever path suspended it.
   */
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
    disabled: Boolean = false,
    quota: Option[Quota] = None,
    record: UsageRecord = UsageRecord(),
    /** What projects granted this organization's machines, by grant id (feature 040). */
    received: Map[String, ReceivedGrant] = Map.empty
):
  def exists: Boolean = name.nonEmpty && !deleted
  def usage: Usage    = record.usage

  def onGrantRecorded(fields: GrantRecordedFields): Organization =
    copy(received = ReceivedGrant.fold(received, fields))

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

  /**
   * The creator becomes the first owner — or, when the event names an `owner`, that subject does,
   * seated by the creator (feature 011). An event with no actor and no owner (pre-feature) creates
   * no owner.
   */
  def onCreated(
      name: String,
      creator: Option[Actor],
      at: Option[Instant],
      owner: Option[Owner] = None
  ): Organization =
    val first = owner match
      case Some(o) =>
        Some(
          o.subject -> Member(
            Role.Owner,
            o.email.map(Organization.key),
            o.display,
            at,
            creator.map(_.subject)
          )
        )
      case None =>
        // An actor carries a display label, which is the email when the token had one — the only
        // thing this has to go on to refuse a later invitation of the owner's own address.
        creator.map(a =>
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

  /**
   * A tombstone that also lets go of its people: nobody is a member of nothing. Its usage goes the
   * same way — the projects were required to be gone first, and a quota on nothing means nothing.
   */
  def onDeleted: Organization =
    copy(
      deleted = true,
      members = Map.empty,
      invitations = Map.empty,
      quota = None,
      record = UsageRecord()
    )

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

  def onQuotaSet(quota: Quota): Organization = copy(quota = Some(quota))
  def onQuotaCleared: Organization           = copy(quota = None)

  /** The usage events, folded through the one function the listing row uses too. */
  def onUsage(event: OrganizationEvent): Organization = copy(record = record.fold(event))

object Organization:
  def empty(id: String): Organization = Organization(id, "")

  /** Emails compare case-insensitively and without surrounding space. */
  def key(email: String): String = email.trim.toLowerCase

/**
 * What an organization holds, exactly (feature 015): the ids of its projects and, for every service
 * across them, the `minInstances` its descriptor asks for, by `projectId/name`.
 *
 * Folded from the organization's own events by *one* function, used by the entity's state and by
 * the listing row alike, so a listing and an enforcement can never disagree about the count. The
 * events are written by the endpoints — a reservation before a project is created or a service
 * applied, a release after one is gone — so the record is true at the moment a quota is checked,
 * which no listing can promise.
 */
final case class UsageRecord(
    projects: Set[String] = Set.empty,
    services: Map[String, Int] = Map.empty
):
  def usage: Usage = Usage(projects.size, services.size, services.values.sum)

  def fold(event: OrganizationEvent): UsageRecord = event match
    case OrganizationEvent.ProjectReserved(projectId, _, _) => copy(projects = projects + projectId)
    case OrganizationEvent.ProjectReleased(projectId, _, _) => copy(projects = projects - projectId)
    case OrganizationEvent.ServiceReserved(key, instances, _, _) =>
      copy(services = services + (key -> instances))
    case OrganizationEvent.ServiceReleased(key, _, _) => copy(services = services - key)
    case OrganizationEvent.UsageReconciled(projects, services, _, _) =>
      UsageRecord(projects, services)
    case _ => this

/**
 * A registry the cluster must authenticate to in order to pull a project's images.
 *
 * **No password.** The credential goes straight to the cluster as a Secret and the journal records
 * only that one exists, where, and as whom — so a journal, a snapshot, a backup of either and every
 * view built from them hold nothing worth stealing. `secretName` is the name the control plane gave
 * that Secret and the operator names on the pod; it is stored rather than derived so a later
 * platform can hold more than one without rewriting history.
 */
final case class RegistryRef(
    server: String,
    username: String,
    secretName: String,
    setBy: Option[Actor] = None,
    setAt: Option[Instant] = None
)

/**
 * What the control plane knows of a project secret: the names of its entries, and who last set one.
 * Never a value — the control plane cannot read the Secret back, so this is the only listing there
 * is.
 */
final case class ProjectSecretRef(
    entries: Set[String],
    setBy: Option[Actor] = None,
    setAt: Option[Instant] = None
)

/**
 * A topic a project declares (feature 027): its partitions, and when it was first declared — kept
 * when the partitions are raised, so a topic the broker held from before this declaration can be
 * told apart from one it made for it.
 */
final case class DeclaredTopic(
    partitions: Int,
    declaredAt: Option[Instant] = None,
    /** Feature 037: the broker keeps the last message under each key. */
    compacted: Boolean = false,
    /**
     * Feature 037: the contract every side must state; its document is in the project's schema
     * store.
     */
    contract: Option[Contract] = None
)

/**
 * A broker a project declares beside the installation's (feature 037): where it is, the shape of
 * its credential, and the project secret holding it. A component names it for one topic.
 */
final case class DeclaredBroker(
    bootstrap: String,
    shape: String,
    secretName: String,
    declaredAt: Option[Instant] = None
)

/** Who did something to a grant, and when: what an event carried, kept on the grant. */
final case class GrantMark(actor: Option[Actor] = None, at: Option[Instant] = None):
  def display: GrantAct = GrantAct(actor.flatMap(_.display).orElse(actor.map(_.subject)), at)

/**
 * A grant a project holds (feature 040): one grantee, one target, and where it is in its life. The
 * project is the grantor; this record, written by the project's own commands, is the only one a
 * command writes. Identified by `id`; while it is live, also by its grantee and target together.
 */
final case class Grant(
    id: String,
    grantee: Grantee,
    target: GrantTarget,
    state: GrantState,
    granted: GrantMark = GrantMark(),
    answered: Option[GrantMark] = None,
    ended: Option[GrantMark] = None
):
  def detail(effect: String): GrantDetail =
    GrantDetail(
      id,
      grantee,
      target,
      state,
      effect,
      granted.display,
      answered.map(_.display),
      ended.map(_.display)
    )

/** One change to a grant as the grantee side recorded it. */
final case class GrantChangeEntry(
    change: GrantChange,
    actor: Option[Actor] = None,
    at: Option[Instant] = None
)

/**
 * A grant as the grantee side keeps it: on the grantee project for a service, on the organization
 * for a machine. Derived from the granting project's events by a consumer, never written beside
 * them, so the two cannot disagree for longer than the consumer takes.
 */
final case class ReceivedGrant(
    id: String,
    grantingProject: String,
    grantingOrganization: String,
    grantee: Grantee,
    target: GrantTarget,
    state: GrantState,
    changes: Vector[GrantChangeEntry] = Vector.empty
):
  def recorded(change: GrantChange): Boolean = changes.exists(_.change == change)

object ReceivedGrant:

  /** The state a grant is in after `change`. */
  def stateAfter(change: GrantChange): GrantState = change match
    case GrantChange.Made | GrantChange.Accepted => GrantState.Accepted
    case GrantChange.Offered                     => GrantState.Pending
    case GrantChange.Declined                    => GrantState.Declined
    case GrantChange.Withdrawn                   => GrantState.Withdrawn
    case GrantChange.Revoked                     => GrantState.Revoked
    case GrantChange.Relinquished                => GrantState.Relinquished
    case GrantChange.Lapsed                      => GrantState.Lapsed

  /** Fold one recorded change into what a grantee side holds, by the grant's id. */
  def fold(
      held: Map[String, ReceivedGrant],
      recorded: GrantRecordedFields
  ): Map[String, ReceivedGrant] =
    val entry = GrantChangeEntry(recorded.change, recorded.actor, recorded.at)
    val next = held.get(recorded.id) match
      case Some(had) =>
        had.copy(state = stateAfter(recorded.change), changes = had.changes :+ entry)
      case None =>
        ReceivedGrant(
          recorded.id,
          recorded.grantingProject,
          recorded.grantingOrganization,
          recorded.grantee,
          recorded.target,
          stateAfter(recorded.change),
          Vector(entry)
        )
    held.updated(recorded.id, next)

/** What a `GrantRecorded` event says, the same on a project and on an organization. */
final case class GrantRecordedFields(
    id: String,
    grantingProject: String,
    grantingOrganization: String,
    grantee: Grantee,
    target: GrantTarget,
    change: GrantChange,
    actor: Option[Actor] = None,
    at: Option[Instant] = None
)

/**
 * Who deleted a project or a registered machine, and when (feature 040): what a grant made to it
 * just before is lapsed under, when the grant reaches the grantee's side only after the deletion.
 */
final case class Deletion(by: Option[Actor] = None, at: Option[Instant] = None)

/** A project. Services live in one. */
final case class Project(
    id: String,
    name: String,
    organizationId: String,
    deleted: Boolean = false,
    /** Who deleted it and when (feature 040); none while it exists. */
    deletion: Option[Deletion] = None,
    registry: Option[RegistryRef] = None,
    /** By the secret's name. A secret with no entry left is not here. */
    secrets: Map[String, ProjectSecretRef] = Map.empty,
    /** By the topic's name, as the project's components use it (feature 027). */
    topics: Map[String, DeclaredTopic] = Map.empty,
    /** By the broker's name, as a component names it (feature 037). */
    brokers: Map[String, DeclaredBroker] = Map.empty,
    /** The grants this project holds, by id (feature 040). An ended grant stays, as history. */
    grants: Map[String, Grant] = Map.empty,
    /** What other projects granted this project's services, by grant id (feature 040). */
    received: Map[String, ReceivedGrant] = Map.empty
):
  def exists: Boolean = name.nonEmpty && !deleted

  /** The live grant of this grantee on this target, if there is one. */
  def liveGrant(grantee: Grantee, target: GrantTarget): Option[Grant] =
    grants.values.find(g => g.state.live && g.grantee == grantee && g.target == target)

  def onGrantMade(
      id: String,
      grantee: Grantee,
      target: GrantTarget,
      pending: Boolean,
      actor: Option[Actor],
      at: Option[Instant]
  ): Project =
    val state = if pending then GrantState.Pending else GrantState.Accepted
    copy(grants = grants.updated(id, Grant(id, grantee, target, state, GrantMark(actor, at))))

  /** An answer from the grantee's side: accepted or declined. */
  def onGrantAnswered(
      id: String,
      state: GrantState,
      actor: Option[Actor],
      at: Option[Instant]
  ): Project =
    grants.get(id).fold(this) { g =>
      val answered = Some(GrantMark(actor, at))
      val ended    = Option.when(!state.live)(GrantMark(actor, at))
      copy(grants = grants.updated(id, g.copy(state = state, answered = answered, ended = ended)))
    }

  /** An end from either side, or a lapse. */
  def onGrantEnded(
      id: String,
      state: GrantState,
      actor: Option[Actor],
      at: Option[Instant]
  ): Project =
    grants
      .get(id)
      .fold(this)(g =>
        copy(grants = grants.updated(id, g.copy(state = state, ended = Some(GrantMark(actor, at)))))
      )

  def onGrantRecorded(fields: GrantRecordedFields): Project =
    copy(received = ReceivedGrant.fold(received, fields))

  /** As for [[Organization.known]] — a deleted project's id stays taken. */
  def known: Boolean = name.nonEmpty || deleted

  def onCreated(name: String, organizationId: String): Project =
    copy(name = name, organizationId = organizationId)

  def onRenamed(name: String): Project = copy(name = name)
  def onDeleted: Project               = copy(deleted = true)
  def onDeleted(by: Option[Actor], at: Option[Instant]): Project =
    copy(deleted = true, deletion = Some(Deletion(by, at)))

  def onRegistryConfigured(
      server: String,
      username: String,
      secretName: String,
      actor: Option[Actor],
      at: Option[Instant]
  ): Project =
    copy(registry = Some(RegistryRef(server, username, secretName, actor, at)))

  def onRegistryCleared: Project = copy(registry = None)

  def onSecretEntriesSet(
      name: String,
      entries: Vector[String],
      actor: Option[Actor],
      at: Option[Instant]
  ): Project =
    val had = secrets.get(name).map(_.entries).getOrElse(Set.empty)
    copy(secrets = secrets.updated(name, ProjectSecretRef(had ++ entries, actor, at)))

  def onTopicDeclared(
      name: String,
      partitions: Int,
      at: Option[Instant],
      compacted: Boolean = false,
      contract: Option[Contract] = None
  ): Project =
    val declaredAt = topics.get(name).fold(at)(_.declaredAt)
    copy(topics = topics.updated(name, DeclaredTopic(partitions, declaredAt, compacted, contract)))

  def onTopicRemoved(name: String): Project = copy(topics = topics - name)

  def onBrokerDeclared(name: String, broker: DeclaredBroker): Project =
    val declaredAt = brokers.get(name).fold(broker.declaredAt)(_.declaredAt)
    copy(brokers = brokers.updated(name, broker.copy(declaredAt = declaredAt)))

  def onBrokerRemoved(name: String): Project = copy(brokers = brokers - name)

  def onSecretEntryRemoved(name: String, entry: String): Project =
    secrets.get(name) match
      case None => this
      case Some(ref) =>
        val left = ref.entries - entry
        if left.isEmpty then copy(secrets = secrets - name)
        else copy(secrets = secrets.updated(name, ref.copy(entries = left)))

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
    /** The operator's last-reported broker phase, verbatim (feature 027). */
    broker: Option[String] = None,
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
    history: Vector[HistoryEntry] = Vector.empty,
    /**
     * The last `Service.KeptDescriptors` applied descriptors, newest first (feature 033): what a
     * rollback reads, so it never replays the journal. Read through `keptDescriptors`, never
     * directly — a state written before the feature has none, and that accessor seeds it.
     *
     * Never part of a reply but the two that return one descriptor: `desiredState` crosses nodes
     * for every service on every sweep, inside a frame that fifty large descriptors would overflow.
     */
    kept: Vector[KeptDescriptor] = Vector.empty,
    /**
     * The operator's last-reported object storage phase, verbatim (feature 034). The bucket's name
     * and address are not stored: both are derived when a status is built.
     */
    objectStorage: Option[String] = None,
    /** The operator's last report on whether the service reads its grants (feature 040). */
    grants: Option[String] = None
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

  /** Every command-produced fold remembers who asked; an observation is not a command. */
  def remember(
      kind: String,
      actor: Option[Actor],
      at: Option[Instant],
      recorded: Option[ServiceDescriptor] = None,
      rolledBackTo: Option[Long] = None
  ): Service =
    val entry = HistoryEntry(
      kind,
      generation,
      actor.map(a => HistoryActor(a.subject, a.display, a.administrative)),
      at,
      image = recorded.map(_.service.image),
      digest = recorded.map(_.digest),
      rolledBackTo = rolledBackTo
    )
    copy(history = (entry +: history).take(Service.HistoryLimit))

  /**
   * The kept descriptors, newest first. A state written before they were kept has none, which would
   * leave the first bad apply after an upgrade with nothing to roll back to; so its current
   * descriptor stands as kept, at the generation of its newest recorded apply. A function of the
   * state alone, so replay reproduces it.
   */
  def keptDescriptors: Vector[KeptDescriptor] =
    if kept.nonEmpty then kept
    else
      descriptor.toVector.map { current =>
        val appliedAt = history.find(_.kind == "applied").fold(generation)(_.generation)
        KeptDescriptor(appliedAt, current)
      }

  /**
   * The descriptor recorded at generation `n`, or why there is none to give. Only an apply and a
   * restart move the generation, so a generation inside the kept range with no descriptor of its
   * own is a restart's, and it ran the newest kept one below it.
   */
  def descriptorAt(n: Long): Either[RollbackRefusal, KeptDescriptor] =
    val held = keptDescriptors
    if n < 1 || n > generation then Left(RollbackRefusal.NoSuchGeneration(n))
    else
      held.find(_.generation == n) match
        case Some(found) => Right(found)
        case None =>
          held.find(_.generation < n) match
            case Some(ran) if held.lastOption.exists(_.generation < n) =>
              Left(RollbackRefusal.NoDescriptor(n, ran.generation))
            case _ =>
              Left(RollbackRefusal.NotKept(n, held.lastOption.fold(generation)(_.generation)))

  /**
   * What a rollback would apply. Named, the generation's descriptor unless the service already has
   * it; unnamed, the newest kept descriptor that differs from the service's. "Differs" is by
   * digest, computed now from the descriptors themselves, never from a digest a history entry
   * stored.
   */
  def rollbackTarget(requested: Option[Long]): Either[RollbackRefusal, KeptDescriptor] =
    val current = descriptor.map(_.digest)
    requested match
      case Some(n) =>
        descriptorAt(n).flatMap { found =>
          if current.contains(found.descriptor.digest) then Left(RollbackRefusal.SameDescriptor(n))
          else Right(found)
        }
      case None =>
        keptDescriptors
          .find(k => !current.contains(k.descriptor.digest))
          .toRight(RollbackRefusal.NothingToRollBackTo)

  /**
   * The event a rollback to generation `n` persists, or why it may not: the whole decision, as a
   * function of the state, so the entity and a test that builds a state by folding share it. The
   * descriptor's validation runs again because the platform's rules may have changed since it ran.
   */
  def rollingBack(
      n: Long,
      actor: Option[Actor],
      at: Option[Instant]
  ): Either[CommandError, ServiceEvent.ServiceApplied] =
    rollbackTarget(Some(n)).left
      .map(refusal => CommandError(refusal.message(name), refusal.code))
      .flatMap { target =>
        val problems = target.descriptor.problems
        if problems.nonEmpty then
          Left(
            CommandError(
              problems
                .mkString(s"invalid descriptor at generation ${target.generation}: ", "; ", ""),
              ErrorCode.BadRequest
            )
          )
        else
          Right(
            ServiceEvent.ServiceApplied(
              projectId,
              target.descriptor,
              generation + 1,
              actor,
              at,
              rolledBackTo = Some(target.generation)
            )
          )
      }

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
      kept =
        (KeptDescriptor(generation, descriptor) +: keptDescriptors).take(Service.KeptDescriptors),
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
        database = event.database,
        broker = event.broker,
        objectStorage = event.objectStorage,
        grants = event.grants
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
      // A web-hosted service has no database, whatever an operator reported or did not.
      database =
        if descriptor.exists(_.service.isWebHosted) then Some(Service.NoDatabase)
        else database.map(Service.databasePhrase),
      exposed = exposed,
      suspended = suspended,
      paused = paused,
      hosting = descriptor.map(_.service.hosting).getOrElse("embedded"),
      protocol = descriptor.flatMap(_.service.protocol),
      // The states are the endpoint's to fill, from the mounted services' own entities.
      mounts =
        descriptor.toVector.flatMap(_.service.mounts).map(m => MountStatus(m.path, m.service)),
      callers = descriptor.toVector.flatMap(_.service.callers),
      processPort = descriptor.flatMap(_.service.resolvedProcessPort),
      broker = broker.map(Service.brokerPhrase),
      objectStorage = objectStorage.map(Service.objectStoragePhrase),
      grants = grants,
      bucket = Service.bucketOf(projectId, name, descriptor),
      bucketAddress = Service.bucketPathOf(projectId, name, descriptor)
    )

/** An applied descriptor and the generation that applied it (feature 033). */
final case class KeptDescriptor(generation: Long, descriptor: ServiceDescriptor)

/** Why a rollback, or a read of a past descriptor, has nothing to give (feature 033). */
enum RollbackRefusal:
  case NoSuchGeneration(generation: Long)
  case NotKept(generation: Long, oldest: Long)
  case NoDescriptor(generation: Long, ran: Long)
  case SameDescriptor(generation: Long)
  case NothingToRollBackTo

  def message(service: String): String = this match
    case NoSuchGeneration(n) => s"service '$service' has no generation $n"
    case NotKept(n, oldest) =>
      s"the descriptor of generation $n is no longer kept; the oldest kept is generation $oldest"
    case NoDescriptor(n, ran) =>
      s"generation $n was a restart and ran the descriptor of generation $ran"
    case SameDescriptor(n) => s"service '$service' already has the descriptor of generation $n"
    case NothingToRollBackTo =>
      s"service '$service' has no earlier generation with a different descriptor"

  def code: ErrorCode = this match
    case NoSuchGeneration(_) => ErrorCode.NotFound
    case _                   => ErrorCode.Conflict

object Service:

  /**
   * How much history a service keeps in its state. Enough to answer "who did this"; never
   * unbounded.
   */
  val HistoryLimit = 50

  /**
   * How many applied descriptors a service keeps to roll back to: as many as its history shows, so
   * every history entry that recorded a descriptor can still be rolled back to.
   */
  val KeptDescriptors = 50

  /**
   * The fold, as a pure function: the entity applies it, and a test that wants to prove replay
   * reproduces the state applies the same one rather than a second copy of it.
   */
  def fold(current: Service, event: ServiceEvent): Service =
    import ServiceEvent.*
    event match
      case ServiceApplied(_, descriptor, generation, actor, at, rolledBackTo) =>
        val kind = if rolledBackTo.isDefined then "rolled-back" else "applied"
        current
          .onApplied(descriptor, generation)
          .remember(kind, actor, at, Some(descriptor), rolledBackTo)
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
  /** What `ServiceStatus.database` says of a web-hosted service, which has none (feature 021). */
  val NoDatabase: String = "none"

  def databasePhrase(phase: String): String = phase match
    case "Waiting"     => "waiting for database"
    case "Provisioned" => "provisioned"
    case "Recovered"   => "recovered existing data"
    case "Supplied"    => "supplied"
    case "Failed"      => "database provisioning failed"
    case other         => other

  /** The operator's reported broker phase, as the short phrase `ServiceStatus.broker` documents. */
  def brokerPhrase(phase: String): String = phase match
    case "Waiting"     => "waiting for broker"
    case "Provisioned" => "provisioned"
    case "Recovered"   => "recovered"
    case "Supplied"    => "supplied"
    case "Failed"      => "broker provisioning failed"
    case other         => other

  /**
   * The operator's reported object storage phase as a phrase (feature 034). The one function the
   * entity's status and the listing's row both use, so the two cannot say different things.
   */
  def objectStoragePhrase(phase: String): String = phase match
    case "Waiting"     => "waiting for object storage"
    case "Provisioned" => "provisioned"
    case "Recovered"   => "recovered existing bucket"
    case "Supplied"    => "supplied"
    case "Failed"      => "object storage provisioning failed"
    case other         => other

  /** The bucket a descriptor asks for, named as the operator names it. */
  def bucketOf(
      projectId: String,
      name: String,
      descriptor: Option[ServiceDescriptor]
  ): Option[String] =
    descriptor
      .filter(_.service.provisionObjectStorage)
      .map(_ => com.thinkmorestupidless.ankka.crd.Buckets.name(projectId, name))

  /**
   * The path of a bucket its descriptor asks to be reachable from the internet. The endpoint puts
   * the store's address in front of it (`DeployConfig.bucketAddressFor`), as it puts the hostname
   * on a service: the base domain is configuration, which neither the entity nor the listing has.
   */
  def bucketPathOf(
      projectId: String,
      name: String,
      descriptor: Option[ServiceDescriptor]
  ): Option[String] =
    descriptor
      .filter(d => d.service.provisionObjectStorage && d.service.exposeObjectStorage)
      .flatMap(_ => bucketOf(projectId, name, descriptor))
      .map("/" + _)

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

/**
 * A deploy token: a credential the control plane issues itself, for a machine.
 *
 * Its whole design is one decision — *the token's subject is an ordinary member of the
 * organization*. `token:<id>` goes into `Organization.members` with the `member` role when the
 * token is created, and from that moment every membership check, every attribution and every "what
 * you cannot see does not exist" 404 applies to it unchanged, because none of them has ever cared
 * how a subject was authenticated. There is no second authorization path for machines.
 *
 * Only `digest` is stored of the secret, and the secret itself exists for the length of one
 * response. `lastUsed` is a date rather than an instant deliberately: recording the instant would
 * mean a write per request, and "was this token used today" is the question an operator asks.
 */
final case class DeployToken(
    id: String,
    organizationId: String = "",
    label: String = "",
    digest: String = "",
    createdBy: Option[Actor] = None,
    createdAt: Option[Instant] = None,
    /** Absent only when the creator deliberately asked for a token that never expires. */
    expiresAt: Option[Instant] = None,
    lastUsed: Option[java.time.LocalDate] = None,
    revoked: Boolean = false
):
  /** A revoked token is a tombstone: its id is never reused, so this is not `known`. */
  def exists: Boolean = organizationId.nonEmpty && !revoked

  def expired(now: Instant): Boolean = expiresAt.exists(!_.isAfter(now))

  /** Whether this id has ever been used, revoked or not — what refuses a recreate. */
  def known: Boolean = organizationId.nonEmpty

  def subject: String = DeployToken.subjectOf(id)

  def onCreated(
      organizationId: String,
      label: String,
      digest: String,
      expiresAt: Option[Instant],
      actor: Option[Actor],
      at: Option[Instant]
  ): DeployToken =
    copy(
      organizationId = organizationId,
      label = label,
      digest = digest,
      createdBy = actor,
      createdAt = at,
      expiresAt = expiresAt
    )

  def onUsed(date: java.time.LocalDate): DeployToken = copy(lastUsed = Some(date))

  /** The digest is kept: it is part of the audit trail, and is useless without the secret. */
  def onRevoked: DeployToken = copy(revoked = true)

object DeployToken:

  /**
   * The subject a deploy token authenticates as.
   *
   * The `token:` prefix is not parsed by anything that authorizes — it is a label, so a members
   * listing can show what a subject is without a second field, and so a person reading an audit
   * trail can see that a machine made the change.
   */
  def subjectOf(id: String): String = s"token:$id"

  def empty(id: String): DeployToken = DeployToken(id)

  /** The lifetime a token gets when its creator does not choose one. */
  val DefaultLifetime: java.time.Duration = java.time.Duration.ofDays(90)

  /** The longest lifetime that may be chosen. */
  val MaximumLifetime: java.time.Duration = java.time.Duration.ofDays(365)

// ── Machines (feature 040) ────────────────────────────────────────────────────

/** What a machine may move through the broker, each a ceiling the broker enforces. */
final case class ByteRates(
    produceBytesPerSecond: Long,
    consumeBytesPerSecond: Long,
    requestPercentage: Int
)

/**
 * A machine registered on an organization, keyed `<organization>/<name>`. Deleting one is a
 * tombstone that may be registered over: a new machine, a new secret, and no grant of the old
 * one's, since a grant names the machine by its client id and the old grants lapsed with it.
 */
final case class Machine(
    id: String,
    organizationId: String = "",
    name: String = "",
    digest: String = "",
    registeredBy: Option[Actor] = None,
    registeredAt: Option[Instant] = None,
    byteRates: Option[ByteRates] = None,
    deleted: Boolean = false,
    /** Who deleted it and when; none while it exists, and none again once registered anew. */
    deletion: Option[Deletion] = None
):
  def exists: Boolean = organizationId.nonEmpty && !deleted

  def clientId: String =
    com.thinkmorestupidless.ankka.controlplane.api.Machines.clientId(organizationId, name)

  def onRegistered(
      organizationId: String,
      name: String,
      digest: String,
      actor: Option[Actor],
      at: Option[Instant]
  ): Machine =
    Machine(id, organizationId, name, digest, actor, at)

  def onByteRatesSet(rates: ByteRates): Machine = copy(byteRates = Some(rates))

  def onDeleted(by: Option[Actor], at: Option[Instant]): Machine =
    copy(deleted = true, deletion = Some(Deletion(by, at)))

  def summary: com.thinkmorestupidless.ankka.controlplane.api.MachineSummary =
    com.thinkmorestupidless.ankka.controlplane.api.MachineSummary(
      name = name,
      clientId = clientId,
      registeredBy = registeredBy.flatMap(_.display),
      registeredAt = registeredAt,
      byteRates = byteRates.map(r =>
        com.thinkmorestupidless.ankka.controlplane.api
          .ByteRatesRequest(r.produceBytesPerSecond, r.consumeBytesPerSecond, r.requestPercentage)
      )
    )

object Machine:
  def key(organizationId: String, name: String): String = s"$organizationId/$name"
