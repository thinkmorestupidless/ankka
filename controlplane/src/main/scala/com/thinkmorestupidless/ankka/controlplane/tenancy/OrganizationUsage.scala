package com.thinkmorestupidless.ankka.controlplane.tenancy

import com.thinkmorestupidless.ankka.controlplane.application.OrganizationEntity
import com.thinkmorestupidless.ankka.controlplane.domain.{RecordService, ReserveService}
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.EndpointClients
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal

/**
 * The organization's usage bookkeeping, from the endpoints that create and delete what it counts
 * (feature 015).
 *
 * The sequence is reserve first, then create or apply, then give back on failure: the organization
 * is asked for the capacity before the thing exists, so a refusal creates nothing and two callers
 * racing for the last slot are serialized by the entity. What the reservation replies — whether a
 * project slot was newly taken, what a service previously counted — is exactly what `undo` needs,
 * and nothing more is remembered anywhere.
 *
 * An undo that fails is logged and swallowed: the caller's failure is the one they need to see, and
 * a count left stale is repaired by the next `quota set`, which makes the record what exists.
 */
final class OrganizationUsage(clients: EndpointClients):

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.quotas")

  /** Whether the slot was newly taken; `false` for a project the organization already counts. */
  def reserveProject(organizationId: String, projectId: String, by: Metadata): Boolean =
    organization(organizationId)
      .call(OrganizationEntity.reserveProject)
      .withMetadata(by)
      .invoke(projectId)

  def releaseProject(organizationId: String, projectId: String, by: Metadata): Unit =
    organization(organizationId)
      .call(OrganizationEntity.releaseProject)
      .withMetadata(by)
      .invoke(projectId): Unit

  /** The instance count previously recorded for the service; `None` for a new one. */
  def reserveService(
      organizationId: String,
      key: String,
      instances: Int,
      by: Metadata
  ): Option[Int] =
    organization(organizationId)
      .call(OrganizationEntity.reserveService)
      .withMetadata(by)
      .invoke(ReserveService(key, instances))

  /** Unchecked: `Some(n)` records the service at `n` whatever the quota, `None` removes it. */
  def recordService(
      organizationId: String,
      key: String,
      instances: Option[Int],
      by: Metadata
  ): Unit =
    organization(organizationId)
      .call(OrganizationEntity.recordService)
      .withMetadata(by)
      .invoke(RecordService(key, instances)): Unit

  /** Runs a give-back after the step it covered failed, without letting it mask that failure. */
  def undo(what: String)(release: => Unit): Unit =
    try release
    catch
      case NonFatal(failure) =>
        log.warn(
          s"could not $what after the request failed; the organization's usage is one over " +
            s"until its quota is next set: ${failure.getMessage}"
        )

  private def organization(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))
