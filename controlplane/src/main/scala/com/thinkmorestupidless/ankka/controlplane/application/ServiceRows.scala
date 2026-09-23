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
          case ServiceApplied(_, descriptor, generation, _, _) =>
            effects.updateRow(
              row.copy(
                image = descriptor.service.image,
                hosting = descriptor.service.hosting,
                protocol = descriptor.service.protocol,
                generation = generation,
                lifecycle =
                  if row.paused then ServiceLifecycle.Paused
                  else ServiceLifecycle.UpdateInProgress,
                detail = None,
                confirmed = true
              )
            )

          case ServiceRestarted(generation, _, _) =>
            effects.updateRow(
              row.copy(
                generation = generation,
                lifecycle = ServiceLifecycle.UpdateInProgress,
                readyInstances = 0,
                detail = None,
                confirmed = true
              )
            )

          case _: ServicePaused =>
            effects.updateRow(
              row.copy(
                lifecycle = ServiceLifecycle.Paused,
                paused = true,
                desiredInstances = 0,
                detail = None,
                confirmed = true
              )
            )

          case _: ServiceResumed =>
            effects.updateRow(
              row.copy(
                lifecycle =
                  if row.suspended then ServiceLifecycle.Suspended
                  else ServiceLifecycle.UpdateInProgress,
                paused = false,
                detail = None,
                confirmed = true
              )
            )

          // The row carries the boolean only; the endpoint adds the hostname on the way out, since
          // the base domain is configuration the view does not have.
          case _: ServiceExposed   => effects.updateRow(row.copy(exposed = true))
          case _: ServiceUnexposed => effects.updateRow(row.copy(exposed = false))

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
                  // The same rule as the entity's fold: what the members and the organization
                  // asked for wins over what the operator reported — and the row carries the
                  // members' choice itself, because an operator *reports* Paused too, and a stale
                  // one landing after a resume must not read as a pause nobody asked for.
                  lifecycle =
                    if row.paused then ServiceLifecycle.Paused
                    else if row.suspended then ServiceLifecycle.Suspended
                    else lifecycle,
                  readyInstances = ready,
                  desiredInstances = desired,
                  detail = detail,
                  confirmed = confirmed,
                  database = database
                )
              )

          case _: ServiceSuspended =>
            effects.updateRow(
              row.copy(
                lifecycle =
                  if row.paused then ServiceLifecycle.Paused else ServiceLifecycle.Suspended,
                desiredInstances = 0,
                detail = None,
                confirmed = true,
                suspended = true
              )
            )

          case _: ServiceReinstated =>
            effects.updateRow(
              row.copy(
                lifecycle =
                  if row.paused then ServiceLifecycle.Paused else ServiceLifecycle.UpdateInProgress,
                detail = None,
                confirmed = true,
                suspended = false
              )
            )

          case _: ServiceDeleted =>
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
