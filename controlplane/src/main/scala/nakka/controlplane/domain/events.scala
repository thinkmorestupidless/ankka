package nakka.controlplane.domain

import nakka.controlplane.api.*

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

enum OrganizationEvent:
  case OrganizationCreated(name: String)
  case OrganizationRenamed(name: String)
  case OrganizationDeleted

enum ProjectEvent:
  case ProjectCreated(name: String, organizationId: String)
  case ProjectRenamed(name: String)
  case ProjectDeleted

enum ServiceEvent:
  /**
   * A descriptor was applied.
   *
   * Carries the generation it produced rather than letting the fold compute it. An event that
   * states its own generation can be read by a view or a consumer without replaying everything
   * before it.
   */
  case ServiceApplied(projectId: String, descriptor: ServiceDescriptor, generation: Long)

  /** An operator asked for the running instances to be replaced. */
  case ServiceRestarted(generation: Long)

  case ServicePaused
  case ServiceResumed

  /** What the reconciler saw. `generation` is the desired state being reported on. */
  case ServiceObserved(
      generation: Long,
      lifecycle: ServiceLifecycle,
      readyInstances: Int,
      desiredInstances: Int,
      detail: Option[String]
  )

  case ServiceDeleted

/** What an operator submits to change a service. */
final case class ApplyService(projectId: String, descriptor: ServiceDescriptor)

/** What the reconciler reports back. */
final case class ServiceObservation(
    generation: Long,
    lifecycle: ServiceLifecycle,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String] = None
)
