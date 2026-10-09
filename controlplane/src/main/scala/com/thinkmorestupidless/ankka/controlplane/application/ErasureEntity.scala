package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** What the request entity records: the wire request is its state, every change an event. */
enum ErasureEvent:
  case Asked(request: ErasureRequest)
  case Withdrawn(by: ErasureWho, at: Instant)
  case Replaced(by: String, at: Instant)
  case Overridden(by: ErasureWho, reason: String, at: Instant)
  case Released(at: Instant)
  case LogWritten(sequence: Long, at: Instant)
  case KeyDestroyed(at: Instant)
  case ServiceCompleted(completion: ErasureServiceCompletion)
  case Applied(at: Instant)
  case Finalised(at: Instant)
  case Settled(at: Instant)
  case Failed(reason: String, at: Instant)
  case Retried(at: Instant)

final case class AskErasure(
    request: RequestErasure,
    id: String,
    projectId: String,
    askedBy: ErasureWho,
    at: Instant
)
final case class Withdraw(by: ErasureWho, at: Instant)
final case class RefuseAsk(ask: AskErasure, reason: String)
final case class Override(by: ErasureWho, reason: String, at: Instant)
final case class Replace(by: String, at: Instant)
final case class Step(at: Instant, sequence: Option[Long] = None, reason: Option[String] = None)

/**
 * One erasure request (id `<projectId>/<erasureId>`), its whole history in its journal: the audit
 * an auditor reads (FR-016). Who may do what — a member, an owner, the asker — is the endpoint's to
 * decide; what may happen next is this entity's.
 */
final class ErasureEntity extends EventSourcedEntity[Option[ErasureRequest], ErasureEvent]:

  def emptyState: Option[ErasureRequest] = None

  def applyEvent(event: ErasureEvent): Option[ErasureRequest] =
    import ErasureEvent.*
    (event, currentState) match
      case (Asked(request), _) => Some(request)
      case (_, None)           => None
      case (e, Some(r)) =>
        Some(e match
          case Withdrawn(by, _) => r.copy(state = ErasureState.Withdrawn, withdrawnBy = Some(by))
          case Replaced(by, _)  => r.copy(state = ErasureState.Replaced, replacedBy = Some(by))
          case Overridden(by, reason, at) =>
            r.copy(
              state = ErasureState.Applying,
              overridden = Some(ErasureOverride(by, reason, at))
            )
          case Released(_)             => r.copy(state = ErasureState.Applying)
          case LogWritten(sequence, _) => r.copy(sequence = Some(sequence))
          case KeyDestroyed(at)        => r.copy(keyDestroyedAt = Some(at))
          case ServiceCompleted(c) =>
            r.copy(completions = r.completions.filterNot(_.service == c.service) :+ c)
          case Applied(at) =>
            r.copy(state = ErasureState.Applied, appliedAt = Some(at), failure = None)
          case Finalised(at)     => r.copy(state = ErasureState.Final, finalAt = Some(at))
          case Settled(_)        => r.copy(state = ErasureState.Settled)
          case Failed(reason, _) => r.copy(state = ErasureState.Failed, failure = Some(reason))
          case Retried(_)        => r.copy(state = ErasureState.Applying, failure = None)
          case Asked(request)    => request)

  private def existing: ErasureRequest =
    currentState.getOrElse(throw CommandError("no such erasure request", ErrorCode.NotFound))

  def ask(command: AskErasure): Effect[ErasureRequest] =
    currentState match
      case Some(r) => effects.reply(r)
      case None =>
        val request = ErasureRequest(
          id = command.id,
          projectId = command.projectId,
          subject = command.request.subject,
          state =
            if command.request.notBefore.isDefined then ErasureState.Held
            else ErasureState.Applying,
          askedBy = command.askedBy,
          askedAt = command.at,
          notBefore = command.request.notBefore,
          reason = command.request.reason,
          correlationId = command.request.correlationId
        )
        effects.persist(ErasureEvent.Asked(request)).thenReply(_ => request)

  /**
   * A service asked and its grants did not allow it: the attempt is kept, refused, with why, so the
   * project's history shows it. Nothing is applied.
   */
  def recordRefusal(command: RefuseAsk): Effect[ErasureRequest] =
    currentState match
      case Some(r) => effects.reply(r)
      case None =>
        val request = ErasureRequest(
          id = command.ask.id,
          projectId = command.ask.projectId,
          subject = command.ask.request.subject,
          state = ErasureState.Refused,
          askedBy = command.ask.askedBy,
          askedAt = command.ask.at,
          correlationId = command.ask.request.correlationId,
          failure = Some(command.reason)
        )
        effects.persist(ErasureEvent.Asked(request)).thenReply(_ => request)

  def withdraw(command: Withdraw): Effect[ErasureRequest] =
    val r = existing
    if r.state != ErasureState.Held then
      effects.error(
        s"erasure request ${r.id} is ${r.state.toString.toLowerCase}: only a held one can be withdrawn",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(ErasureEvent.Withdrawn(command.by, command.at))
        .thenReply(after => after.getOrElse(existing))

  def replace(command: Replace): Effect[ErasureRequest] =
    val r = existing
    if r.state != ErasureState.Held then
      effects.error(s"erasure request ${r.id} is not held", ErrorCode.Conflict)
    else
      effects
        .persist(ErasureEvent.Replaced(command.by, command.at))
        .thenReply(after => after.getOrElse(existing))

  def overrideHold(command: Override): Effect[ErasureRequest] =
    val r = existing
    if r.state != ErasureState.Held then
      effects.error(
        s"erasure request ${r.id} is ${r.state.toString.toLowerCase}: only a hold can be overridden",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(ErasureEvent.Overridden(command.by, command.reason, command.at))
        .thenReply(after => after.getOrElse(existing))

  /** The sweeper's steps: each a no-op when the request is not where the step applies. */
  def release(step: Step): Effect[ErasureRequest] =
    if existing.state == ErasureState.Held then
      effects.persist(ErasureEvent.Released(step.at)).thenReply(after => after.getOrElse(existing))
    else effects.reply(existing)

  def logWritten(step: Step): Effect[ErasureRequest] =
    if existing.sequence.isDefined then effects.reply(existing)
    else
      effects
        .persist(ErasureEvent.LogWritten(step.sequence.get, step.at))
        .thenReply(after => after.getOrElse(existing))

  def keyDestroyed(step: Step): Effect[ErasureRequest] =
    if existing.keyDestroyedAt.isDefined then effects.reply(existing)
    else
      effects
        .persist(ErasureEvent.KeyDestroyed(step.at))
        .thenReply(after => after.getOrElse(existing))

  /**
   * A service's completion, recorded only when it says something the last one for that service did
   * not (FR-016).
   */
  def completed(completion: ErasureServiceCompletion): Effect[ErasureRequest] =
    val prior = existing.completions.find(_.service == completion.service)
    val same = prior.exists(p =>
      p.handler == completion.handler && p.objectsErased == completion.objectsErased && p.byAbsence == completion.byAbsence
    )
    if same then effects.reply(existing)
    else
      effects
        .persist(ErasureEvent.ServiceCompleted(completion))
        .thenReply(after => after.getOrElse(existing))

  def applied(step: Step): Effect[ErasureRequest] =
    if existing.state == ErasureState.Applying then
      effects.persist(ErasureEvent.Applied(step.at)).thenReply(after => after.getOrElse(existing))
    else effects.reply(existing)

  def finalised(step: Step): Effect[ErasureRequest] =
    if existing.state == ErasureState.Applied then
      effects.persist(ErasureEvent.Finalised(step.at)).thenReply(after => after.getOrElse(existing))
    else effects.reply(existing)

  def settled(step: Step): Effect[ErasureRequest] =
    if existing.state == ErasureState.Final then
      effects.persist(ErasureEvent.Settled(step.at)).thenReply(after => after.getOrElse(existing))
    else effects.reply(existing)

  def failed(step: Step): Effect[ErasureRequest] =
    if existing.state == ErasureState.Applying && !existing.failure.contains(
        step.reason.getOrElse("")
      )
    then
      effects
        .persist(ErasureEvent.Failed(step.reason.getOrElse("failed"), step.at))
        .thenReply(after => after.getOrElse(existing))
    else effects.reply(existing)

  def retry(step: Step): Effect[ErasureRequest] =
    if existing.state == ErasureState.Failed then
      effects.persist(ErasureEvent.Retried(step.at)).thenReply(after => after.getOrElse(existing))
    else effects.reply(existing)

  def get: ReadOnlyEffect[ErasureRequest] =
    currentState.fold(effects.error("no such erasure request", ErrorCode.NotFound))(effects.reply)

object ErasureEntity
    extends EventSourcedEntity.Companion[ErasureEntity, Option[ErasureRequest], ErasureEvent](
      componentId = ComponentId("erasure-request"),
      stateSerializer = Codecs.serializer[Option[ErasureRequest]]("erasure-request-state"),
      eventSerializer = Codecs.serializer[ErasureEvent]("erasure-request-event")
    ):
  def create(context: EventSourcedEntityContext) = new ErasureEntity

  given Serializer[AskErasure] = Codecs.serializer[AskErasure]("erasure-ask")
  given Serializer[Withdraw]   = Codecs.serializer[Withdraw]("erasure-withdraw")
  given Serializer[RefuseAsk]  = Codecs.serializer[RefuseAsk]("erasure-refuse-ask")
  given Serializer[Override]   = Codecs.serializer[Override]("erasure-override")
  given Serializer[Replace]    = Codecs.serializer[Replace]("erasure-replace")
  given Serializer[Step]       = Codecs.serializer[Step]("erasure-step")
  given Serializer[ErasureServiceCompletion] =
    Codecs.serializer[ErasureServiceCompletion]("erasure-completion")
  given Serializer[ErasureRequest] = Codecs.serializer[ErasureRequest]("erasure-request")

  val ask           = command("ask")(_.ask)
  val withdraw      = command("withdraw")(_.withdraw)
  val replace       = command("replace")(_.replace)
  val overrideHold  = command("override")(_.overrideHold)
  val release       = command("release")(_.release)
  val logWritten    = command("log-written")(_.logWritten)
  val keyDestroyed  = command("key-destroyed")(_.keyDestroyed)
  val completed     = command("completed")(_.completed)
  val applied       = command("applied")(_.applied)
  val finalised     = command("finalised")(_.finalised)
  val settled       = command("settled")(_.settled)
  val failed        = command("failed")(_.failed)
  val retry         = command("retry")(_.retry)
  val get           = query("get")(_.get)
  val recordRefusal = command("record-refusal")(_.recordRefusal)

  /** Who a member is, as an erasure request names them. */
  def member(actor: Actor): ErasureWho = ErasureWho("member", actor.subject, actor.display)

  def who(metadata: Metadata): Option[ErasureWho] =
    Attribution.from(metadata).map(a => member(a.actor))

/** Every erasure request, listable by project, subject, state and correlation id. */
final class ErasureRowsView extends View[ErasureEvent, ErasureRequest]:
  def onChange(event: ErasureEvent): Effect =
    (event, rowState) match
      case (ErasureEvent.Asked(request), _) => effects.updateRow(request)
      case (_, None)                        => effects.ignore()
      case (e, Some(row))                   =>
        // The same fold as the entity's, over the row.
        val entity = new ErasureEntity
        entity._setState(Some(row))
        effects.updateRow(entity.applyEvent(e).getOrElse(row))

object ErasureRows
    extends View.Companion[ErasureRowsView, ErasureEvent, ErasureRequest](
      componentId = ComponentId("erasure-requests"),
      source = ChangeSource.eventsOf(ErasureEntity),
      rowSerializer = Codecs.serializer[ErasureRequest]("erasure-request-row")
    ):
  def create(ctx: ViewComponentContext) = new ErasureRowsView

/** One entry of the installation's erasure log, as the control plane keeps its copy. */
final case class LogAppend(erasureId: String, projectId: String, subject: String, at: Instant)
final case class ErasureLogEntry(
    sequence: Long,
    erasureId: String,
    projectId: String,
    subject: String,
    at: Instant
)

enum ErasureLogEvent:
  case Appended(entry: ErasureLogEntry)

final case class ErasureLog(entries: Vector[ErasureLogEntry])

/**
 * The installation's erasure log, the control plane's copy (FR-017; id `installation`): every
 * erasure written here, and to the platform bucket, before any key is destroyed. One sequence for
 * the installation, so a service's "highest applied" means the same thing in every project.
 */
final class ErasureLogEntity extends EventSourcedEntity[ErasureLog, ErasureLogEvent]:
  def emptyState: ErasureLog = ErasureLog(Vector.empty)

  def applyEvent(event: ErasureLogEvent): ErasureLog = event match
    case ErasureLogEvent.Appended(entry) => ErasureLog(currentState.entries :+ entry)

  def append(request: LogAppend): Effect[ErasureLogEntry] =
    currentState.entries.find(_.erasureId == request.erasureId) match
      case Some(entry) => effects.reply(entry)
      case None =>
        val entry = ErasureLogEntry(
          currentState.entries.size.toLong + 1,
          request.erasureId,
          request.projectId,
          request.subject,
          request.at
        )
        effects.persist(ErasureLogEvent.Appended(entry)).thenReply(_ => entry)

  /**
   * Appended from the bucket after a restore (041's FR-031): kept at the sequence the bucket says.
   */
  def reconcile(entry: ErasureLogEntry): Effect[ErasureLogEntry] =
    if currentState.entries.exists(_.erasureId == entry.erasureId) then effects.reply(entry)
    else effects.persist(ErasureLogEvent.Appended(entry)).thenReply(_ => entry)

  def after(sequence: Long): ReadOnlyEffect[ErasureLog] =
    effects.reply(ErasureLog(currentState.entries.filter(_.sequence > sequence)))

object ErasureLogEntity
    extends EventSourcedEntity.Companion[ErasureLogEntity, ErasureLog, ErasureLogEvent](
      componentId = ComponentId("erasure-log"),
      stateSerializer = Codecs.serializer[ErasureLog]("erasure-log"),
      eventSerializer = Codecs.serializer[ErasureLogEvent]("erasure-log-event")
    ):
  def create(context: EventSourcedEntityContext) = new ErasureLogEntity
  val Id: String                                 = "installation"
  given Serializer[LogAppend]       = Codecs.serializer[LogAppend]("erasure-log-append")
  given Serializer[ErasureLogEntry] = Codecs.serializer[ErasureLogEntry]("erasure-log-entry")
  given Serializer[ErasureLog]      = stateSerializer
  val append                        = command("append")(_.append)
  val reconcile                     = command("reconcile")(_.reconcile)
  val after                         = query("after")(_.after)
