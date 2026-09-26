package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{ProjectDetail, RegistrySummary}
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** Every project, queryable by organization. */
final class ProjectRowsView extends View[ProjectEvent, ProjectDetail]:

  def onChange(event: ProjectEvent): Effect = event match
    case ProjectCreated(name, organizationId, _, _) =>
      effects.updateRow(ProjectDetail(updateContext.subject, name, organizationId))

    case ProjectRenamed(name, _, _) =>
      rowState match
        case Some(row) => effects.updateRow(row.copy(name = name))
        case None      => effects.ignore()

    case _: ProjectDeleted => effects.deleteRow()

    // A listing says which registry a project pulls from, so `projects list` answers the question
    // without a call per project. Never the password — the row is built from the event, and the
    // event does not have one.
    case RegistryConfigured(server, username, _, actor, at) =>
      rowState match
        case Some(row) =>
          effects.updateRow(
            row.copy(registry =
              Some(RegistrySummary(server, username, at, actor.flatMap(_.display)))
            )
          )
        case None => effects.ignore()

    case _: RegistryCleared =>
      rowState match
        case Some(row) => effects.updateRow(row.copy(registry = None))
        case None      => effects.ignore()

object ProjectRows
    extends View.Companion[ProjectRowsView, ProjectEvent, ProjectDetail](
      componentId = ComponentId("project-rows"),
      source = ChangeSource.eventsOf(ProjectEntity),
      rowSerializer = Codecs.serializer[ProjectDetail]("project-detail")
    ):
  def create(ctx: ViewComponentContext) = new ProjectRowsView
