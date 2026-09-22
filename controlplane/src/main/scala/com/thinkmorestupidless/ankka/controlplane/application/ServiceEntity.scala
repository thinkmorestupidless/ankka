package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A service's desired state, and the last thing observed about it.
 *
 * This is the entity the whole control plane turns on. Every operator action is a command here;
 * every deployment is a workflow reading from here; the reconciler both reads the desired state and
 * reports observations back to it.
 *
 * Note what it does *not* do: it never talks to Kubernetes. An entity that performed I/O in a
 * command handler could not be replayed, and the point of persisting desired state separately from
 * acting on it is that the two can fail independently.
 */
final class ServiceEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Service, ServiceEvent]:

  private val key: ServiceKey =
    ServiceKey
      .parse(context.entityId)
      .getOrElse(
        // Unreachable through the endpoint, which builds the id from a parsed path. A
        // malformed id means a caller addressed sharding directly, and there is no
        // sensible state to serve.
        throw IllegalArgumentException(
          s"service entity id '${context.entityId}' is not 'projectId/name'"
        )
      )

  def emptyState: Service = Service.empty(key)

  def applyEvent(event: ServiceEvent): Service = Service.fold(currentState, event)

  /**
   * Applies a descriptor, creating the service if it is new.
   *
   * Deliberately idempotent-shaped but not idempotent: re-applying an unchanged descriptor still
   * bumps the generation, because an operator running `apply` again is usually asking for the image
   * tag to be re-pulled. `restart` exists for the case where that is all they want.
   */
  def apply(request: ApplyService): Effect[ServiceStatus] =
    if request.projectId != key.projectId then
      effects.error(
        s"descriptor targets project '${request.projectId}' but was applied to " +
          s"'${key.projectId}'"
      )
    else if request.descriptor.name != key.name then
      effects.error(
        s"descriptor names service '${request.descriptor.name}' but was applied to " +
          s"'${key.name}'"
      )
    else
      request.descriptor.problems match
        case problems if problems.nonEmpty =>
          // Every problem at once: an operator fixing a descriptor should not have to
          // re-apply once per mistake.
          effects.error(problems.mkString("invalid descriptor: ", "; ", ""))
        case _ =>
          effects
            .persist(
              ServiceApplied(
                key.projectId,
                request.descriptor,
                currentState.generation + 1,
                actor,
                at
              )
            )
            .thenReply(_.toStatus)

  def restart: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if currentState.isPaused then
      effects.error(s"service '${key.name}' is paused; resume it first", ErrorCode.Conflict)
    else
      effects
        .persist(ServiceRestarted(currentState.generation + 1, actor, at))
        .thenReply(_.toStatus)

  def pause: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if currentState.isPaused then effects.reply(currentState.toStatus)
    else effects.persist(ServicePaused(actor, at)).thenReply(_.toStatus)

  def resume: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if !currentState.isPaused then effects.reply(currentState.toStatus)
    else effects.persist(ServiceResumed(actor, at)).thenReply(_.toStatus)

  /**
   * Whether the service *may* be exposed — no HTTP, a hostname too long, a hostname another service
   * holds — is the endpoint's to decide: the first is a descriptor rule, the last a cross-entity
   * check. The entity records the decision.
   */
  def expose: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if currentState.exposed then effects.reply(currentState.toStatus)
    else effects.persist(ServiceExposed(actor, at)).thenReply(_.toStatus)

  def unexpose: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if !currentState.exposed then effects.reply(currentState.toStatus)
    else effects.persist(ServiceUnexposed(actor, at)).thenReply(_.toStatus)

  /**
   * The organization's decision, not the members' (feature 008). Idempotent: the trigger and the
   * sweep may both ask, and redelivery is at-least-once.
   */
  def suspend: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if currentState.suspended then effects.reply(currentState.toStatus)
    else effects.persist(ServiceSuspended(actor, at)).thenReply(_.toStatus)

  def reinstate: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if !currentState.suspended then effects.reply(currentState.toStatus)
    else effects.persist(ServiceReinstated(actor, at)).thenReply(_.toStatus)

  /**
   * Records what the reconciler saw.
   *
   * Two observations are dropped rather than persisted: one for a generation that has already been
   * superseded, and one that reports exactly what is already recorded. The second matters more than
   * it looks — the reconciler runs on a timer, so without it a steady-state service would grow its
   * journal forever.
   */
  def observe(observation: ServiceObservation): Effect[Done] =
    val event: ServiceObserved = ServiceObserved(
      observation.generation,
      observation.lifecycle,
      observation.readyInstances,
      observation.desiredInstances,
      observation.detail,
      observation.confirmed,
      observation.database
    )
    if !currentState.exists then effects.reply(Done)
    else if observation.generation < currentState.generation then effects.reply(Done)
    else if currentState.onObserved(event) == currentState then effects.reply(Done)
    else effects.persist(event).thenReply(_ => Done)

  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(ServiceDeleted(actor, at)).thenReply(_ => Done)

  def get: ReadOnlyEffect[ServiceStatus] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.toStatus)

  /** Who did what, newest first. A deleted service still answers: the history is the point. */
  def history: ReadOnlyEffect[Vector[HistoryEntry]] =
    if currentState.history.isEmpty && !currentState.exists then
      effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.history)

  /**
   * Everything the projector needs, in one call. Absent once the service is deleted.
   *
   * The whole state rather than just the descriptor: projecting needs the generation and whether
   * the service is paused as well, and two round trips per service per sweep is a cost with nothing
   * to show for it. `None` is the authoritative "this should not exist in the cluster".
   */
  def desiredState: ReadOnlyEffect[Option[Service]] =
    effects.reply(Option.when(currentState.exists)(currentState))

  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  private def at: Option[java.time.Instant]    = attribution.map(_.at)

  private def notFoundMessage = s"no such service '${key.name}' in project '${key.projectId}'"

  private def notFound[T] = effects.error[T](notFoundMessage, ErrorCode.NotFound)

object ServiceEntity
    extends EventSourcedEntity.Companion[ServiceEntity, Service, ServiceEvent](
      componentId = ComponentId("service"),
      stateSerializer = Codecs.serializer[Service]("service"),
      eventSerializer = Codecs.serializer[ServiceEvent]("service-event")
    ):

  given Serializer[ApplyService]  = Codecs.serializer[ApplyService]("apply-service")
  given Serializer[ServiceStatus] = Codecs.serializer[ServiceStatus]("service-status")
  given Serializer[ServiceObservation] =
    Codecs.serializer[ServiceObservation]("service-observation")

  given Serializer[Option[Service]] =
    Codecs.serializer[Option[Service]]("service-state-option")

  given Serializer[Vector[HistoryEntry]] =
    Codecs.serializer[Vector[HistoryEntry]]("service-history")

  def create(context: EventSourcedEntityContext) = new ServiceEntity(context)

  val applyDescriptor = command("apply")(_.apply)
  val restart         = command("restart")(_.restart)
  val pause           = command("pause")(_.pause)
  val resume          = command("resume")(_.resume)
  val expose          = command("expose")(_.expose)
  val unexpose        = command("unexpose")(_.unexpose)
  val observe         = command("observe")(_.observe)
  val delete          = command("delete")(_.delete)
  val suspend         = command("suspend")(_.suspend)
  val reinstate       = command("reinstate")(_.reinstate)
  val get             = query("get")(_.get)
  val history         = query("history")(_.history)
  val desiredState    = query("desired")(_.desiredState)
