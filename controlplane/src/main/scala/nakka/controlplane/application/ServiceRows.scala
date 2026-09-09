package nakka.controlplane.application

import nakka.controlplane.api.*
import nakka.controlplane.domain.*
import nakka.controlplane.domain.ServiceEvent.*
import nakka.core.{Codecs, ComponentId}
import nakka.sdk.*

/**
 * Every service, queryable by project.
 *
 * `ServiceEntity` can only be found by `projectId/name`, which is exactly the wrong shape for
 * `nakka services list` — the CLI's most-used command asks "what is in this project", a question no
 * single entity can answer.
 */
final class ServiceRowsView extends View[ServiceEvent, ServiceStatus]:

  def onChange(event: ServiceEvent): Effect =
    val key = ServiceKey.parse(updateContext.subject)
    (key, rowState) match
      case (None, _) =>
        // A subject that is not `projectId/name` cannot have come from ServiceEntity.
        effects.ignore()

      case (Some(k), current) =>
        val row = current.getOrElse(
          ServiceStatus(
            name = k.name,
            projectId = k.projectId,
            lifecycle = ServiceLifecycle.NotDeployed,
            generation = 0L,
            image = "",
            readyInstances = 0,
            desiredInstances = 0
          )
        )
        event match
          case ServiceApplied(_, descriptor, generation) =>
            effects.updateRow(
              row.copy(
                image = descriptor.service.image,
                generation = generation,
                lifecycle =
                  if row.lifecycle == ServiceLifecycle.Paused then ServiceLifecycle.Paused
                  else ServiceLifecycle.UpdateInProgress,
                detail = None
              )
            )

          case ServiceRestarted(generation) =>
            effects.updateRow(
              row.copy(
                generation = generation,
                lifecycle = ServiceLifecycle.UpdateInProgress,
                readyInstances = 0,
                detail = None
              )
            )

          case ServicePaused =>
            effects.updateRow(
              row.copy(lifecycle = ServiceLifecycle.Paused, desiredInstances = 0, detail = None)
            )

          case ServiceResumed =>
            effects.updateRow(
              row.copy(lifecycle = ServiceLifecycle.UpdateInProgress, detail = None)
            )

          case ServiceObserved(generation, lifecycle, ready, desired, detail) =>
            // Same staleness guard as the entity's fold. The view is fed the entity's
            // journal in order, so this only fires for an observation the entity itself
            // recorded — but repeating it keeps the row derivable from the events alone.
            if generation < row.generation then effects.ignore()
            else
              effects.updateRow(
                row.copy(
                  lifecycle = lifecycle,
                  readyInstances = ready,
                  desiredInstances = desired,
                  detail = detail
                )
              )

          case ServiceDeleted =>
            // Unlike the entity, which keeps a tombstone for the audit trail, the row
            // goes: `services list` should show what exists now.
            effects.deleteRow()

object ServiceRows
    extends View.Companion[ServiceRowsView, ServiceEvent, ServiceStatus](
      componentId = ComponentId("service-rows"),
      source = ChangeSource.eventsOf(ServiceEntity),
      rowSerializer = Codecs.serializer[ServiceStatus]("service-status")
    ):
  def create(ctx: ViewComponentContext) = new ServiceRowsView
