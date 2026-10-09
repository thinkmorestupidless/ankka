package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{GrantChange, Grantee}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, EntityId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Keeps the grantee side's copy of every grant (feature 040): each change a granting project makes
 * to a grant is recorded on the grantee project, for a service, or on the grantee organization, for
 * a machine, with the actor who made it.
 *
 * The granting project's events are the one record a command writes; this consumer derives the
 * other, so nothing is half-recorded when two entities would otherwise be written by one request.
 * Delivered at least once: a change already recorded is refused as a conflict, which this reads as
 * done, so a redelivery writes nothing twice. A grantee that no longer exists — a project never
 * created, an organization never created — has nowhere to record, and is passed over. Any other
 * failure throws, and the change is delivered again.
 */
final class GrantMirror(client: ComponentClient) extends Consumer[ProjectEvent, Nothing]:

  def onMessage(event: ProjectEvent): Effect =
    val granting = messageContext.subject
    event match
      case GrantMade(id, grantee, target, pending, actor, at) =>
        val change = if pending then GrantChange.Offered else GrantChange.Made
        record(granting, id, grantee, target, change, Attribution.from(actor, at))
        lapseIfGone(granting, id, grantee)
      case GrantAccepted(id, actor, at) => later(granting, id, GrantChange.Accepted, actor, at)
      case GrantDeclined(id, actor, at) => later(granting, id, GrantChange.Declined, actor, at)
      case GrantWithdrawn(id, actor, at) =>
        later(granting, id, GrantChange.Withdrawn, actor, at)
      case GrantRevoked(id, actor, at) => later(granting, id, GrantChange.Revoked, actor, at)
      case GrantRelinquished(id, actor, at) =>
        later(granting, id, GrantChange.Relinquished, actor, at)
      case GrantLapsed(id, actor, at) => later(granting, id, GrantChange.Lapsed, actor, at)
      case _                          => ()
    effects.ignore()

  /** A change after the first names only the grant; its grantee and target are the project's. */
  private def later(
      granting: String,
      id: String,
      change: GrantChange,
      actor: Option[Actor],
      at: Option[java.time.Instant]
  ): Unit =
    val grants = project(granting).call(ProjectEntity.grants).invoke()
    grants.find(_.id == id) match
      case Some(grant) =>
        record(granting, id, grant.grantee, grant.target, change, Attribution.from(actor, at))
      // The project holds every grant it ever made; one it does not hold was never made.
      case None => ()

  private def record(
      granting: String,
      id: String,
      grantee: Grantee,
      target: com.thinkmorestupidless.ankka.controlplane.api.GrantTarget,
      change: GrantChange,
      attribution: Option[Attribution]
  ): Unit =
    val organization = project(granting).call(ProjectEntity.get).invoke().organizationId
    val request      = RecordGrantChange(id, granting, organization, grantee, target, change)
    val metadata     = attribution.fold(Metadata.empty)(_.metadata)
    try
      grantee match
        case Grantee.Service(project, _) =>
          this
            .project(project)
            .call(ProjectEntity.recordGrantChange)
            .withMetadata(metadata)
            .invoke(request): Unit
        case Grantee.Machine(organizationId, _) =>
          client
            .forEventSourcedEntity(EntityId(organizationId))
            .call(OrganizationEntity.recordGrantChange)
            .withMetadata(metadata)
            .invoke(request): Unit
    catch case e: CommandError if e.code == ErrorCode.Conflict || e.code == ErrorCode.NotFound => ()

  /**
   * A grant made to a grantee deleted before this consumer recorded it: the deletion's trigger read
   * the grantee's side without it, so the grant is lapsed here, under who deleted the grantee. A
   * grant recorded first is the trigger's to lapse; lapsing twice is a conflict, read as done.
   */
  private def lapseIfGone(granting: String, id: String, grantee: Grantee): Unit =
    val deletion = grantee match
      case Grantee.Service(p, _) => project(p).call(ProjectEntity.deletion).invoke()
      case Grantee.Machine(organizationId, name) =>
        client
          .forEventSourcedEntity(EntityId(s"$organizationId/$name"))
          .call(MachineEntity.deletion)
          .invoke()
    deletion.foreach { d =>
      val by = Attribution.from(d.by, d.at).fold(Metadata.empty)(_.metadata)
      try project(granting).call(ProjectEntity.lapseGrant).withMetadata(by).invoke(id): Unit
      catch
        case e: CommandError if e.code == ErrorCode.Conflict || e.code == ErrorCode.NotFound => ()
    }

  private def project(id: String) = client.forEventSourcedEntity(EntityId(id))

object GrantMirror
    extends Consumer.Companion[GrantMirror, ProjectEvent, Nothing](
      componentId = ComponentId("grant-mirror"),
      source = ChangeSource.eventsOf(ProjectEntity)
    ):
  def create(ctx: ConsumerContext): GrantMirror = new GrantMirror(ctx.componentClient)
