package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, Respond}
import org.slf4j.LoggerFactory

import java.security.SecureRandom

/**
 * A project's erasure requests (feature 042, `contracts/control-plane-and-cli.md`): a member asks,
 * lists, reads one and its certificate. A request asked with no hold is applied at once, after the
 * answer; every write is refused while the installation's erasure is off. Mixed into the project
 * endpoint, whose prefix `/projects` they share and no second endpoint may.
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

  postBody("/{projectId}/erasures") { (projectId: String, ask: ErasureAsk) =>
    val access = authz.project(principal, projectId, write = true)
    requireOn()
    // Asked again for a data subject already erased: that request, nothing recorded, and its
    // notice sent again so every service, and every handler, runs it again (data-model.md).
    // The listing follows the requests, so each of the subject's still in play is read from its
    // entity: one applied a moment ago is listed as applying. One asked in the moment before the
    // listing has it at all is not found, and a second request is made; applying it destroys a
    // key already gone and runs every handler again, which is what the re-send does.
    val applied = rows
      .ask(ErasureRequestsView.bySubject, "project" -> projectId, "subject" -> ask.subject)
      .iterator
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
      case None => askNew(projectId, ask, access)
  }

  private def askNew(
      projectId: String,
      ask: ErasureAsk,
      access: com.thinkmorestupidless.ankka.controlplane.auth.Authorized
  ): Respond[ErasureRequestView] =
    val rid   = ErasureRoutes.rid()
    val asked = request(projectId, rid)
      .call(ErasureRequestEntity.ask)
      .withMetadata(authz.metadata(access))
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
    ErasureRoutes.view(request(projectId, rid).call(ErasureRequestEntity.get).invoke())
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
    ErasureRoutes.certificate(current)
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

  def view(r: ErasureRequest): ErasureRequestView =
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
      completions = r.completions.toVector.sortBy(_._1).map { (service, c) =>
        ErasureCompletionView(
          service,
          c.at,
          c.rowsRedacted,
          c.tokensDropped,
          c.sessionsEnded,
          c.handler.map(handler)
        )
      },
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

  def certificate(r: ErasureRequest): ErasureCertificate =
    ErasureCertificate(
      request = r.id,
      project = r.project,
      subject = r.subject,
      askedBy = r.askedBy.map(_.subject),
      askedAt = r.askedAt,
      hold = r.notBefore.map(nb => ErasureHoldView(nb, r.reason, r.lapsedAt)),
      holdOverride = r.holdOverride.map(o => ErasureOverrideView(o.owner.subject, o.reason, o.at)),
      keyDestroyedAt = r.destroyedAt,
      services = r.completions.toVector.sortBy(_._1).map { (service, c) =>
        ErasureCertificateService(service, c.at, c.handler.map(handler))
      },
      finalAt = r.finalAt
    )
