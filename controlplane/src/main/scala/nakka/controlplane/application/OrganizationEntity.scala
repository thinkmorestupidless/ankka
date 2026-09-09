package nakka.controlplane.application

import nakka.controlplane.api.OrganizationDetail
import nakka.controlplane.domain.*
import nakka.controlplane.domain.OrganizationEvent.*
import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

/**
 * An organization, event sourced.
 *
 * Tenancy is event sourced for one reason: an operator asking "who changed this, and to what" is
 * the normal case in a control plane, and a journal answers it without a separate audit trail to
 * keep in step.
 */
final class OrganizationEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Organization, OrganizationEvent]:

  def emptyState: Organization = Organization.empty(context.entityId)

  def applyEvent(event: OrganizationEvent): Organization = event match
    case OrganizationCreated(name) => currentState.onCreated(name)
    case OrganizationRenamed(name) => currentState.onRenamed(name)
    case OrganizationDeleted       => currentState.onDeleted

  def create(name: String): Effect[Done] =
    if currentState.deleted then
      effects.error(
        s"organization '${context.entityId}' was deleted; its id is not reused",
        ErrorCode.Conflict
      )
    else if currentState.known then
      effects.error(s"organization '${context.entityId}' already exists", ErrorCode.Conflict)
    else if name.isEmpty then effects.error("organization name must not be empty")
    else effects.persist(OrganizationCreated(name)).thenReply(_ => Done)

  def rename(name: String): Effect[Done] =
    if !currentState.exists then notFound
    else if name.isEmpty then effects.error("organization name must not be empty")
    else effects.persist(OrganizationRenamed(name)).thenReply(_ => Done)

  /**
   * Marks the organization deleted without removing the entity.
   *
   * The journal is the audit trail, so deletion is a tombstone rather than a `deleteEntity()`. It
   * also keeps the id from being silently reused by a later create, which for a tenancy boundary
   * would be the wrong default.
   */
  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(OrganizationDeleted).thenReply(_ => Done)

  def get: ReadOnlyEffect[OrganizationDetail] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(OrganizationDetail(currentState.id, currentState.name))

  def exists: ReadOnlyEffect[Boolean] = effects.reply(currentState.exists)

  private def notFoundMessage = s"no such organization '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object OrganizationEntity
    extends EventSourcedEntity.Companion[OrganizationEntity, Organization, OrganizationEvent](
      componentId = ComponentId("organization"),
      stateSerializer = Codecs.serializer[Organization]("organization"),
      eventSerializer = Codecs.serializer[OrganizationEvent]("organization-event")
    ):

  given Serializer[OrganizationDetail] =
    Codecs.serializer[OrganizationDetail]("organization-detail")

  def create(context: EventSourcedEntityContext) = new OrganizationEntity(context)

  val createOrganization = command("create")(_.create)
  val rename             = command("rename")(_.rename)
  val delete             = command("delete")(_.delete)
  val get                = query("get")(_.get)
  val exists             = query("exists")(_.exists)
