package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.{
  ProjectBrokerDeclared,
  ProjectBackupCredentialReissued,
  ProjectBrokerRemoved,
  ProjectLocationSet,
  ProjectDatabaseSet,
  ProjectRehearsalEnded,
  ProjectRehearsalRequested,
  ProjectRestoreRequested,
  ProjectTopicDeclared,
  ProjectTopicRemoved
}
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Writes a project's declared topics to the cluster when they change (feature 027).
 *
 * As `ProjectionTrigger` does for a service: the event says only which project to look at, and the
 * project's current declarations are what is written, so a redelivery writes nothing new. A write
 * the cluster refuses throws, and the projection delivers the event again with its backoff, so a
 * declaration reaches the cluster even across an outage.
 */
final class ProjectTopicsTrigger(projector: ServiceProjector)
    extends Consumer[ProjectEvent, Nothing]:

  def onMessage(event: ProjectEvent): Effect = event match
    // A restore asked for (feature 041) is written as the topics are: the operator makes it.
    case _: ProjectTopicDeclared | _: ProjectTopicRemoved | _: ProjectBrokerDeclared |
        _: ProjectBrokerRemoved | _: ProjectRestoreRequested | _: ProjectDatabaseSet |
        _: ProjectRehearsalRequested | _: ProjectRehearsalEnded |
        _: ProjectBackupCredentialReissued | _: ProjectLocationSet =>
      projector.projectTopics(messageContext.subject)
      effects.ignore()
    case _ => effects.ignore()

object ProjectTopicsTrigger:

  def companion(
      projector: ServiceProjector
  ): Consumer.Companion[ProjectTopicsTrigger, ProjectEvent, Nothing] =
    new Consumer.Companion[ProjectTopicsTrigger, ProjectEvent, Nothing](
      componentId = ComponentId("project-topics-trigger"),
      source = ChangeSource.eventsOf(ProjectEntity)
    ):
      def create(ctx: ConsumerContext): ProjectTopicsTrigger = new ProjectTopicsTrigger(projector)
