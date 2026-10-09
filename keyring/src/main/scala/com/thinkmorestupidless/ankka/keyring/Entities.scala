package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * One data subject's key in one project (id `<project>/<subject>`): the key wrapped by the
 * project's key-encryption key, or a tombstone once an erasure has destroyed it. A tombstone is
 * kept for ever, so no later write makes a key for the subject again (FR-005).
 */
final case class SubjectKey(
    wrapped: Option[String],
    erasureId: Option[String],
    destroyedAt: Option[Long],
    everExisted: Boolean,
    refusals: Long,
    decryptions: Long
)

/** The keyring's answer about one subject: `key`, `erased` or `unknown`. */
final case class KeyAnswer(kind: String, wrapped: Option[String], erasureId: Option[String])

/** Asks for the subject's key, offering `wrapped` as the key to keep if it has none yet. */
final case class AskKey(wrapped: Option[String])

final case class Destroy(erasureId: String, at: Long)

final class SubjectKeyEntity extends KeyValueEntity[SubjectKey]:
  def emptyState: SubjectKey = SubjectKey(None, None, None, everExisted = false, 0, 0)

  def ask(request: AskKey): Effect[KeyAnswer] =
    currentState match
      case s if s.erasureId.isDefined => effects.reply(KeyAnswer("erased", None, s.erasureId))
      case s if s.wrapped.isDefined   => effects.reply(KeyAnswer("key", s.wrapped, None))
      case s =>
        request.wrapped match
          // The first offer kept: whichever instance or service asks first makes the key, once.
          case Some(offer) =>
            effects
              .updateState(s.copy(wrapped = Some(offer), everExisted = true))
              .thenReply(_ => KeyAnswer("key", Some(offer), None))
          case None => effects.reply(KeyAnswer("unknown", None, None))

  def destroy(request: Destroy): Effect[KeyAnswer] =
    if currentState.erasureId.isDefined then
      effects.reply(KeyAnswer("erased", None, currentState.erasureId))
    else
      effects
        .updateState(
          SubjectKey(
            None,
            Some(request.erasureId),
            Some(request.at),
            currentState.wrapped.isDefined || currentState.everExisted,
            currentState.refusals,
            currentState.decryptions
          )
        )
        .thenReply(s => KeyAnswer("erased", None, s.erasureId))

  def refused: Effect[Done] = effects
    .updateState(currentState.copy(refusals = currentState.refusals + 1))
    .thenReply(_ => Done)

  def decrypted: Effect[Done] = effects
    .updateState(currentState.copy(decryptions = currentState.decryptions + 1))
    .thenReply(_ => Done)

  def state: ReadOnlyEffect[SubjectKey] = effects.reply(currentState)

object SubjectKeyEntity
    extends KeyValueEntity.Companion[SubjectKeyEntity, SubjectKey](
      componentId = ComponentId("keyring-subject-key"),
      stateSerializer = Codecs.serializer[SubjectKey]("keyring-subject-key")
    ):
  def create(context: KeyValueEntityContext) = new SubjectKeyEntity

  given Serializer[AskKey]     = Codecs.serializer[AskKey]("keyring-ask-key")
  given Serializer[KeyAnswer]  = Codecs.serializer[KeyAnswer]("keyring-key-answer")
  given Serializer[Destroy]    = Codecs.serializer[Destroy]("keyring-destroy")
  given Serializer[SubjectKey] = stateSerializer

  val ask       = command("ask")(_.ask)
  val destroy   = command("destroy")(_.destroy)
  val refused   = command("refused")(_.refused)
  val decrypted = command("decrypted")(_.decrypted)
  val state     = query("state")(_.state)

/** A project's key-encryption key and lookup key, each wrapped by the root key (id `<project>`). */
final case class ProjectKeys(kek: Option[String], lookup: Option[String])

final class ProjectKeyEntity extends KeyValueEntity[ProjectKeys]:
  def emptyState: ProjectKeys = ProjectKeys(None, None)

  /** The project's keys, keeping `offer` only where it has none: one pair, whoever asks first. */
  def ensure(offer: ProjectKeys): Effect[ProjectKeys] =
    if currentState.kek.isDefined then effects.reply(currentState)
    else effects.updateState(offer).thenReply(identity)

object ProjectKeyEntity
    extends KeyValueEntity.Companion[ProjectKeyEntity, ProjectKeys](
      componentId = ComponentId("keyring-project-keys"),
      stateSerializer = Codecs.serializer[ProjectKeys]("keyring-project-keys")
    ):
  def create(context: KeyValueEntityContext) = new ProjectKeyEntity
  given Serializer[ProjectKeys]              = stateSerializer
  val ensure                                 = command("ensure")(_.ensure)

/** One applied erasure in a project's log, as the keyring holds it. */
final case class Applied(erasureId: String, sequence: Long, subject: String, destroyedAt: Long)

enum ProjectLogEvent:
  case Appended(entry: Applied)

final case class ProjectLog(entries: Vector[Applied])

/**
 * A project's erasure log as the keyring has applied it (id `<project>`): what a service is sent,
 * past the highest it applied, when it opens its channel.
 */
final class ProjectLogEntity extends EventSourcedEntity[ProjectLog, ProjectLogEvent]:
  def emptyState: ProjectLog = ProjectLog(Vector.empty)

  def applyEvent(event: ProjectLogEvent): ProjectLog = event match
    case ProjectLogEvent.Appended(entry) => ProjectLog(currentState.entries :+ entry)

  def append(entry: Applied): Effect[Done] =
    if currentState.entries.exists(_.erasureId == entry.erasureId) then effects.reply(Done)
    else effects.persist(ProjectLogEvent.Appended(entry)).thenReply(_ => Done)

  def after(sequence: Long): ReadOnlyEffect[ProjectLog] =
    effects.reply(ProjectLog(currentState.entries.filter(_.sequence > sequence).sortBy(_.sequence)))

object ProjectLogEntity
    extends EventSourcedEntity.Companion[ProjectLogEntity, ProjectLog, ProjectLogEvent](
      componentId = ComponentId("keyring-project-log"),
      stateSerializer = Codecs.serializer[ProjectLog]("keyring-project-log"),
      eventSerializer = Codecs.serializer[ProjectLogEvent]("keyring-project-log-event")
    ):
  def create(context: EventSourcedEntityContext) = new ProjectLogEntity
  given Serializer[Applied]                      = Codecs.serializer[Applied]("keyring-applied")
  given Serializer[ProjectLog]                   = stateSerializer
  val append                                     = command("append")(_.append)
  val after                                      = query("after")(_.after)

/** One channel told of an erasure, and what it answered. */
final case class Told(
    service: String,
    instance: String,
    acknowledged: Boolean,
    closedUnacknowledged: Boolean
)

final case class CompletionRecord(
    service: String,
    instance: String,
    completedAt: Long,
    viewsRedacted: Vector[String],
    rowsRedacted: Long,
    sessionsMarked: Int,
    instancesStopped: Int,
    handlerOk: Option[Boolean],
    handlerDetail: Option[String],
    objectsErased: Option[Long],
    objectsFinalAt: Option[Long]
)

final case class ErasureRecord(
    project: String,
    subject: String,
    sequence: Long,
    keyDestroyedAt: Long,
    everExisted: Boolean,
    told: Map[String, Told],
    completions: Vector[CompletionRecord]
)

enum ErasureEvent:
  case Started(
      project: String,
      subject: String,
      sequence: Long,
      keyDestroyedAt: Long,
      everExisted: Boolean
  )
  case Notified(channel: String, service: String, instance: String)
  case Acknowledged(channel: String)
  case ClosedUnacknowledged(channel: String)
  case Completed(record: CompletionRecord)

final case class Start(
    project: String,
    subject: String,
    sequence: Long,
    keyDestroyedAt: Long,
    everExisted: Boolean
)
final case class Notify(channel: String, service: String, instance: String)

/**
 * Where one erasure stands in the keyring (id `<erasureId>`): who was told, who answered, who
 * completed.
 */
final class ErasureEntity extends EventSourcedEntity[ErasureRecord, ErasureEvent]:
  def emptyState: ErasureRecord =
    ErasureRecord("", "", 0, 0, everExisted = false, Map.empty, Vector.empty)

  def applyEvent(event: ErasureEvent): ErasureRecord = event match
    case ErasureEvent.Started(p, s, seq, at, ever) =>
      currentState.copy(
        project = p,
        subject = s,
        sequence = seq,
        keyDestroyedAt = at,
        everExisted = ever
      )
    case ErasureEvent.Notified(c, service, instance) =>
      currentState.copy(told =
        currentState.told
          .updated(c, Told(service, instance, acknowledged = false, closedUnacknowledged = false))
      )
    case ErasureEvent.Acknowledged(c) =>
      currentState.copy(told = currentState.told.updatedWith(c)(_.map(_.copy(acknowledged = true))))
    case ErasureEvent.ClosedUnacknowledged(c) =>
      currentState.copy(told =
        currentState.told.updatedWith(c)(_.map(_.copy(closedUnacknowledged = true)))
      )
    case ErasureEvent.Completed(record) =>
      currentState.copy(completions = currentState.completions :+ record)

  def start(request: Start): Effect[Done] =
    if currentState.project.nonEmpty then effects.reply(Done)
    else
      effects
        .persist(
          ErasureEvent.Started(
            request.project,
            request.subject,
            request.sequence,
            request.keyDestroyedAt,
            request.everExisted
          )
        )
        .thenReply(_ => Done)

  def notified(request: Notify): Effect[Done] =
    if currentState.told.contains(request.channel) then effects.reply(Done)
    else
      effects
        .persist(ErasureEvent.Notified(request.channel, request.service, request.instance))
        .thenReply(_ => Done)

  def acknowledged(channel: String): Effect[Done] =
    if currentState.told.get(channel).exists(_.acknowledged) then effects.reply(Done)
    else effects.persist(ErasureEvent.Acknowledged(channel)).thenReply(_ => Done)

  def unacknowledged(channel: String): Effect[Done] =
    if currentState.told.get(channel).forall(t => t.acknowledged || t.closedUnacknowledged) then
      effects.reply(Done)
    else effects.persist(ErasureEvent.ClosedUnacknowledged(channel)).thenReply(_ => Done)

  def completed(record: CompletionRecord): Effect[Done] =
    effects.persist(ErasureEvent.Completed(record)).thenReply(_ => Done)

  def get: ReadOnlyEffect[ErasureRecord] = effects.reply(currentState)

object ErasureEntity
    extends EventSourcedEntity.Companion[ErasureEntity, ErasureRecord, ErasureEvent](
      componentId = ComponentId("keyring-erasure"),
      stateSerializer = Codecs.serializer[ErasureRecord]("keyring-erasure"),
      eventSerializer = Codecs.serializer[ErasureEvent]("keyring-erasure-event")
    ):
  def create(context: EventSourcedEntityContext) = new ErasureEntity
  given Serializer[Start]                        = Codecs.serializer[Start]("keyring-start")
  given Serializer[Notify]                       = Codecs.serializer[Notify]("keyring-notify")
  given Serializer[CompletionRecord] = Codecs.serializer[CompletionRecord]("keyring-completion")
  given Serializer[ErasureRecord]    = stateSerializer
  val start                          = command("start")(_.start)
  val notified                       = command("notified")(_.notified)
  val acknowledged                   = command("acknowledged")(_.acknowledged)
  val unacknowledged                 = command("unacknowledged")(_.unacknowledged)
  val completed                      = command("completed")(_.completed)
  val get                            = query("get")(_.get)
