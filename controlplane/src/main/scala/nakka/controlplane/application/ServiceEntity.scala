package nakka.controlplane.application

import nakka.controlplane.api.*
import nakka.controlplane.domain.*
import nakka.controlplane.domain.ServiceEvent.*
import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

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

  def applyEvent(event: ServiceEvent): Service = event match
    case ServiceApplied(_, descriptor, generation) => currentState.onApplied(descriptor, generation)
    case ServiceRestarted(generation)              => currentState.onRestarted(generation)
    case ServicePaused                             => currentState.onPaused
    case ServiceResumed                            => currentState.onResumed
    case observed: ServiceObserved                 => currentState.onObserved(observed)
    case ServiceDeleted                            => currentState.onDeleted

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
              ServiceApplied(key.projectId, request.descriptor, currentState.generation + 1)
            )
            .thenReply(_.toStatus)

  def restart: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if currentState.isPaused then
      effects.error(s"service '${key.name}' is paused; resume it first", ErrorCode.Conflict)
    else effects.persist(ServiceRestarted(currentState.generation + 1)).thenReply(_.toStatus)

  def pause: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if currentState.isPaused then effects.reply(currentState.toStatus)
    else effects.persist(ServicePaused).thenReply(_.toStatus)

  def resume: Effect[ServiceStatus] =
    if !currentState.exists then notFound
    else if !currentState.isPaused then effects.reply(currentState.toStatus)
    else effects.persist(ServiceResumed).thenReply(_.toStatus)

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
    else effects.persist(ServiceDeleted).thenReply(_ => Done)

  def get: ReadOnlyEffect[ServiceStatus] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.toStatus)

  /**
   * Everything the projector needs, in one call. Absent once the service is deleted.
   *
   * The whole state rather than just the descriptor: projecting needs the generation and whether
   * the service is paused as well, and two round trips per service per sweep is a cost with nothing
   * to show for it. `None` is the authoritative "this should not exist in the cluster".
   */
  def desiredState: ReadOnlyEffect[Option[Service]] =
    effects.reply(Option.when(currentState.exists)(currentState))

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

  def create(context: EventSourcedEntityContext) = new ServiceEntity(context)

  val applyDescriptor = command("apply")(_.apply)
  val restart         = command("restart")(_.restart)
  val pause           = command("pause")(_.pause)
  val resume          = command("resume")(_.resume)
  val observe         = command("observe")(_.observe)
  val delete          = command("delete")(_.delete)
  val get             = query("get")(_.get)
  val desiredState    = query("desired")(_.desiredState)
