package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution}
import com.thinkmorestupidless.ankka.controlplane.erasure.ErasureRequestEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.core.keyring.HandlerReport
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * An erasure request (feature 042, R21), id `<project>/<rid>`: asked, held until its date or an
 * owner's override, applied (the log written, then the key destroyed), completed per service, and
 * final. Who may do each is the endpoint's question; the entity is told the actor and records it,
 * and refuses only what no caller may do: a transition its state does not have.
 */
final class ErasureRequestEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ErasureRequest, ErasureRequestEvent]:

  def emptyState: ErasureRequest = ErasureRequest(context.entityId)

  def applyEvent(event: ErasureRequestEvent): ErasureRequest =
    val s = currentState
    event match
      case Asked(project, subject, notBefore, reason, correlation, actor, at) =>
        val held = notBefore.exists(nb => at.forall(nb.isAfter))
        s.copy(
          project = project,
          subject = subject,
          askedBy = actor,
          askedAt = at,
          notBefore = notBefore,
          reason = reason,
          correlation = correlation,
          state = Some(if held then ErasureState.Held else ErasureState.Applying)
        )
      case Replaced(notBefore, reason, _, _) => s.copy(notBefore = notBefore, reason = reason)
      case Withdrawn(_, at)                  =>
        s.copy(state = Some(ErasureState.Withdrawn), withdrawnAt = at)
      case HoldOverridden(reason, actor, at) =>
        s.copy(
          state = Some(ErasureState.Applying),
          holdOverride = actor.map(HoldOverride(_, reason, at))
        )
      case HoldLapsed(_, at)        => s.copy(state = Some(ErasureState.Applying), lapsedAt = at)
      case LogWritten(seq, _, _)    => s.copy(erasureSeq = Some(seq))
      case KeyDestroyed(when, _, _) =>
        s.copy(state = Some(ErasureState.Applied), destroyedAt = Some(when))
      case ServiceCompleted(service, completion, _, _) =>
        s.copy(completions = s.completions.updated(service, completion))
      case HandlerRan(service, report, _, at) =>
        s.copy(handlerRuns = s.handlerRuns :+ HandlerRun(service, report, at))
      case ObjectsFinal(when, _, _) => s.copy(objectsFinalAt = Some(when))
      case Finalised(_, at)         => s.copy(finalAt = at)

  def ask(request: AskErasure): Effect[ErasureRequest] =
    if currentState.exists then
      effects.error(s"erasure request '${context.entityId}' already exists", ErrorCode.Conflict)
    else
      ErasureRequestEntity.problemOf(request) match
        case Some(problem) => effects.error(problem)
        case None          =>
          effects
            .persist(
              Asked(
                request.project,
                request.subject,
                request.notBefore,
                request.reason,
                request.correlation,
                actor,
                Some(now)
              )
            )
            .thenReply(identity)

  def replace(request: ReplaceErasure): Effect[ErasureRequest] =
    whenHeld("replaced") {
      ErasureRequestEntity.reasonProblem(request.reason) match
        case Some(problem) => effects.error(problem)
        case None          =>
          effects
            .persist(Replaced(request.notBefore, request.reason, actor, Some(now)))
            .thenReply(identity)
    }

  def withdraw: Effect[ErasureRequest] =
    whenHeld("withdrawn")(effects.persist(Withdrawn(actor, Some(now))).thenReply(identity))

  def overrideHold(request: OverrideHold): Effect[ErasureRequest] =
    whenHeld("overridden") {
      if request.reason.trim.isEmpty then effects.error("an override needs a reason")
      else if request.reason.length > ErasureRequest.ReasonLimit then
        effects.error(s"an override's reason is at most ${ErasureRequest.ReasonLimit} characters")
      else effects.persist(HoldOverridden(request.reason, actor, Some(now))).thenReply(identity)
    }

  /** The sweep's: a held request whose date has passed is applied from here. */
  def lapse: Effect[ErasureRequest] =
    whenHeld("lapsed") {
      if currentState.notBefore.exists(_.isAfter(now)) then
        effects.error(
          s"erasure request '${context.entityId}' is held until ${currentState.notBefore.get}",
          ErrorCode.Conflict
        )
      else effects.persist(HoldLapsed(actor, Some(now))).thenReply(identity)
    }

  /** The log entry for this application was written; the same sequence twice is no news. */
  def logWritten(seq: Long): Effect[ErasureRequest] =
    currentState.state match
      case Some(ErasureState.Applying) if currentState.erasureSeq.contains(seq) =>
        effects.reply(currentState)
      case Some(ErasureState.Applying) if currentState.erasureSeq.isEmpty =>
        effects.persist(LogWritten(seq, actor, Some(now))).thenReply(identity)
      case Some(ErasureState.Applying) =>
        conflict(s"its log entry is ${currentState.erasureSeq.get}, not $seq")
      case _ => stateConflict("logged")

  /** The keyring destroyed the key: the request is applied. */
  def keyDestroyed(when: Instant): Effect[ErasureRequest] =
    currentState.state match
      case Some(ErasureState.Applied) => effects.reply(currentState)
      case Some(ErasureState.Applying) if currentState.erasureSeq.isEmpty =>
        conflict("no log entry was written, and nothing is destroyed before one is")
      case Some(ErasureState.Applying) =>
        effects.persist(KeyDestroyed(when, actor, Some(now))).thenReply(identity)
      case _ => stateConflict("applied")

  /** A service's completion: recorded once, the first time it is reported. */
  def recordCompletion(request: RecordCompletion): Effect[ErasureRequest] =
    if !currentState.state.contains(ErasureState.Applied) then stateConflict("completed")
    else if currentState.completions.contains(request.service) then effects.reply(currentState)
    else
      effects
        .persist(ServiceCompleted(request.service, request.completion, actor, Some(now)))
        .thenReply(identity)

  /** A run of a service's handler that erased something or failed (FR-016). */
  def recordHandlerRun(run: HandlerRun): Effect[ErasureRequest] =
    if !currentState.state.contains(ErasureState.Applied) then stateConflict("re-run")
    else effects.persist(HandlerRan(run.service, run.report, actor, Some(now))).thenReply(identity)

  def objectsFinal(when: Instant): Effect[ErasureRequest] =
    if !currentState.state.contains(ErasureState.Applied) then stateConflict("made final")
    else if currentState.objectsFinalAt.exists(!_.isBefore(when)) then effects.reply(currentState)
    else effects.persist(ObjectsFinal(when, actor, Some(now))).thenReply(identity)

  def finalise: Effect[ErasureRequest] =
    if !currentState.state.contains(ErasureState.Applied) then stateConflict("finalised")
    else if currentState.finalAt.isDefined then effects.reply(currentState)
    else effects.persist(Finalised(actor, Some(now))).thenReply(identity)

  def get: ReadOnlyEffect[ErasureRequest] =
    if !currentState.exists then
      effects.error(s"no such erasure request '${context.entityId}'", ErrorCode.NotFound)
    else effects.reply(currentState)

  private def whenHeld(what: String)(held: => Effect[ErasureRequest]): Effect[ErasureRequest] =
    if currentState.state.contains(ErasureState.Held) then held else stateConflict(what)

  private def stateConflict(what: String): Effect[ErasureRequest] =
    currentState.state match
      case None =>
        effects.error(s"no such erasure request '${context.entityId}'", ErrorCode.NotFound)
      case Some(state) => conflict(s"it is ${state.wire} and cannot be $what")

  private def conflict(why: String): Effect[ErasureRequest] =
    effects.error(s"erasure request '${context.entityId}': $why", ErrorCode.Conflict)

  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  // The attribution's time when the endpoint gave one; otherwise the moment the command arrived.
  private def now: Instant = attribution.map(_.at).getOrElse(Instant.now())

object ErasureRequestEntity
    extends EventSourcedEntity.Companion[ErasureRequestEntity, ErasureRequest, ErasureRequestEvent](
      componentId = ComponentId("erasure-request"),
      stateSerializer = Codecs.serializer[ErasureRequest]("erasure-request"),
      eventSerializer = Codecs.serializer[ErasureRequestEvent]("erasure-request-event")
    ):

  given Serializer[ErasureRequest]   = Codecs.serializer[ErasureRequest]("erasure-request")
  given Serializer[AskErasure]       = Codecs.serializer[AskErasure]("ask-erasure")
  given Serializer[ReplaceErasure]   = Codecs.serializer[ReplaceErasure]("replace-erasure")
  given Serializer[OverrideHold]     = Codecs.serializer[OverrideHold]("override-hold")
  given Serializer[RecordCompletion] = Codecs.serializer[RecordCompletion]("record-completion")
  given Serializer[HandlerRun]       = Codecs.serializer[HandlerRun]("handler-run")
  given Serializer[Instant]          = Codecs.serializer[Instant]("instant")

  def create(context: EventSourcedEntityContext) = new ErasureRequestEntity(context)

  val ask              = command("ask")(_.ask)
  val replace          = command("replace")(_.replace)
  val withdraw         = command("withdraw")(_.withdraw)
  val overrideHold     = command("override-hold")(_.overrideHold)
  val lapse            = command("lapse")(_.lapse)
  val logWritten       = command("log-written")(_.logWritten)
  val keyDestroyed     = command("key-destroyed")(_.keyDestroyed)
  val recordCompletion = command("record-completion")(_.recordCompletion)
  val recordHandlerRun = command("record-handler-run")(_.recordHandlerRun)
  val objectsFinal     = command("objects-final")(_.objectsFinal)
  val finalise         = command("finalise")(_.finalise)
  val get              = query("get")(_.get)

  private val Allowed = "[A-Za-z0-9._/:-]+".r

  /** Why an ask cannot be a request, or nothing. */
  def problemOf(request: AskErasure): Option[String] =
    DataSubject
      .from(request.subject)
      .left
      .toOption
      .orElse(Option.when(request.project.isEmpty)("an erasure request needs a project"))
      .orElse(reasonProblem(request.reason))
      .orElse(
        request.correlation.flatMap(c =>
          Option.when(c.length > ErasureRequest.CorrelationLimit || !Allowed.matches(c))(
            s"a correlation is 1 to ${ErasureRequest.CorrelationLimit} characters from letters, " +
              "digits, '.', '_', '-', '/' and ':'"
          )
        )
      )

  def reasonProblem(reason: Option[String]): Option[String] =
    reason.flatMap(r =>
      Option.when(r.length > ErasureRequest.ReasonLimit)(
        s"a reason is at most ${ErasureRequest.ReasonLimit} characters"
      )
    )

  /** A handler's report as the request records it. */
  def handlerRun(service: String, report: HandlerReport): HandlerRun = HandlerRun(service, report)
