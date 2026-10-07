package com.thinkmorestupidless.ankka.agent.autonomous

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.agent.{ApprovalRequest, Decision, TokenUsage}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

/** What an instance is doing, derived from its record. */
enum Phase:
  case Idle, Working, Waiting, Suspended, Terminated

object Phase:
  /** A word on the wire, for the reason `TaskStatus` is one. */
  given JsonValueCodec[Phase] = new JsonValueCodec[Phase]:
    def nullValue: Phase = null
    def decodeValue(in: JsonReader, default: Phase): Phase =
      val word = in.readString(null)
      Phase.values
        .find(_.toString.toLowerCase == word)
        .getOrElse(in.decodeError(s"unknown phase '$word'"))
    def encodeValue(x: Phase, out: JsonWriter): Unit = out.writeVal(x.toString.toLowerCase)

/** The ways an instance can be struggling with a task, each warned about once. */
enum Struggle:
  case ApproachingBudget, RepeatedFailure, DependencyStuck

object Struggle:
  given JsonValueCodec[Struggle] = new JsonValueCodec[Struggle]:
    def nullValue: Struggle = null
    def decodeValue(in: JsonReader, default: Struggle): Struggle =
      val word = in.readString(null)
      Struggle.values
        .find(_.toString == word)
        .getOrElse(in.decodeError(s"unknown struggle '$word'"))
    def encodeValue(x: Struggle, out: JsonWriter): Unit = out.writeVal(x.toString)

/** How an instance's work on one task ended. */
enum TaskOutcome:
  case Completed
  case Failed(reason: String)
  case Cancelled(reason: String)

/**
 * The task an instance is working on, and how that is going.
 *
 * `iteration` is the last iteration started and `completed` the last whose model response was
 * recorded; `iterationStartedAt` is when `iteration` started, which is always later than anything
 * already in the task's session. Together they are how an instance that stopped mid-iteration tells
 * what landed: a model response in the session at or after `iterationStartedAt` is this
 * iteration's.
 *
 * `approvals` are the approval requests of the tool calls in the current iteration's response,
 * recorded before any tool of that response runs and cleared when the next iteration starts. While
 * one is awaiting a decision the instance makes no model call and starts no iteration.
 */
final case class Working(
    taskId: String,
    iteration: Int,
    completed: Int,
    iterationStartedAt: Long,
    started: Boolean,
    consecutiveFailures: Int,
    warned: Set[Struggle],
    approvals: Vector[ApprovalRequest] = Vector.empty
):
  def awaiting: Vector[ApprovalRequest] = approvals.filter(_.awaiting)

/**
 * One instance of an autonomous agent, as a durable record.
 *
 * Written only by the instance's own host, which is why its commands are the events themselves: the
 * host knows what happened, and the entity's job is to refuse what cannot have — anything after
 * termination, an iteration with no task, a task selected that was never queued — and to make it
 * survive the process.
 */
final case class InstanceRecord(
    componentId: String,
    instanceId: String,
    created: Boolean,
    terminateWhenDone: Boolean,
    queue: Vector[String],
    current: Option[Working],
    suspended: Boolean,
    terminated: Boolean,
    usage: TokenUsage,
    taskUsage: TokenUsage,
    lastActiveAt: Long
):
  def phase: Phase =
    if terminated then Phase.Terminated
    else if suspended then Phase.Suspended
    else
      current match
        case Some(_) => Phase.Working
        // Queued work and nothing started: every queued task is waiting on a dependency, or the
        // instance is between tasks.
        case None if queue.nonEmpty => Phase.Waiting
        case None                   => Phase.Idle

  /** Whether the instance has anything to do. */
  def busy: Boolean = current.isDefined || queue.nonEmpty

object InstanceRecord:
  def empty(componentId: String, instanceId: String): InstanceRecord =
    InstanceRecord(
      componentId,
      instanceId,
      created = false,
      terminateWhenDone = false,
      Vector.empty,
      None,
      suspended = false,
      terminated = false,
      TokenUsage.zero,
      TokenUsage.zero,
      0L
    )

enum InstanceEvent:
  case Created(terminateWhenDone: Boolean, at: Long)
  case TasksAssigned(taskIds: Vector[String], at: Long)
  case TaskDequeued(taskId: String, reason: String, at: Long)
  case TaskSelected(taskId: String, at: Long)
  case TaskStarted(taskId: String, at: Long)
  case IterationStarted(iteration: Int, at: Long)
  case IterationFailed(iteration: Int, error: String, at: Long)
  case IterationCompleted(iteration: Int, usage: TokenUsage, at: Long)
  case StruggleNoted(struggle: Struggle, reset: Boolean)
  case TaskEnded(taskId: String, outcome: TaskOutcome, iterations: Int, at: Long)
  case Suspended(at: Long)
  case Resumed(at: Long)
  case Terminated(at: Long)

  /** A tool call of the current iteration requires approval; recorded before any tool runs. */
  case ApprovalRequested(request: ApprovalRequest)

  /** A decision on one of the current iteration's approval requests. */
  case ApprovalDecided(approvalId: String, decision: Decision)

final class InstanceEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[InstanceRecord, InstanceEvent]:

  import InstanceEvent as E

  private val (componentId, instanceId) = InstanceEntity.split(context.entityId)

  def emptyState: InstanceRecord = InstanceRecord.empty(componentId, instanceId)

  def applyEvent(event: InstanceEvent): InstanceRecord =
    val s                              = currentState
    def touch(at: Long)                = s.copy(lastActiveAt = at)
    def working(f: Working => Working) = s.copy(current = s.current.map(f))
    event match
      case E.Created(terminateWhenDone, at) =>
        s.copy(created = true, terminateWhenDone = terminateWhenDone, lastActiveAt = at)
      case E.TasksAssigned(ids, at) =>
        val fresh =
          ids.distinct.filterNot(id => s.queue.contains(id) || s.current.exists(_.taskId == id))
        touch(at).copy(queue = s.queue ++ fresh)
      case E.TaskDequeued(id, _, at) =>
        touch(at).copy(queue = s.queue.filterNot(_ == id))
      case E.TaskSelected(id, at) =>
        touch(at).copy(
          queue = s.queue.filterNot(_ == id),
          current = Some(Working(id, 0, 0, 0L, started = false, 0, Set.empty)),
          taskUsage = TokenUsage.zero
        )
      case E.TaskStarted(_, at) =>
        working(_.copy(started = true)).copy(lastActiveAt = at)
      case E.IterationStarted(n, at) =>
        working(_.copy(iteration = n, iterationStartedAt = at, approvals = Vector.empty))
          .copy(lastActiveAt = at)
      case E.IterationFailed(_, _, at) =>
        working(w => w.copy(consecutiveFailures = w.consecutiveFailures + 1))
          .copy(lastActiveAt = at)
      case E.IterationCompleted(n, used, at) =>
        working(_.copy(completed = n, consecutiveFailures = 0)).copy(
          usage = s.usage + used,
          taskUsage = s.taskUsage + used,
          lastActiveAt = at
        )
      case E.StruggleNoted(struggle, reset) =>
        working(w => w.copy(warned = if reset then w.warned - struggle else w.warned + struggle))
      case E.TaskEnded(_, _, _, at) =>
        touch(at).copy(current = None)
      case E.Suspended(at) => touch(at).copy(suspended = true)
      case E.Resumed(at)   => touch(at).copy(suspended = false)
      case E.Terminated(at) =>
        touch(at).copy(terminated = true, suspended = false, current = None, queue = Vector.empty)
      case E.ApprovalRequested(request) =>
        working(w => w.copy(approvals = w.approvals :+ request))
      case E.ApprovalDecided(id, decision) =>
        working(w =>
          w.copy(approvals =
            w.approvals.map(r => if r.id == id then r.copy(decision = Some(decision)) else r)
          )
        )

  /** Records what the host did, refusing what cannot have happened. */
  def record(event: InstanceEvent): Effect[InstanceRecord] =
    val s = currentState
    problem(s, event) match
      case Some((message, code))       => effects.error(s"instance '$instanceId': $message", code)
      case None if redundant(s, event) => effects.reply(s)
      case None                        => effects.persist(event).thenReplyState

  def get: ReadOnlyEffect[InstanceRecord] = effects.reply(currentState)

  private def redundant(s: InstanceRecord, event: InstanceEvent): Boolean = event match
    case _: E.Created    => s.created
    case _: E.Terminated => s.terminated
    // Settling a response again after a stop finds its requests already recorded.
    case E.ApprovalRequested(request) =>
      s.current.exists(_.approvals.exists(_.callId == request.callId))
    case E.StruggleNoted(struggle, reset) =>
      s.current.forall(w => w.warned.contains(struggle) != reset)
    case _ => false

  private def problem(s: InstanceRecord, event: InstanceEvent): Option[(String, ErrorCode)] =
    import ErrorCode.*
    def conflict(message: String) = Some(message -> Conflict)
    def needsTask(id: Option[String]) =
      s.current match
        case None => conflict("it is not working on a task")
        case Some(w) if id.exists(_ != w.taskId) =>
          conflict(s"it is working on '${w.taskId}', not '${id.get}'")
        case _ => None
    event match
      // Termination burns the id: nothing, not even creation, follows it.
      case _: E.Created if s.terminated   => conflict("it has been terminated")
      case _: E.Created | _: E.Terminated => None
      case _ if !s.created                => Some("it was never created" -> NotFound)
      case _ if s.terminated              => conflict("it has been terminated")
      case E.Suspended(_) if s.suspended  => conflict("it is already suspended")
      case E.Resumed(_) if !s.suspended   => conflict("it is not suspended")
      case E.TaskSelected(id, _) =>
        if s.current.isDefined then conflict(s"it is already working on '${s.current.get.taskId}'")
        else if !s.queue.contains(id) then conflict(s"task '$id' is not queued")
        else None
      case E.TaskDequeued(id, _, _) if !s.queue.contains(id) =>
        conflict(s"task '$id' is not queued")
      case E.TaskStarted(id, _)     => needsTask(Some(id))
      case E.TaskEnded(id, _, _, _) => needsTask(Some(id))
      case _: E.IterationStarted | _: E.IterationFailed | _: E.IterationCompleted |
          _: E.StruggleNoted | _: E.ApprovalRequested =>
        needsTask(None)
      case E.ApprovalDecided(id, decision) =>
        Decision.problem(decision).map(_ -> BadRequest).orElse {
          s.current.flatMap(_.approvals.find(_.id == id)) match
            case None                   => Some(s"it holds no approval request '$id'" -> NotFound)
            case Some(r) if !r.awaiting => conflict(s"approval request '$id' is decided")
            case Some(_)                => None
        }
      case _ => None

object InstanceEntity
    extends EventSourcedEntity.Companion[InstanceEntity, InstanceRecord, InstanceEvent](
      componentId = ComponentId("ankka-agent-instance"),
      stateSerializer = Codecs.serializer[InstanceRecord]("agent-instance-record"),
      eventSerializer = Codecs.serializer[InstanceEvent]("agent-instance-event")
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  /** One entity type serves every autonomous agent, so its id names both. */
  def idFor(componentId: ComponentId, instanceId: String): EntityId =
    EntityId(s"$componentId/$instanceId")

  private[autonomous] def split(entityId: String): (String, String) =
    entityId.indexOf('/') match
      case -1 => ("", entityId)
      case i  => (entityId.take(i), entityId.drop(i + 1))

  def create(context: EventSourcedEntityContext) = new InstanceEntity(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
