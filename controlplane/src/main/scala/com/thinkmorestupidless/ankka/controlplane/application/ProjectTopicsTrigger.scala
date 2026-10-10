package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.{
  ProjectBrokerDeclared,
  ProjectBrokerRemoved,
  ProjectSecretEntriesSet,
  ProjectSecretEntryRemoved,
  ProjectLocationSet,
  ProjectTopicDeclared,
  ProjectTopicRemoved
}
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Writes a project's declarations to the cluster when they change: its topics and brokers (feature
 * 027), and its secrets' names and entries, which the cloud provider's sync reads (feature 038).
 *
 * As `ProjectionTrigger` does for a service: the event says only which project to look at, and the
 * project's current declarations are what is written, so a redelivery writes nothing new. A write
 * the cluster refuses throws, and the projection delivers the event again with its backoff, so a
 * declaration reaches the cluster even across an outage.
 */
final class ProjectTopicsTrigger(projector: ServiceProjector)
    extends Consumer[ProjectEvent, Nothing]:

  def onMessage(event: ProjectEvent): Effect = event match
    case _: ProjectTopicDeclared | _: ProjectTopicRemoved | _: ProjectBrokerDeclared |
        _: ProjectBrokerRemoved | _: ProjectSecretEntriesSet | _: ProjectSecretEntryRemoved |
        _: ProjectLocationSet =>
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
