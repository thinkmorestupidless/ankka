package nakka.controlplane.application

import nakka.controlplane.api.{CreateProject, ProjectDetail}
import nakka.controlplane.domain.*
import nakka.controlplane.domain.ProjectEvent.*
import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

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
    case ProjectCreated(name, organizationId) => currentState.onCreated(name, organizationId)
    case ProjectRenamed(name)                 => currentState.onRenamed(name)
    case ProjectDeleted                       => currentState.onDeleted

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
        .persist(ProjectCreated(request.name, request.organizationId))
        .thenReply(_ => Done)

  def rename(name: String): Effect[Done] =
    if !currentState.exists then notFound
    else if name.isEmpty then effects.error("project name must not be empty")
    else effects.persist(ProjectRenamed(name)).thenReply(_ => Done)

  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(ProjectDeleted).thenReply(_ => Done)

  def get: ReadOnlyEffect[ProjectDetail] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        ProjectDetail(currentState.id, currentState.name, currentState.organizationId)
      )

  def exists: ReadOnlyEffect[Boolean] = effects.reply(currentState.exists)

  private def notFoundMessage = s"no such project '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object ProjectEntity
    extends EventSourcedEntity.Companion[ProjectEntity, Project, ProjectEvent](
      componentId = ComponentId("project"),
      stateSerializer = Codecs.serializer[Project]("project"),
      eventSerializer = Codecs.serializer[ProjectEvent]("project-event")
    ):

  given Serializer[CreateProject] = Codecs.serializer[CreateProject]("create-project")
  given Serializer[ProjectDetail] = Codecs.serializer[ProjectDetail]("project-detail")

  def create(context: EventSourcedEntityContext) = new ProjectEntity(context)

  val createProject = command("create")(_.create)
  val rename        = command("rename")(_.rename)
  val delete        = command("delete")(_.delete)
  val get           = query("get")(_.get)
  val exists        = query("exists")(_.exists)
