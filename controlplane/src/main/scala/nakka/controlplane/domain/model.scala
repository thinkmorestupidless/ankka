package nakka.controlplane.domain

import nakka.controlplane.api.*

/** An organization. The outermost tenancy boundary. */
final case class Organization(id: String, name: String, deleted: Boolean = false):
  def exists: Boolean = name.nonEmpty && !deleted

  /**
   * Whether this id has ever been used.
   *
   * Distinct from `exists`, and the distinction is the point of a tombstone: a deleted organization
   * does not exist, but its id is not free either. Reusing it would let a later create inherit the
   * deleted tenant's projects and audit trail.
   */
  def known: Boolean = name.nonEmpty || deleted

  def onCreated(name: String): Organization = copy(name = name)
  def onRenamed(name: String): Organization = copy(name = name)
  def onDeleted: Organization               = copy(deleted = true)

object Organization:
  def empty(id: String): Organization = Organization(id, "")

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
     * `NakkaServiceSpec.restarts`.
     */
    restarts: Int = 0
):
  def name: String      = key.name
  def projectId: String = key.projectId

  def exists: Boolean = descriptor.isDefined && !deleted

  def image: String = descriptor.fold("")(_.service.image)

  def isPaused: Boolean = paused

  /** The replica count the reconciler should aim for, which is zero while paused. */
  def targetInstances: Int =
    if isPaused then 0
    else descriptor.fold(0)(_.service.resources.autoscaling.minInstances)

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
        lifecycle = event.lifecycle,
        readyInstances = event.readyInstances,
        desiredInstances = event.desiredInstances,
        detail = event.detail,
        confirmed = event.confirmed,
        database = event.database
      )

  def onDeleted: Service =
    copy(
      deleted = true,
      paused = false,
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
      database = database.map(Service.databasePhrase)
    )

object Service:

  /**
   * The operator's reported database phase (`nakka.crd.DatabaseStatus.phase`, produced by
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
