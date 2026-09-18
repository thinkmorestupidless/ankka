package nakka.controlplane.application

import nakka.controlplane.deploy.ServiceProjector
import nakka.controlplane.domain.{ServiceEvent, ServiceKey}
import nakka.core.ComponentId
import nakka.sdk.*

/**
 * Projects a service the moment its desired state changes.
 *
 * The sweep alone would converge everything eventually, but "eventually" would be the sweep
 * interval, and an operator watching `services list` after an apply should not be waiting on a
 * timer. This is the latency half of the trigger pair; the sweep is the correctness half, and is
 * the only thing that can notice what nobody announced.
 *
 * Rides the existing projection machinery, so delivery is ordered per entity, at-least-once, and
 * has a durable offset. At-least-once is free here: projecting is level-triggered and idempotent,
 * so a redelivery re-reads the same state and writes nothing.
 */
final class ProjectionTrigger(projector: ServiceProjector) extends Consumer[ServiceEvent, Nothing]:

  def onMessage(event: ServiceEvent): Effect =
    // The event itself is ignored beyond identifying the service. Reading the entity's
    // current state instead of trusting the event is what makes a duplicate harmless and a
    // reordering impossible to act on wrongly.
    val _ = event
    ServiceKey.parse(messageContext.subject).foreach(projector.project)
    effects.ignore()

  /** A deleted service still needs its resource removed, and `projectOne` handles that. */
  override def onDelete: Effect =
    ServiceKey.parse(messageContext.subject).foreach(projector.project)
    effects.ignore()

object ProjectionTrigger:

  /**
   * A companion instance rather than an object, so the projector can be handed in.
   *
   * nakka has no container: a component is built by its own companion and anything it needs arrives
   * that way. The projector is created when the service is assembled, so the companion that closes
   * over it has to be created then too.
   */
  def companion(
      projector: ServiceProjector
  ): Consumer.Companion[ProjectionTrigger, ServiceEvent, Nothing] =
    new Consumer.Companion[ProjectionTrigger, ServiceEvent, Nothing](
      componentId = ComponentId("projection-trigger"),
      source = ChangeSource.eventsOf(ServiceEntity)
    ):
      def create(ctx: ConsumerContext): ProjectionTrigger = new ProjectionTrigger(projector)
