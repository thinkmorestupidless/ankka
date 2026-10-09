package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.{
  GrantAccepted,
  GrantDeclined,
  GrantLapsed,
  GrantMade,
  GrantRelinquished,
  GrantRevoked,
  GrantWithdrawn,
  ProjectBrokerDeclared,
  ProjectBrokerRemoved,
  ProjectCreated,
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
    case _: ProjectTopicDeclared | _: ProjectTopicRemoved | _: ProjectBrokerDeclared |
        _: ProjectBrokerRemoved =>
      projector.projectTopics(messageContext.subject)
      effects.ignore()
    // Every change to a grant changes what the project's resource lists (feature 040): an
    // accepted grant joins it, an ended one leaves it, and a pending one changes nothing written,
    // which the projector's equal-spec check turns into no write at all.
    case _: GrantMade | _: GrantAccepted | _: GrantDeclined | _: GrantWithdrawn | _: GrantRevoked |
        _: GrantRelinquished | _: GrantLapsed =>
      projector.projectTopics(messageContext.subject)
      effects.ignore()
    // A new project has its resource from the start (feature 040): the ConfigMap the operator
    // renders from it tells every service of the project where machines' tokens come from, which
    // a machine calling a route that authenticates needs before any grant is made.
    // A creation writes only what a project has from the start, which its first grant or topic
    // writes again: one the projector cannot write yet is passed over rather than retried, so a
    // control plane with no cluster behind it is not stalled by it.
    case _: ProjectCreated =>
      if projector.ready then projector.projectTopics(messageContext.subject)
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
