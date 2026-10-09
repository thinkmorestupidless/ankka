package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.Grantee
import com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
import com.thinkmorestupidless.ankka.controlplane.domain.MachineEvent
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Keeps a registered machine's `AnkkaMachine` as the machine is (feature 040): written when it is
 * registered or its byte rates change, removed when it is deleted, from which the operator gives it
 * its user on the installation's broker.
 *
 * As `ProjectionTrigger` does for a service: the event says only which machine to look at, and the
 * machine as it is now is what is written, so a redelivery writes nothing new and an event
 * delivered after a later one cannot undo it. A write the cluster refuses throws, and the event is
 * delivered again with the projection's backoff.
 */
final class MachineLifecycleTrigger(projector: ServiceProjector, client: ComponentClient)
    extends Consumer[MachineEvent, Nothing]:

  def onMessage(event: MachineEvent): Effect =
    val key = messageContext.subject
    key.split('/') match
      case Array(organizationId, name) =>
        event match
          // A deleted machine's grants lapse, so a machine registered again under its name is new.
          case MachineEvent.MachineDeleted(actor, at) =>
            val received =
              try
                client
                  .forEventSourcedEntity(EntityId(organizationId))
                  .call(OrganizationEntity.receivedGrants)
                  .invoke()
              catch case e: CommandError if e.code == ErrorCode.NotFound => Vector.empty
            GrantLapse.lapse(client, received, Grantee.Machine(organizationId, name), actor, at)
          case _ => ()
        val machine =
          try Some(client.forEventSourcedEntity(EntityId(key)).call(MachineEntity.get).invoke())
          catch case e: CommandError if e.code == ErrorCode.NotFound => None
        projector.projectMachine(organizationId, name, machine)
      case _ => ()
    effects.ignore()

object MachineLifecycleTrigger:

  def companion(
      projector: ServiceProjector
  ): Consumer.Companion[MachineLifecycleTrigger, MachineEvent, Nothing] =
    new Consumer.Companion[MachineLifecycleTrigger, MachineEvent, Nothing](
      componentId = ComponentId("machine-lifecycle-trigger"),
      source = ChangeSource.eventsOf(MachineEntity)
    ):
      def create(ctx: ConsumerContext): MachineLifecycleTrigger =
        new MachineLifecycleTrigger(projector, ctx.componentClient)
