package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Every service, queryable by project.
 *
 * `ServiceEntity` can only be found by `projectId/name`, which is exactly the wrong shape for
 * `ankka services list` — the CLI's most-used command asks "what is in this project", a question no
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
                detail = None,
                confirmed = true
              )
            )

          case ServiceRestarted(generation) =>
            effects.updateRow(
              row.copy(
                generation = generation,
                lifecycle = ServiceLifecycle.UpdateInProgress,
                readyInstances = 0,
                detail = None,
                confirmed = true
              )
            )

          case ServicePaused =>
            effects.updateRow(
              row.copy(
                lifecycle = ServiceLifecycle.Paused,
                desiredInstances = 0,
                detail = None,
                confirmed = true
              )
            )

          case ServiceResumed =>
            effects.updateRow(
              row.copy(
                lifecycle = ServiceLifecycle.UpdateInProgress,
                detail = None,
                confirmed = true
              )
            )

          // The row carries the boolean only; the endpoint adds the hostname on the way out, since
          // the base domain is configuration the view does not have.
          case ServiceExposed   => effects.updateRow(row.copy(exposed = true))
          case ServiceUnexposed => effects.updateRow(row.copy(exposed = false))

          case ServiceObserved(
                generation,
                lifecycle,
                ready,
                desired,
                detail,
                confirmed,
                database
              ) =>
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
                  detail = detail,
                  confirmed = confirmed,
                  database = database
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
