package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.controlplane.api.Role
import com.thinkmorestupidless.ankka.controlplane.application.{OrganizationEntity, OrganizationRows}
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution, ClaimInvitation}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{EndpointClients, Principal}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.jsonContains

import java.time.Clock

/**
 * Turns a pending invitation into a membership the first time its owner shows up (FR-017) — without
 * a Keycloak credential, and without a query on every request (research R9).
 *
 * Two moments, and only two: when a membership check is about to refuse (one extra entity call on a
 * path that was failing anyway), and when the caller lists their organizations or asks who they are
 * (a view query that is the listing's own). A member's ordinary request costs nothing extra. Only a
 * *verified* email claims; an unverified one is never consulted.
 */
final class InvitationClaim(clients: EndpointClients, clock: Clock):

  private val rows = clients.viewClient.forView(OrganizationRows)

  private def verifiedEmail(principal: Principal): Option[String] =
    principal.email.filter(_ => principal.emailVerified)

  /**
   * The role claimed in `organizationId`, if an invitation for the caller's verified email was
   * pending.
   */
  def claimIfPending(principal: Principal, organizationId: String): Option[Role] =
    verifiedEmail(principal).flatMap { email =>
      val organization = clients.componentClient.forEventSourcedEntity(EntityId(organizationId))
      organization.call(OrganizationEntity.pendingFor).invoke(email).flatMap { _ =>
        val by = Attribution(
          Actor(principal.subject, Some(Principals.display(principal))),
          clock.instant()
        )
        organization
          .call(OrganizationEntity.claimInvitation)
          .withMetadata(by.metadata)
          .invoke(ClaimInvitation(principal.subject, email, Some(Principals.display(principal))))
      }
    }

  /** Every organization that has invited the caller's verified email, claimed. Before a listing. */
  def claimAll(principal: Principal): Unit =
    verifiedEmail(principal).foreach { email =>
      rows
        .where(jsonContains("invitations", email))
        .foreach(row => claimIfPending(principal, row.id): Unit)
    }
