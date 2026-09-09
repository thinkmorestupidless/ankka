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
    lifecycle: ServiceLifecycle,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String],
    deleted: Boolean
):
  def name: String      = key.name
  def projectId: String = key.projectId

  def exists: Boolean = descriptor.isDefined && !deleted

  def image: String = descriptor.fold("")(_.service.image)

  def isPaused: Boolean = lifecycle == ServiceLifecycle.Paused

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
      deleted = false
    )

  def onRestarted(generation: Long): Service =
    copy(
      generation = generation,
      lifecycle = ServiceLifecycle.UpdateInProgress,
      readyInstances = 0,
      detail = None
    )

  def onPaused: Service =
    copy(lifecycle = ServiceLifecycle.Paused, desiredInstances = 0, detail = None)

  def onResumed: Service =
    copy(lifecycle = ServiceLifecycle.UpdateInProgress, detail = None)

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
        detail = event.detail
      )

  def onDeleted: Service =
    copy(
      deleted = true,
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
      detail = detail
    )

object Service:
  def empty(key: ServiceKey): Service =
    Service(
      key = key,
      descriptor = None,
      generation = 0L,
      lifecycle = ServiceLifecycle.NotDeployed,
      readyInstances = 0,
      desiredInstances = 0,
      detail = None,
      deleted = false
    )
