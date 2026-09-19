package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.ProjectDetail
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** Every project, queryable by organization. */
final class ProjectRowsView extends View[ProjectEvent, ProjectDetail]:

  def onChange(event: ProjectEvent): Effect = event match
    case ProjectCreated(name, organizationId) =>
      effects.updateRow(ProjectDetail(updateContext.subject, name, organizationId))

    case ProjectRenamed(name) =>
      rowState match
        case Some(row) => effects.updateRow(row.copy(name = name))
        case None      => effects.ignore()

    case ProjectDeleted => effects.deleteRow()

object ProjectRows
    extends View.Companion[ProjectRowsView, ProjectEvent, ProjectDetail](
      componentId = ComponentId("project-rows"),
      source = ChangeSource.eventsOf(ProjectEntity),
      rowSerializer = Codecs.serializer[ProjectDetail]("project-detail")
    ):
  def create(ctx: ViewComponentContext) = new ProjectRowsView
