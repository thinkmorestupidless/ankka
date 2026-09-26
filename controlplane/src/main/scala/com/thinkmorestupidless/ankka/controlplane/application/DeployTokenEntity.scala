package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A deploy token, event sourced.
 *
 * An entity of its own rather than a map on `Organization`, for two reasons. Every control plane
 * node replays *this* journal into an in-memory index so its ACL can verify a token without
 * touching the database, and that journal should be tokens and nothing else. And recording a
 * token's use would otherwise be a write on the organization's journal once per token per day,
 * mixed in with the tenancy changes its history is for.
 *
 * What it does not decide is who may create or revoke one: that is the endpoint's question,
 * answered from the organization the token belongs to. The entity is told the actor and records it.
 */
final class DeployTokenEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[DeployToken, DeployTokenEvent]:

  def emptyState: DeployToken = DeployToken.empty(context.entityId)

  def applyEvent(event: DeployTokenEvent): DeployToken = event match
    case DeployTokenCreated(organizationId, label, digest, expiresAt, actor, at) =>
      currentState.onCreated(organizationId, label, digest, expiresAt, actor, at)
    case DeployTokenUsed(date) => currentState.onUsed(date)
    case _: DeployTokenRevoked => currentState.onRevoked

  /**
   * Mints nothing: the endpoint generated the secret and hands over only what may be stored.
   *
   * A revoked id is refused rather than recreated. The id is 64 bits of `SecureRandom`, so this
   * cannot happen by accident — but a token id that could be reused would make an audit trail
   * ambiguous about which credential did what.
   */
  def create(request: RecordDeployToken): Effect[Done] =
    if currentState.revoked then
      effects.error(
        s"deploy token '${context.entityId}' was revoked; its id is not reused",
        ErrorCode.Conflict
      )
    else if currentState.known then
      effects.error(s"deploy token '${context.entityId}' already exists", ErrorCode.Conflict)
    else if request.organizationId.isEmpty then
      effects.error("a deploy token needs an organization")
    else if request.label.isEmpty then effects.error("deploy token label must not be empty")
    else if request.digest.isEmpty then effects.error("a deploy token needs a digest")
    else
      effects
        .persist(
          DeployTokenCreated(
            request.organizationId,
            request.label,
            request.digest,
            request.expiresAt,
            actor,
            at
          )
        )
        .thenReply(_ => Done)

  /**
   * Records that the token was used on `date`, if that is news.
   *
   * Every node's index touches its own copy on every request and a background task sends this at
   * most once a minute; several nodes racing on the same day must produce one event, not one each,
   * so a date that is not later than the recorded one replies without persisting. That guard is
   * what keeps a busy token from writing to its own journal forever.
   */
  def recordUse(date: java.time.LocalDate): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.lastUsed.exists(!_.isBefore(date)) then effects.reply(Done)
    else effects.persist(DeployTokenUsed(date)).thenReply(_ => Done)

  def revoke: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(DeployTokenRevoked(actor, at)).thenReply(_ => Done)

  def get: ReadOnlyEffect[DeployTokenDetail] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        DeployTokenDetail(
          id = currentState.id,
          organizationId = currentState.organizationId,
          label = currentState.label,
          subject = currentState.subject,
          createdBy = currentState.createdBy.flatMap(_.display),
          createdAt = currentState.createdAt,
          expiresAt = currentState.expiresAt,
          lastUsed = currentState.lastUsed
        )
      )

  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  private def at: Option[java.time.Instant]    = attribution.map(_.at)

  private def notFoundMessage = s"no such deploy token '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object DeployTokenEntity
    extends EventSourcedEntity.Companion[DeployTokenEntity, DeployToken, DeployTokenEvent](
      componentId = ComponentId("deploy-token"),
      stateSerializer = Codecs.serializer[DeployToken]("deploy-token"),
      eventSerializer = Codecs.serializer[DeployTokenEvent]("deploy-token-event")
    ):

  given Serializer[RecordDeployToken] = Codecs.serializer[RecordDeployToken]("record-deploy-token")
  given Serializer[DeployTokenDetail] = Codecs.serializer[DeployTokenDetail]("deploy-token-detail")
  given Serializer[java.time.LocalDate] = Codecs.serializer[java.time.LocalDate]("local-date")

  def create(context: EventSourcedEntityContext) = new DeployTokenEntity(context)

  val createToken = command("create")(_.create)
  val recordUse   = command("record-use")(_.recordUse)
  val revoke      = command("revoke")(_.revoke)
  val get         = query("get")(_.get)
