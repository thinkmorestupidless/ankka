package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.controlplane.application.ProjectEntity
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution, RecordErasureRefused}
import com.thinkmorestupidless.ankka.http.{
  Acl,
  Caller,
  Callers,
  EndpointClients,
  HttpEndpoint,
  Respond
}
import org.slf4j.LoggerFactory

import java.security.SecureRandom

/**
 * A project's erasure requests (feature 042, `contracts/control-plane-and-cli.md`): a member asks,
 * lists, reads one and its certificate; a member replaces or withdraws a held one, and only an
 * owner overrides a hold. A request asked with no hold is applied at once, after the answer; every
 * write is refused while the installation's erasure is off. Mixed into the project endpoint, whose
 * prefix `/projects` they share and no second endpoint may.
 */
trait ErasureRoutes extends HttpEndpoint:

  protected def erasureClients: EndpointClients
  protected def erasureAuthz: Authorization
  protected def erasureSettings: ErasureSettings
  protected def erasureApplication: Option[ErasureApplication]

  private val log       = LoggerFactory.getLogger(classOf[ErasureRoutes])
  private lazy val rows = erasureClients.viewClient.forView(ErasureRequestsView)
  private def authz     = erasureAuthz
  private def erasure   = erasureSettings

  private def request(projectId: String, rid: String) =
    erasureClients.componentClient.forEventSourcedEntity(
      EntityId(ErasureRequest.entityId(projectId, rid))
    )

  private def requireOn(): Unit =
    if !erasure.enabled then
      throw CommandError(
        "erasure is off on this installation (ANKKA_ERASURE)",
        ErrorCode.Conflict
      )

  // A member asks with a token; a service of any project with the `erasure` grant of this one
  // (contracts/control-plane-and-cli.md), and is named as having asked.
  withAcl(Acl.anyOf(acl, Acl.allowCallers(Callers.anyService))) {
    postBody("/{projectId}/erasures") { (projectId: String, ask: ErasureAsk) =>
      val asker = request.principal match
        case Some(p) => authz.metadata(authz.project(p, projectId, write = true))
        case None    =>
          request.caller match
            case Caller.Service(project, service) => serviceAsker(projectId, project, service, ask)
            case other                            =>
              throw CommandError(s"$other may not ask for an erasure", ErrorCode.Forbidden)
      asked(projectId, ask, asker)
    }
  }

  /**
   * A service may ask when `projectId` holds an accepted `erasure` grant naming it, its own project
   * included. A refusal is recorded in the history of the project it asked in, once a window for
   * one caller and subject, then answered. A project that does not exist is refused as one that
   * grants nothing, so a service's ask cannot tell which projects exist.
   */
  private def serviceAsker(
      projectId: String,
      project: String,
      service: String,
      ask: ErasureAsk
  ): Metadata =
    requireOn()
    val actor = serviceActor(project, service)
    granted(projectId, project, service) match
      case Some(true)  => actor
      case Some(false) =>
        erasureClients.componentClient
          .forEventSourcedEntity(EntityId(projectId))
          .call(ProjectEntity.recordErasureRefused)
          .withMetadata(actor)
          .invoke(RecordErasureRefused(s"ankka://$project/$service", ask.subject)): Unit
        throw noGrant(projectId, project, service)
      case None => throw noGrant(projectId, project, service)

  private def serviceActor(project: String, service: String): Metadata =
    Attribution(
      Actor(s"ankka://$project/$service", Some(s"service $service of $project")),
      java.time.Instant.now()
    ).metadata

  private def noGrant(projectId: String, project: String, service: String) =
    CommandError(
      s"service $service of $project has no erasure grant of project '$projectId'",
      ErrorCode.Forbidden
    )

  /** Whether `projectId` grants the service `erasure`; none for a project that does not exist. */
  private def granted(projectId: String, project: String, service: String): Option[Boolean] =
    val grantee = Grantee.Service(project, service)
    try
      Some(
        erasureClients.componentClient
          .forEventSourcedEntity(EntityId(projectId))
          .call(ProjectEntity.grants)
          .invoke()
          .exists(g =>
            g.state == GrantState.Accepted && g.grantee == grantee &&
              g.target == GrantTarget.erasure
          )
      )
    catch case e: CommandError if e.code == ErrorCode.NotFound => None

  /**
   * Who may replace or withdraw request `rid`: a member, with a token; or the service that filed
   * it, while it still holds the project's `erasure` grant (FR-014: "its filer or a member"). Any
   * other service is refused, as one with no grant or as not the filer.
   */
  private def filerOrMember(projectId: String, rid: String): Metadata =
    request.principal match
      case Some(p) => authz.metadata(authz.project(p, projectId, write = true))
      case None    =>
        request.caller match
          case Caller.Service(project, service) =>
            if !granted(projectId, project, service).contains(true) then
              throw noGrant(projectId, project, service)
            val filed = request(projectId, rid).call(ErasureRequestEntity.get).invoke()
            if !filed.askedBy.exists(_.subject == s"ankka://$project/$service") then
              throw CommandError(
                s"service $service of $project did not ask for erasure request '$rid'",
                ErrorCode.Forbidden
              )
            serviceActor(project, service)
          case other =>
            throw CommandError(s"$other may not change an erasure request", ErrorCode.Forbidden)

  private def asked(projectId: String, ask: ErasureAsk, asker: Metadata) =
    requireOn()
    // Asked again for a data subject already erased: that request, nothing recorded, and its
    // notice sent again so every service, and every handler, runs it again (data-model.md).
    // The listing follows the requests, so each of the subject's still in play is read from its
    // entity: one applied a moment ago is listed as applying. One asked in the moment before the
    // listing has it at all is not found, and a second request is made; applying it destroys a
    // key already gone and runs every handler again, which is what the re-send does.
    val inPlay = rows
      .ask(ErasureRequestsView.bySubject, "project" -> projectId, "subject" -> ask.subject)
      .toVector
    // Asked again while a request for the subject is held: refused (decided 2026-11-05). A second
    // request with no date would apply at once what the hold keeps, which only an owner's override
    // may do; the held one is replaced through its own route. The check reads the listing, so a
    // second ask in the moment before it lists the held one is not refused. That is no way round
    // the hold: any member may withdraw a held request and ask again (FR-014). The refusal keeps
    // one request in play per subject and says what to do instead.
    inPlay
      .find(_.state == ErasureState.Held.wire)
      .foreach { held =>
        throw CommandError(
          s"data subject '${ask.subject}' has held erasure request '${held.rid}': replace its date " +
            s"(PUT /projects/$projectId/erasures/${held.rid}), withdraw it, or have an owner " +
            "override its hold",
          ErrorCode.Conflict
        )
      }
    val applied = inPlay.iterator
      .filter(r => r.state == ErasureState.Applied.wire || r.state == ErasureState.Applying.wire)
      .map(r => request(projectId, r.rid).call(ErasureRequestEntity.get).invoke())
      .find(_.state.contains(ErasureState.Applied))
    applied match
      case Some(current) =>
        erasureApplication.foreach(app =>
          app
            .resend(current)
            .failed
            .foreach(e => log.warn(s"erasure request ${current.id} was not sent again", e))(using
              scala.concurrent.ExecutionContext.parasitic
            )
        )
        Respond(ErasureRoutes.view(current), 200)
      case None => askNew(projectId, ask, asker)

  private def askNew(
      projectId: String,
      ask: ErasureAsk,
      asker: Metadata
  ): Respond[ErasureRequestView] =
    val rid   = ErasureRoutes.rid()
    val asked = request(projectId, rid)
      .call(ErasureRequestEntity.ask)
      .withMetadata(asker)
      .invoke(AskErasure(projectId, ask.subject, ask.notBefore, ask.reason, ask.correlation))
    if asked.state.contains(ErasureState.Applying) then applyLater(asked.id)
    Respond(ErasureRoutes.view(asked), 201)

  get("/{projectId}/erasures") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    val subject     = request.query.optional[String]("subject")
    val state       = request.query.optional[String]("state")
    val correlation = request.query.optional[String]("correlation")
    val limit       = request.query.optional[Int]("limit").getOrElse(100)
    if limit < 1 || limit > 1000 then
      throw CommandError("limit must be between 1 and 1000", ErrorCode.BadRequest)
    state.foreach(s =>
      if ErasureState.parse(s).isEmpty then
        throw CommandError(
          s"state is one of ${ErasureState.values.map(_.wire).mkString(", ")}, not '$s'",
          ErrorCode.BadRequest
        )
    )
    val found = subject match
      case Some(s) =>
        rows.ask(ErasureRequestsView.bySubject, "project" -> projectId, "subject" -> s)
      case None => rows.ask(ErasureRequestsView.byProject, "project" -> projectId)
    found
      .filter(r => state.forall(_ == r.state) && correlation.forall(c => r.correlation.contains(c)))
      .take(limit)
      .map(ErasureRoutes.summary)
  }

  get("/{projectId}/erasures/{rid}") { (projectId: String, rid: String) =>
    authz.project(principal, projectId, write = false): Unit
    val current = request(projectId, rid).call(ErasureRequestEntity.get).invoke()
    ErasureRoutes.view(current, awaiting(current))
  }

  /** The services `current` still waits on (FR-016): listed with no completion. */
  private def awaiting(current: ErasureRequest): Vector[String] =
    erasureApplication.fold(Vector.empty[String])(app =>
      ErasureApplication.awaiting(current, app.expected(current.project))
    )

  // Whoever asked for it, a service included, or any other member: a hold is the project's.
  withAcl(Acl.anyOf(acl, Acl.allowCallers(Callers.anyService))) {
    putBody("/{projectId}/erasures/{rid}") {
      (projectId: String, rid: String, body: ErasureReplace) =>
        val by = filerOrMember(projectId, rid)
        requireOn()
        ErasureRoutes.view(
          request(projectId, rid)
            .call(ErasureRequestEntity.replace)
            .withMetadata(by)
            .invoke(ReplaceErasure(body.notBefore, body.reason))
        )
    }

    post("/{projectId}/erasures/{rid}/withdraw") { (projectId: String, rid: String) =>
      val by = filerOrMember(projectId, rid)
      requireOn()
      ErasureRoutes.view(
        request(projectId, rid)
          .call(ErasureRequestEntity.withdraw)
          .withMetadata(by)
          .invoke()
      )
    }
  }

  postBody("/{projectId}/erasures/{rid}/override") {
    (projectId: String, rid: String, body: ErasureOverride) =>
      val access = authz.projectOwner(principal, projectId)
      requireOn()
      val overridden = request(projectId, rid)
        .call(ErasureRequestEntity.overrideHold)
        .withMetadata(authz.metadata(access))
        .invoke(OverrideHold(body.reason))
      // Applying at once, as an unheld request is: after the answer, and by the sweep if not now.
      applyLater(overridden.id)
      ErasureRoutes.view(overridden)
  }

  get("/{projectId}/erasures/{rid}/certificate") { (projectId: String, rid: String) =>
    authz.project(principal, projectId, write = false): Unit
    val current = request(projectId, rid).call(ErasureRequestEntity.get).invoke()
    if !current.state.contains(ErasureState.Applied) then
      throw CommandError(
        s"erasure request '$rid' is ${current.state.fold("unknown")(_.wire)}; a certificate is " +
          "issued once it is applied",
        ErrorCode.Conflict
      )
    ErasureRoutes.certificate(current, awaiting(current))
  }

  private def applyLater(id: String): Unit =
    erasureApplication.foreach { app =>
      app
        .apply(id)
        .failed
        .foreach(e =>
          log.warn(s"erasure request $id was not applied yet; the sweep tries again", e)
        )(using
          scala.concurrent.ExecutionContext.parasitic
        )
    }

object ErasureRoutes:

  private val random    = SecureRandom()
  private val Crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  /** A request's id: a ULID, 48 bits of milliseconds and 80 random bits, sortable by time. */
  def rid(now: Long = System.currentTimeMillis()): String =
    val randomBits = new Array[Byte](10)
    random.nextBytes(randomBits)
    val value = BigInt(now) << 80 | BigInt(1, randomBits)
    (0 until 26).map(i => Crockford(((value >> (5 * (25 - i))) & 31).toInt)).mkString

  private def handler(h: com.thinkmorestupidless.ankka.core.keyring.HandlerReport) =
    ErasureHandlerView(h.objectsErased, h.finality, h.error)

  def view(r: ErasureRequest, awaiting: Vector[String] = Vector.empty): ErasureRequestView =
    ErasureRequestView(
      id = r.rid,
      project = r.project,
      subject = r.subject,
      state = r.state.fold("")(_.wire),
      askedBy = r.askedBy.map(_.subject),
      askedAt = r.askedAt,
      notBefore = r.notBefore,
      reason = r.reason,
      correlation = r.correlation,
      lapsedAt = r.lapsedAt,
      holdOverride = r.holdOverride.map(o => ErasureOverrideView(o.owner.subject, o.reason, o.at)),
      erasureSeq = r.erasureSeq,
      destroyedAt = r.destroyedAt,
      completions = (r.completions.toVector.map { (service, c) =>
        ErasureCompletionView(
          service,
          c.at,
          c.rowsRedacted,
          c.tokensDropped,
          c.sessionsEnded,
          c.handler.map(handler)
        )
      } ++ awaiting.map(ErasureCompletionView(_))).sortBy(_.service),
      objectsFinalAt = r.objectsFinalAt,
      finalAt = r.finalAt,
      lastFailure = r.lastFailure.map(f => ErasureFailureView(f.reason, f.at))
    )

  def summary(r: ErasureRequestRow): ErasureRequestSummary =
    ErasureRequestSummary(
      r.rid,
      r.subject,
      r.state,
      r.correlation,
      r.notBefore,
      r.askedBy,
      r.askedAt,
      r.appliedAt,
      r.finalAt
    )

  /**
   * The certificate; a service the request still waits on is listed with no completion time, so a
   * certificate issued before finality says who has not carried the erasure out.
   */
  def certificate(r: ErasureRequest, awaiting: Vector[String] = Vector.empty): ErasureCertificate =
    ErasureCertificate(
      request = r.id,
      project = r.project,
      subject = r.subject,
      askedBy = r.askedBy.map(_.subject),
      askedAt = r.askedAt,
      hold = r.notBefore.map(nb => ErasureHoldView(nb, r.reason, r.lapsedAt)),
      holdOverride = r.holdOverride.map(o => ErasureOverrideView(o.owner.subject, o.reason, o.at)),
      keyDestroyedAt = r.destroyedAt,
      services = (r.completions.toVector.map { (service, c) =>
        ErasureCertificateService(service, c.at, c.handler.map(handler))
      } ++ awaiting.map(ErasureCertificateService(_))).sortBy(_.service),
      finalAt = r.finalAt
    )
