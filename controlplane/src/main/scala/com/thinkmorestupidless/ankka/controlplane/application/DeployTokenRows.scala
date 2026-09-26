package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.domain.DeployToken
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.{Instant, LocalDate}

/**
 * One row per **live** deploy token, so an owner can list their organization's tokens without
 * opening an entity per token.
 *
 * A revoked token deletes its row rather than carrying a flag. The listing is "what can currently
 * be used", and a revoked credential is not a thing an owner needs to keep seeing — the journal is
 * where its life is recorded, and `services history` is where its work is.
 *
 * The digest is not here, and neither is anything derived from the secret.
 */
final case class DeployTokenRow(
    id: String,
    organizationId: String = "",
    label: String = "",
    createdBy: Option[String] = None,
    createdAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    lastUsed: Option[LocalDate] = None
):
  def subject: String = DeployToken.subjectOf(id)

final class DeployTokenRowsView extends View[DeployTokenEvent, DeployTokenRow]:

  private def row = rowState.getOrElse(DeployTokenRow(updateContext.subject))

  def onChange(event: DeployTokenEvent): Effect = event match
    case DeployTokenCreated(organizationId, label, _, expiresAt, actor, at) =>
      effects.updateRow(
        row.copy(
          organizationId = organizationId,
          label = label,
          createdBy = actor.flatMap(_.display),
          createdAt = at,
          expiresAt = expiresAt
        )
      )
    case DeployTokenUsed(date) => effects.updateRow(row.copy(lastUsed = Some(date)))
    case _: DeployTokenRevoked => effects.deleteRow()

object DeployTokenRows
    extends View.Companion[DeployTokenRowsView, DeployTokenEvent, DeployTokenRow](
      componentId = ComponentId("deploy-token-rows"),
      source = ChangeSource.eventsOf(DeployTokenEntity),
      rowSerializer = Codecs.serializer[DeployTokenRow]("deploy-token-row")
    ):
  def create(ctx: ViewComponentContext) = new DeployTokenRowsView
