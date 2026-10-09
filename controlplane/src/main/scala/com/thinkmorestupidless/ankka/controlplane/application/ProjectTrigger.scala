package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.Grantee
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.ProjectDeleted
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Lapses every grant a deleted project's services held or were offered (feature 040), on the
 * projects that made them, with the deleter's attribution: a project id is never reused, so nothing
 * could ever be granted by them again.
 */
final class ProjectTrigger(client: ComponentClient) extends Consumer[ProjectEvent, Nothing]:

  def onMessage(event: ProjectEvent): Effect =
    event match
      case ProjectDeleted(actor, at) =>
        val project = messageContext.subject
        val received =
          client
            .forEventSourcedEntity(EntityId(project))
            .call(ProjectEntity.receivedGrants)
            .invoke()
        received
          .map(_.grantee)
          .distinct
          .foreach {
            case grantee @ Grantee.Service(`project`, _) =>
              GrantLapse.lapse(client, received, grantee, actor, at)
            case _ => ()
          }
      case _ => ()
    effects.ignore()

object ProjectTrigger
    extends Consumer.Companion[ProjectTrigger, ProjectEvent, Nothing](
      componentId = ComponentId("project-trigger"),
      source = ChangeSource.eventsOf(ProjectEntity)
    ):
  def create(ctx: ConsumerContext): ProjectTrigger = new ProjectTrigger(ctx.componentClient)
