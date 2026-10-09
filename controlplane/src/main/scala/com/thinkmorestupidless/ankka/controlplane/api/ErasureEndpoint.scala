package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.application.*
import com.thinkmorestupidless.ankka.controlplane.auth.{Authorization, Principals}
import com.thinkmorestupidless.ankka.controlplane.deploy.ErasureSweeper
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi.given

import java.time.{LocalDate, ZoneOffset}
import java.util.UUID

/**
 * Erasure requests (`contracts/control-plane-erasures.md`), under `/projects/{id}/erasures`: a
 * member asks for, lists, reads, withdraws, applies again and fetches the certificate of one; an
 * owner overrides a hold. Mixed into `ProjectEndpoint`, which owns the `/projects` prefix.
 */
private[api] trait ErasureRoutes extends HttpEndpoint with Attributing:

  protected def erasureClients: EndpointClients
  protected def erasureSweeper: Option[ErasureSweeper]

  /** The installation's grants (spec 040): what admits a service's ask. None until 040. */
  protected def erasureGrants: com.thinkmorestupidless.ankka.runtime.erasure.GrantReader

  private def grants = erasureGrants

  private def clients    = erasureClients
  private def sweeper    = erasureSweeper
  private lazy val authz = Authorization(clients, clock)
  private lazy val rows  = clients.viewClient.forView(ErasureRows)

  private def entity(projectId: String, id: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(s"$projectId/$id"))

  private def today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

  private def who: ErasureWho =
    ErasureWho("member", principal.subject, Some(Principals.display(principal)))

  private def read(projectId: String, id: String): ErasureRequest =
    try entity(projectId, id).call(ErasureEntity.get).invoke()
    catch
      case e: CommandError if e.code == ErrorCode.NotFound =>
        throw CommandError(s"no erasure request '$id' in project '$projectId'", ErrorCode.NotFound)

  private def ofSubject(projectId: String, subject: String): Vector[ErasureRequest] =
    rows.where(
      jsonText("projectId") ++ sql" = $projectId AND " ++ jsonText("subject") ++ sql" = $subject"
    )

  /**
   * Asks for an erasure. A second request for a subject already erased is answered with the applied
   * one; a request from whoever has a held one for the subject replaces it, the history keeping
   * both; a held one from someone else must be withdrawn first.
   */
  /**
   * Who may ask: a member, by their token, or a service of the installation, by its certificate —
   * admitted here and authorized by its grants in the handler, so that a refusal is recorded.
   */
  private def memberOrService: Acl = Acl.Authenticate(context =>
    context.caller match
      case Caller.Service(project, name) =>
        AuthDecision.Allow(
          Principal(
            com.thinkmorestupidless.ankka.runtime.erasure.GrantReader.service(project, name),
            name = Some(s"$project/$name")
          )
        )
      case _ =>
        acl match
          case Acl.Authenticate(decide) => decide(context)
          case _                        => AuthDecision.Forbidden("members only")
  )

  withAcl(memberOrService) {
    postBody("/{projectId}/erasures") { (projectId: String, request: RequestErasure) =>
      val problems = ErasureRequests.problems(request, today)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      caller match
        case Caller.Service(project, name) => askAsService(projectId, request, project, name)
        case _                             => askAsMember(projectId, request)
    }
  }

  /**
   * A service's ask (FR-031): admitted by a grant of `erasure` in the project, its own included,
   * and named as who asked; refused without one, and the refusal kept in the project's history.
   */
  private def askAsService(
      projectId: String,
      request: RequestErasure,
      project: String,
      name: String
  ): Respond[ErasureRequest] =
    val asker   = ErasureWho("service", name, Some(s"$project/$name"), Some(project))
    val id      = "e-" + UUID.randomUUID().toString.replace("-", "").take(12)
    val ask     = AskErasure(request, id, projectId, asker, clock.instant())
    val allowed = grants.allows(principal.subject, projectId, "erasure")
    if !allowed then
      val reason = s"service $project/$name holds no grant of erasure in project $projectId"
      entity(projectId, id).call(ErasureEntity.recordRefusal).invoke(RefuseAsk(ask, reason)): Unit
      throw CommandError(reason, ErrorCode.Forbidden)
    ofSubject(projectId, request.subject).find(r => Done(r.state)) match
      case Some(applied) => Respond(applied, 200)
      case None          => Respond(entity(projectId, id).call(ErasureEntity.ask).invoke(ask), 201)

  private val Done =
    Set(ErasureState.Applying, ErasureState.Applied, ErasureState.Final, ErasureState.Settled)

  private def askAsMember(projectId: String, request: RequestErasure): Respond[ErasureRequest] =
    val access = authz.project(principal, projectId, write = true)
    val prior  = ofSubject(projectId, request.subject)
    prior.find(r => Done(r.state)) match
      case Some(applied) => Respond(applied, 200)
      case None =>
        val held = prior.find(_.state == ErasureState.Held)
        if held.exists(_.askedBy.subject != principal.subject) then
          throw CommandError(
            s"a held erasure request for ${request.subject} exists (${held.get.id}), asked for by someone else: withdraw it first",
            ErrorCode.Conflict
          )
        val id = "e-" + UUID.randomUUID().toString.replace("-", "").take(12)
        val created = entity(projectId, id)
          .call(ErasureEntity.ask)
          .withMetadata(authz.metadata(access))
          .invoke(AskErasure(request, id, projectId, who, clock.instant()))
        held.foreach { old =>
          entity(projectId, old.id)
            .call(ErasureEntity.replace)
            .withMetadata(authz.metadata(access))
            .invoke(Replace(id, clock.instant())): Unit
        }
        Respond(created, 201)

  /** `?subject=`, `?state=`, `?correlation=`: any, all or none. */
  get("/{projectId}/erasures") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    val correlation = query.optional[String]("correlation")
    // By correlation id, the requests of every project the member may read: one person's requests
    // across the projects that know them, found from either (FR-032).
    val filters =
      Vector(
        Option.when(correlation.isEmpty)(jsonText("projectId") ++ sql" = $projectId"),
        query.optional[String]("subject").map(s => jsonText("subject") ++ sql" = $s"),
        query.optional[String]("state").map(s => jsonText("state") ++ sql" = ${s.toLowerCase}"),
        correlation.map(c => jsonText("correlationId") ++ sql" = $c")
      )
    val condition = filters.flatten.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)
    val readable  = scala.collection.mutable.Map.empty[String, Boolean]
    def mayRead(project: String): Boolean =
      readable.getOrElseUpdate(
        project,
        try { authz.project(principal, project, write = false); true }
        catch case _: CommandError => false
      )
    rows.where(condition).filter(r => mayRead(r.projectId)).sortBy(_.askedAt)
  }

  /** What has happened to the project, newest first: its erasures asked for, applied, failed. */
  get("/{projectId}/history") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    ProjectHistory.of(rows.where(jsonText("projectId") ++ sql" = $projectId"))
  }

  get("/{projectId}/erasures/{id}") { (projectId: String, id: String) =>
    authz.project(principal, projectId, write = false): Unit
    read(projectId, id)
  }

  /** Withdraws a held request: whoever asked for it, or any member. Nothing has been destroyed. */
  delete("/{projectId}/erasures/{id}") { (projectId: String, id: String) =>
    val access = authz.project(principal, projectId, write = true)
    read(projectId, id): Unit
    entity(projectId, id)
      .call(ErasureEntity.withdraw)
      .withMetadata(authz.metadata(access))
      .invoke(Withdraw(who, clock.instant()))
  }

  /** An owner applies a held request now, with the reason recorded; a member is refused. */
  postBody("/{projectId}/erasures/{id}/override") {
    (projectId: String, id: String, request: OverrideHold) =>
      val access = authz.project(principal, projectId, write = true)
      if access.role != Role.Owner && !authz.isAdmin(principal) then
        throw CommandError(
          s"owner role required to override a hold in project '$projectId'",
          ErrorCode.Forbidden
        )
      if request.reason.isBlank then
        throw CommandError("an override needs a reason", ErrorCode.BadRequest)
      read(projectId, id): Unit
      entity(projectId, id)
        .call(ErasureEntity.overrideHold)
        .withMetadata(authz.metadata(access))
        .invoke(Override(who, request.reason, clock.instant()))
  }

  /** Runs every service's erasure handler again: any applied request, a settled one included. */
  post("/{projectId}/erasures/{id}/reapply") { (projectId: String, id: String) =>
    authz.project(principal, projectId, write = true): Unit
    val r = read(projectId, id)
    if !Set(ErasureState.Applied, ErasureState.Final, ErasureState.Settled)(r.state) then
      throw CommandError(
        s"erasure request $id is ${r.state.toString.toLowerCase}: only an applied one is applied again",
        ErrorCode.Conflict
      )
    sweeper.foreach(_.reapplyNow(r))
    r
  }

  get("/{projectId}/erasures/{id}/certificate") { (projectId: String, id: String) =>
    authz.project(principal, projectId, write = false): Unit
    val r = read(projectId, id)
    if !Set(ErasureState.Applied, ErasureState.Final, ErasureState.Settled)(r.state) then
      throw CommandError(
        s"erasure request $id is ${r.state.toString.toLowerCase}: a certificate is issued once it is applied",
        ErrorCode.NotFound
      )
    ErasureCertificate(
      r,
      clock.instant(),
      s"The personal data of data subject ${r.subject} in project $projectId was made unreadable by " +
        s"destroying its key at ${r.keyDestroyedAt.fold("-")(_.toString)}; every service of the project " +
        s"completed the erasure${r.finalAt.fold("")(at => s", and it became final at $at")}. The subject's " +
        "id itself is kept, as the ledger the law requires is keyed by it."
    )
  }

/**
 * The erasure log, for the keyring to replay after a restore of its database (FR-021): the keyring
 * alone.
 */
final class ErasureLogEndpoint(clients: EndpointClients) extends HttpEndpoint("/erasures"):
  val acl: Acl = Acl.allowCallers(Callers.service("platform", "keyring"))

  locally {
    get("/log") { () =>
      val after = query.optional[Long]("after").getOrElse(0L)
      clients.componentClient
        .forEventSourcedEntity(EntityId(ErasureLogEntity.Id))
        .call(ErasureLogEntity.after)
        .invoke(after)
        .entries
        .map(e =>
          KeyringApi.LogEntry(e.erasureId, e.projectId, e.subject, e.sequence, e.at.toEpochMilli)
        )
    }
  }
