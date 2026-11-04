package com.thinkmorestupidless.ankka.agent.autonomous

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.agent.{LiveSubjects, Sealing, TokenUsage}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** Where a task is in its life. Completed, failed and cancelled are final. */
enum TaskStatus:
  case Pending, Assigned, InProgress, ResultRejected, Completed, Failed, Cancelled

  def terminal: Boolean = this match
    case Completed | Failed | Cancelled => true
    case _                              => false

object TaskStatus:
  /**
   * A status is a word on the wire, not `{"type":"Pending"}`: the shared codec's discriminator is
   * right for events and wrong for a status a client prints. Declared here, in the companion, so
   * every codec that contains a status uses it.
   */
  given JsonValueCodec[TaskStatus] = new JsonValueCodec[TaskStatus]:
    def nullValue: TaskStatus                                        = null
    def decodeValue(in: JsonReader, default: TaskStatus): TaskStatus =
      val word = in.readString(null)
      TaskStatus.values
        .find(_.wire == word)
        .getOrElse(in.decodeError(s"unknown task status '$word'"))
    def encodeValue(x: TaskStatus, out: JsonWriter): Unit = out.writeVal(x.wire)

  extension (s: TaskStatus)
    /** `pending`, `in-progress`, `result-rejected`… */
    def wire: String = s.toString.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

/** The autonomous agent instance a task is assigned to. */
final case class Assignee(componentId: String, instanceId: String)

/**
 * A task: one piece of work, with its own identity, that outlives the agent doing it.
 *
 * `result` is the canonical JSON of the task type's result, present only when completed; readers
 * decode it with the type. `reason` explains the last rejection, failure, cancellation or
 * unassignment.
 *
 * `subject` is the data subject the task was created with (feature 042): its instructions, its
 * attachments, its result and its reasons are then kept sealed under the subject's key, and
 * `erased` says the key was destroyed, so each of them reads as empty.
 */
final case class TaskRecord(
    id: String,
    typeName: String,
    instructions: String,
    attachments: Vector[Attachment],
    dependencies: Vector[String],
    dependents: Vector[String],
    status: TaskStatus,
    assignee: Option[Assignee],
    result: Option[String],
    reason: Option[String],
    iterations: Int,
    usage: TokenUsage,
    createdAt: Long,
    assignedAt: Option[Long],
    startedAt: Option[Long],
    endedAt: Option[Long],
    /** The agent's definition for this task alone, when the task carries one. */
    definition: Option[TaskDefinition] = None,
    subject: Option[String] = None,
    erased: Boolean = false
):
  def exists: Boolean = createdAt > 0L

object TaskRecord:
  val empty: TaskRecord = TaskRecord(
    "",
    "",
    "",
    Vector.empty,
    Vector.empty,
    Vector.empty,
    TaskStatus.Pending,
    None,
    None,
    None,
    0,
    TokenUsage.zero,
    0L,
    None,
    None,
    None
  )

  /** What a task keeps in the clear: everything but its personal texts. */
  private[autonomous] def redacted(r: TaskRecord): TaskRecord =
    r.copy(instructions = "", attachments = Vector.empty, result = None, reason = None)

  /** A task whose data subject was erased: its personal texts read as empty. */
  private[autonomous] def erasedFrom(r: TaskRecord): TaskRecord = redacted(r).copy(erased = true)

enum TaskEvent:
  case Created(
      id: String,
      typeName: String,
      instructions: String,
      attachments: Vector[Attachment],
      dependencies: Vector[String],
      at: Long,
      definition: Option[TaskDefinition] = None,
      subject: Option[String] = None
  )
  case DependentAdded(taskId: String)
  case Assigned(assignee: Assignee, at: Long)
  case Unassigned(reason: String, at: Long)
  case Started(at: Long)
  case ResultRejected(reason: String, iterations: Int, usage: TokenUsage, at: Long)
  case Completed(result: String, iterations: Int, usage: TokenUsage, at: Long)
  case Failed(reason: String, iterations: Int, usage: TokenUsage, at: Long)
  case Cancelled(reason: String, at: Long)

  /**
   * An event of a task with a data subject that holds a personal text (feature 042): the event's
   * own JSON sealed under the subject's key, beside a clear copy with the texts taken out, which is
   * what it reads as once the key is destroyed.
   */
  case Sealed(clear: String, body: Personal[String])

object TaskEvent:

  /** The event with its personal texts taken out; `None` for one that holds none. */
  private[autonomous] def redacted(e: TaskEvent): Option[TaskEvent] = e match
    case c: Created        => Some(c.copy(instructions = "", attachments = Vector.empty))
    case u: Unassigned     => Some(u.copy(reason = ""))
    case r: ResultRejected => Some(r.copy(reason = ""))
    case c: Completed      => Some(c.copy(result = ""))
    case f: Failed         => Some(f.copy(reason = ""))
    case c: Cancelled      => Some(c.copy(reason = ""))
    case _                 => None

/**
 * The record of one task. The platform registers it; nothing in a service writes to it directly.
 *
 * Every transition not in the task's lifecycle is refused with `Conflict`, and every command but
 * `create` on a task that was never created is `NotFound`, so a caller can tell "no such task" from
 * "not now".
 */
final class TaskEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[TaskRecord, TaskEvent]:

  import TaskEntity.*
  import TaskEvent as E
  import TaskStatus.*

  private val taskId = context.entityId

  def emptyState: TaskRecord = TaskRecord.empty

  def applyEvent(event: TaskEvent): TaskRecord =
    val s = currentState
    event match
      case E.Created(
            id,
            typeName,
            instructions,
            attachments,
            dependencies,
            at,
            definition,
            about
          ) =>
        about.foreach(held)
        TaskRecord.empty.copy(
          id = id,
          typeName = typeName,
          instructions = instructions,
          attachments = attachments,
          dependencies = dependencies,
          createdAt = at,
          definition = definition,
          subject = about
        )
      case E.Sealed(clear, body) =>
        val (inner, gone) = Sealing.open[TaskEvent](body, clear)(using TaskCodecs.event)
        val next          = applyEvent(inner)
        if gone then TaskRecord.erasedFrom(next) else next
      case E.DependentAdded(id) =>
        if s.dependents.contains(id) then s else s.copy(dependents = s.dependents :+ id)
      case E.Assigned(assignee, at) =>
        s.copy(status = Assigned, assignee = Some(assignee), assignedAt = Some(at))
      case E.Unassigned(reason, _) =>
        s.copy(status = Pending, assignee = None, reason = Some(reason), assignedAt = None)
      case E.Started(at) =>
        s.copy(status = InProgress, startedAt = s.startedAt.orElse(Some(at)))
      case E.ResultRejected(reason, iterations, usage, _) =>
        s.copy(
          status = ResultRejected,
          reason = Some(reason),
          iterations = iterations,
          usage = usage
        )
      case E.Completed(result, iterations, usage, at) =>
        s.copy(
          status = Completed,
          result = Some(result),
          reason = None,
          iterations = iterations,
          usage = usage,
          endedAt = Some(at)
        )
      case E.Failed(reason, iterations, usage, at) =>
        s.copy(
          status = Failed,
          reason = Some(reason),
          iterations = iterations,
          usage = usage,
          endedAt = Some(at)
        )
      case E.Cancelled(reason, at) =>
        s.copy(status = Cancelled, reason = Some(reason), endedAt = Some(at))

  /** Notes that this task, in memory, holds `subject`'s conversation (`LiveSubjects`). */
  private def held(subject: String): Unit =
    Option(context.componentClient).foreach(
      LiveSubjects.hold(
        _,
        LiveSubjects.Holder.Entity(TaskEntity.componentId, EntityId(taskId)),
        subject
      )
    )

  /**
   * `event` as the task keeps it (feature 042): as it is with no data subject, or when it holds no
   * personal text; else sealed under the subject's key, or written erased once the subject is.
   */
  private def sealedOr(
      event: TaskEvent,
      subject: Option[String] = currentState.subject
  ): TaskEvent =
    (subject.flatMap(DataSubject.from(_).toOption), TaskEvent.redacted(event)) match
      case (Some(s), Some(clear)) =>
        E.Sealed(
          Sealing.clear(clear)(using TaskCodecs.event),
          Sealing.seal(s, event, erased = currentState.erased)(using TaskCodecs.event)
        )
      case _ => event

  // ── Commands ──────────────────────────────────────────────────────────────

  def create(request: Create): Effect[Done] =
    if currentState.exists then effects.error(s"task '$taskId' already exists", ErrorCode.Conflict)
    else if request.typeName.isEmpty then effects.error("a task needs a type")
    else if request.dependencies.contains(taskId) then
      effects.error(s"task '$taskId' cannot depend on itself")
    else
      request.subject.map(Sealing.subjectOf) match
        case Some(Left(problem)) => effects.error(problem.message, problem.code)
        case _                   =>
          effects
            .persist(
              sealedOr(
                E.Created(
                  taskId,
                  request.typeName,
                  request.instructions,
                  request.attachments,
                  request.dependencies.distinct,
                  now(),
                  request.definition,
                  request.subject
                ),
                request.subject
              )
            )
            .thenReply(_ => Done)

  /**
   * Records that another task depends on this one.
   *
   * On a task that has already ended the answer says so instead, so the creator of the dependent
   * can cancel it at once: a dependent indexed on a task that has already failed would never be
   * told.
   */
  def addDependent(request: AddDependent): Effect[DependentAdded] =
    withTask { s =>
      if s.status.terminal then effects.reply(DependentAdded(Some(s.status), s.reason))
      else
        effects.persist(E.DependentAdded(request.taskId)).thenReply(_ => DependentAdded(None, None))
    }

  def assign(assignee: Assignee): Effect[Done] =
    withTask { s =>
      s.status match
        case Pending => effects.persist(E.Assigned(assignee, now())).thenReply(_ => Done)
        // A retried assignment by the same instance, after a timeout, is not a conflict.
        case Assigned | InProgress | ResultRejected if s.assignee.contains(assignee) =>
          effects.reply(Done)
        case other => refuse(s"cannot assign a task that is ${other.wire}")
    }

  def unassign(request: Unassign): Effect[Done] =
    withTask { s =>
      s.status match
        case Assigned | InProgress | ResultRejected =>
          effects.persist(sealedOr(E.Unassigned(request.reason, now()))).thenReply(_ => Done)
        case other => refuse(s"cannot unassign a task that is ${other.wire}")
    }

  def start: Effect[Done] =
    withTask { s =>
      s.status match
        case Assigned | ResultRejected => effects.persist(E.Started(now())).thenReply(_ => Done)
        case InProgress                => effects.reply(Done)
        case other                     => refuse(s"cannot start a task that is ${other.wire}")
    }

  def rejectResult(request: RejectResult): Effect[Done] =
    withTask { s =>
      s.status match
        case InProgress =>
          effects
            .persist(
              sealedOr(E.ResultRejected(request.reason, request.iterations, request.usage, now()))
            )
            .thenReply(_ => Done)
        case other => refuse(s"cannot reject the result of a task that is ${other.wire}")
    }

  def complete(request: Complete): Effect[Done] =
    withTask { s =>
      s.status match
        case InProgress | ResultRejected =>
          effects
            .persist(
              sealedOr(E.Completed(request.result, request.iterations, request.usage, now()))
            )
            .thenReply(_ => Done)
        case other => refuse(s"cannot complete a task that is ${other.wire}")
    }

  def fail(request: Fail): Effect[Done] =
    withTask { s =>
      s.status match
        case Assigned | InProgress | ResultRejected =>
          effects
            .persist(sealedOr(E.Failed(request.reason, request.iterations, request.usage, now())))
            .thenReply(_ => Done)
        case other => refuse(s"cannot fail a task that is ${other.wire}")
    }

  def cancel(request: Cancel): Effect[Done] =
    withTask { s =>
      if s.status.terminal then refuse(s"cannot cancel a task that is ${s.status.wire}")
      else effects.persist(sealedOr(E.Cancelled(request.reason, now()))).thenReply(_ => Done)
    }

  def get: ReadOnlyEffect[TaskRecord] =
    if currentState.exists then effects.reply(currentState)
    else effects.error(s"no task '$taskId'", ErrorCode.NotFound)

  private def withTask[R](f: TaskRecord => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState)
    else effects.error(s"no task '$taskId'", ErrorCode.NotFound)

  private def refuse[R](message: String): Effect[R] =
    effects.error(s"task '$taskId': $message", ErrorCode.Conflict)

  // A command handler may read the clock; the fold may not, which is why the time is in the event.
  private def now(): Long = System.currentTimeMillis()

/**
 * The task's codecs, apart from its companion, whose constructor names them before its own fields
 * are set.
 */
private[autonomous] object TaskCodecs:
  given event: JsonValueCodec[TaskEvent]   = Codecs.make[TaskEvent]
  given record: JsonValueCodec[TaskRecord] = Codecs.make[TaskRecord]

  /**
   * The record as a snapshot keeps it: sealed whole under the task's data subject when it has one.
   */
  val events: Serializer[TaskEvent] = Sealing.eventSerializer[TaskEvent](
    "task-event",
    {
      case TaskEvent.Sealed(clear, Personal.Present(subject, _)) =>
        Some(TaskEvent.Sealed(clear, Personal.Erased(subject)))
      case _ => None
    }
  )

  val state: Serializer[TaskRecord] = Sealing.stateSerializer[TaskRecord](
    "task-record",
    _.subject,
    _.erased,
    TaskRecord.redacted,
    TaskRecord.erasedFrom
  )

object TaskEntity
    extends EventSourcedEntity.Companion[TaskEntity, TaskRecord, TaskEvent](
      componentId = ComponentId("ankka-task"),
      stateSerializer = TaskCodecs.state,
      eventSerializer = TaskCodecs.events
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  final case class Create(
      typeName: String,
      instructions: String,
      attachments: Vector[Attachment] = Vector.empty,
      dependencies: Vector[String] = Vector.empty,
      definition: Option[TaskDefinition] = None,
      /**
       * The data subject the task is about (feature 042), which its personal texts are sealed
       * under.
       */
      subject: Option[String] = None
  )
  final case class AddDependent(taskId: String)

  /** `None` when recorded; the dependency's final status when it had already ended. */
  final case class DependentAdded(alreadyEnded: Option[TaskStatus], reason: Option[String])
  final case class Unassign(reason: String)
  final case class RejectResult(reason: String, iterations: Int, usage: TokenUsage)
  final case class Complete(result: String, iterations: Int, usage: TokenUsage)
  final case class Fail(reason: String, iterations: Int, usage: TokenUsage)
  final case class Cancel(reason: String)

  given Serializer[Create]         = Codecs.serializer[Create]("task-create")
  given Serializer[AddDependent]   = Codecs.serializer[AddDependent]("task-add-dependent")
  given Serializer[DependentAdded] = Codecs.serializer[DependentAdded]("task-dependent-added")
  given Serializer[Assignee]       = Codecs.serializer[Assignee]("task-assign")
  given Serializer[Unassign]       = Codecs.serializer[Unassign]("task-unassign")
  given Serializer[RejectResult]   = Codecs.serializer[RejectResult]("task-reject")
  given Serializer[Complete]       = Codecs.serializer[Complete]("task-complete")
  given Serializer[Fail]           = Codecs.serializer[Fail]("task-fail")
  given Serializer[Cancel]         = Codecs.serializer[Cancel]("task-cancel")

  def create(context: EventSourcedEntityContext) = new TaskEntity(context)

  val createTask   = command("create")(_.create)
  val addDependent = command("add-dependent")(_.addDependent)
  val assign       = command("assign")(_.assign)
  val unassign     = command("unassign")(_.unassign)
  val start        = command("start")(_.start)
  val rejectResult = command("reject-result")(_.rejectResult)
  val complete     = command("complete")(_.complete)
  val fail         = command("fail")(_.fail)
  val cancel       = command("cancel")(_.cancel)
  val get          = query("get")(_.get)
