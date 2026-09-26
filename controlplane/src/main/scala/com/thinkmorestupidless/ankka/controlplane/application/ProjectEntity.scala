package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{
  CreateProject,
  ProjectDetail,
  RegistrySummary
}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A project: the scope a service name is unique within.
 *
 * The parent organization is validated by the endpoint rather than here. An entity can only see its
 * own state, and a command handler that called out to another entity to check a precondition would
 * be making that check non-atomic anyway — so the check belongs where the request arrives, once.
 */
final class ProjectEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Project, ProjectEvent]:

  def emptyState: Project = Project(context.entityId, "", "")

  def applyEvent(event: ProjectEvent): Project = event match
    case ProjectCreated(name, organizationId, _, _) => currentState.onCreated(name, organizationId)
    case ProjectRenamed(name, _, _)                 => currentState.onRenamed(name)
    case _: ProjectDeleted                          => currentState.onDeleted
    case RegistryConfigured(server, username, secretName, actor, at) =>
      currentState.onRegistryConfigured(server, username, secretName, actor, at)
    case _: RegistryCleared => currentState.onRegistryCleared

  def create(request: CreateProject): Effect[Done] =
    if currentState.deleted then
      effects.error(
        s"project '${context.entityId}' was deleted; its id is not reused",
        ErrorCode.Conflict
      )
    else if currentState.known then
      effects.error(s"project '${context.entityId}' already exists", ErrorCode.Conflict)
    else if request.name.isEmpty then effects.error("project name must not be empty")
    else if request.organizationId.isEmpty then effects.error("project needs an organization")
    else
      effects
        .persist(ProjectCreated(request.name, request.organizationId, actor, at))
        .thenReply(_ => Done)

  def rename(name: String): Effect[Done] =
    if !currentState.exists then notFound
    else if name.isEmpty then effects.error("project name must not be empty")
    else effects.persist(ProjectRenamed(name, actor, at)).thenReply(_ => Done)

  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(ProjectDeleted(actor, at)).thenReply(_ => Done)

  /**
   * Record a registry credential the cluster already holds.
   *
   * The Secret is written before this is called, and a failure there stops the sequence — so this
   * never records a credential the cluster does not have. It is idempotent by construction: a
   * second call for the same server simply replaces the reference.
   */
  def configureRegistry(request: ConfigureRegistry): Effect[Done] =
    if !currentState.exists then notFound
    else if request.server.isEmpty then effects.error("registry server must not be empty")
    else if request.username.isEmpty then effects.error("registry username must not be empty")
    else if request.secretName.isEmpty then effects.error("a registry needs a secret to name")
    else
      effects
        .persist(
          RegistryConfigured(request.server, request.username, request.secretName, actor, at)
        )
        .thenReply(_ => Done)

  /**
   * Stop claiming a registry. The Secret stays in the cluster: the control plane holds no `delete`
   * on secrets, and one nothing references is inert.
   */
  def clearRegistry: Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.registry.isEmpty then
      effects.error(s"project '${context.entityId}' has no registry", ErrorCode.NotFound)
    else effects.persist(RegistryCleared(actor, at)).thenReply(_ => Done)

  def get: ReadOnlyEffect[ProjectDetail] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        ProjectDetail(
          currentState.id,
          currentState.name,
          currentState.organizationId,
          currentState.registry.map(r =>
            RegistrySummary(r.server, r.username, r.setAt, r.setBy.flatMap(_.display))
          )
        )
      )

  /** What a projection needs: the reference itself, secret name included, or nothing. */
  def registry: ReadOnlyEffect[Option[RegistryRef]] = effects.reply(currentState.registry)

  def exists: ReadOnlyEffect[Boolean] = effects.reply(currentState.exists)

  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  private def at: Option[java.time.Instant]    = attribution.map(_.at)

  private def notFoundMessage = s"no such project '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object ProjectEntity
    extends EventSourcedEntity.Companion[ProjectEntity, Project, ProjectEvent](
      componentId = ComponentId("project"),
      stateSerializer = Codecs.serializer[Project]("project"),
      eventSerializer = Codecs.serializer[ProjectEvent]("project-event")
    ):

  given Serializer[CreateProject]       = Codecs.serializer[CreateProject]("create-project")
  given Serializer[ProjectDetail]       = Codecs.serializer[ProjectDetail]("project-detail")
  given Serializer[ConfigureRegistry]   = Codecs.serializer[ConfigureRegistry]("configure-registry")
  given Serializer[Option[RegistryRef]] = Codecs.serializer[Option[RegistryRef]]("registry-ref")

  def create(context: EventSourcedEntityContext) = new ProjectEntity(context)

  val createProject = command("create")(_.create)
  val rename        = command("rename")(_.rename)
  val delete        = command("delete")(_.delete)
  val get           = query("get")(_.get)
  val exists        = query("exists")(_.exists)
  val registry      = query("registry")(_.registry)

  val configureRegistry = command("configure-registry")(_.configureRegistry)
  val clearRegistry     = command("clear-registry")(_.clearRegistry)
