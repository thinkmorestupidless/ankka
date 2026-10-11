package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.Grantee
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution, ReceivedGrant}
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import java.time.Instant

/**
 * Lapses the grants a deleted grantee held or was offered (feature 040): each live one, on the
 * project that made it, with the attribution of whoever deleted the grantee. Lapsing is idempotent
 * on a grant already ended, so a redelivered deletion lapses nothing twice; a granting project that
 * is gone has nothing to lapse.
 */
object GrantLapse:

  def lapse(
      client: ComponentClient,
      received: Vector[ReceivedGrant],
      grantee: Grantee,
      actor: Option[Actor],
      at: Option[Instant]
  ): Unit =
    val by = Attribution.from(actor, at).fold(Metadata.empty)(_.metadata)
    received.filter(g => g.grantee == grantee && g.state.live).foreach { g =>
      try
        client
          .forEventSourcedEntity(EntityId(g.grantingProject))
          .call(ProjectEntity.lapseGrant)
          .withMetadata(by)
          .invoke(g.id): Unit
      catch case e: CommandError if e.code == ErrorCode.NotFound => ()
    }
