package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
import com.thinkmorestupidless.ankka.controlplane.domain.{Attribution, OrganizationEvent}
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Suspends every service in an organization the moment it is disabled, and reinstates them the
 * moment it is enabled (feature 008, FR-033, research R10).
 *
 * The latency half of a pair, exactly like `ProjectionTrigger`: this consumer acts on the event as
 * soon as it is journaled; the projector's sweep is the correctness half, which also catches the
 * service whose row had not reached the listing view when this ran. Both are idempotent, so
 * at-least-once delivery and the overlap between them are harmless.
 *
 * The actor on the resulting service events is the administrator who disabled the organization: the
 * event carries them, and the trigger passes them on.
 */
final class SuspensionTrigger(projector: ServiceProjector)
    extends Consumer[OrganizationEvent, Nothing]:

  def onMessage(event: OrganizationEvent): Effect =
    event match
      case OrganizationDisabled(actor, at) =>
        projector.organizationDisabled(messageContext.subject, Attribution.from(actor, at))
      case OrganizationEnabled(actor, at) =>
        projector.organizationEnabled(messageContext.subject, Attribution.from(actor, at))
      case _ => ()
    effects.ignore()

object SuspensionTrigger:
  def companion(
      projector: ServiceProjector
  ): Consumer.Companion[SuspensionTrigger, OrganizationEvent, Nothing] =
    new Consumer.Companion[SuspensionTrigger, OrganizationEvent, Nothing](
      componentId = ComponentId("suspension-trigger"),
      source = ChangeSource.eventsOf(OrganizationEntity)
    ):
      def create(ctx: ConsumerContext): SuspensionTrigger = new SuspensionTrigger(projector)
