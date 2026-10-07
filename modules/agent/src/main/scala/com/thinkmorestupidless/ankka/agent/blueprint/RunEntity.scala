package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*

/** Where a run stands. */
enum RunStatus:
  case Running, WaitingForDecision, Completed, Failed, Cancelled

  def ended: Boolean = this match
    case Completed | Failed | Cancelled => true
    case _                              => false

  def wire: String = this match
    case Running            => "running"
    case WaitingForDecision => "waiting-for-decision"
    case Completed          => "completed"
    case Failed             => "failed"
    case Cancelled          => "cancelled"

object RunStatus:
  def fromWire(text: String): Option[RunStatus] = values.find(_.wire == text)

  /** A fieldless enum would otherwise be written as `{"type":"Running"}`. */
  given JsonValueCodec[RunStatus] = new JsonValueCodec[RunStatus]:
    def decodeValue(in: JsonReader, default: RunStatus): RunStatus =
      val text = in.readString(null)
      fromWire(text).getOrElse(in.decodeError(s"not a run status: '$text'"))
    def encodeValue(x: RunStatus, out: JsonWriter): Unit = out.writeVal(x.wire)
    def nullValue: RunStatus                             = null

/** An approval request a step is waiting on: where it is, which, and for what tool. */
final case class ApprovalRef(session: String, approvalId: String, tool: String)

/** One item of a for-each step. */
final case class ItemRecord(
    index: Int,
    result: Option[String],
    failure: Option[String],
    session: String,
    usage: TokenUsage
)

/** One round of a critique step. */
final case class RoundRecord(
    round: Int,
    draft: String,
    passed: Boolean,
    reasons: Vector[String],
    sessions: Vector[String],
    usage: TokenUsage
)

/** One step of a run, as far as it has got. */
final case class StepRecord(
    name: String,
    ended: Boolean = false,
    result: Option[String] = None,
    items: Vector[ItemRecord] = Vector.empty,
    rounds: Vector[RoundRecord] = Vector.empty,
    sessions: Vector[String] = Vector.empty,
    usage: TokenUsage = TokenUsage.zero,
    judgmentUsage: TokenUsage = TokenUsage.zero,
    modelCalls: Int = 0,
    waiting: Vector[ApprovalRef] = Vector.empty
)

/** The record of one run: its blueprint version, its input, each step so far, and how it stands. */
final case class RunRecord(
    runId: String,
    blueprint: String = "",
    version: Int = 0,
    input: String = "",
    status: RunStatus = RunStatus.Running,
    steps: Vector[StepRecord] = Vector.empty,
    startedBy: String = "",
    startedAt: Long = 0L,
    endedAt: Option[Long] = None,
    reason: Option[String] = None,
    deadline: Option[Long] = None,
    cancelRequested: Option[String] = None
):
  def exists: Boolean                        = startedAt > 0L
  def step(name: String): Option[StepRecord] = steps.find(_.name == name)
  def current: Option[StepRecord]            = steps.find(!_.ended)
  def usage: TokenUsage                      = steps.map(_.usage).foldLeft(TokenUsage.zero)(_ + _)
  def judgmentUsage: TokenUsage     = steps.map(_.judgmentUsage).foldLeft(TokenUsage.zero)(_ + _)
  def modelCalls: Int               = steps.map(_.modelCalls).sum
  def awaiting: Vector[ApprovalRef] = steps.flatMap(_.waiting)

  private[blueprint] def withStep(name: String)(f: StepRecord => StepRecord): RunRecord =
    if steps.exists(_.name == name) then
      copy(steps = steps.map(s => if s.name == name then f(s) else s))
    else copy(steps = steps :+ f(StepRecord(name)))

enum RunEvent:
  case Started(
      blueprint: String,
      version: Int,
      input: String,
      startedBy: String,
      deadline: Option[Long],
      at: Long
  )
  case StepStarted(step: String, at: Long)
  case ItemEnded(step: String, item: ItemRecord)
  case RoundEnded(step: String, round: RoundRecord)
  case WaitingForDecision(step: String, request: ApprovalRef, at: Long)
  case DecisionReceived(step: String, approvalId: String, at: Long)

  /** The requests the host refused itself, because the run ended while they awaited a decision. */
  case ApprovalsRefused(step: String, approvalIds: Vector[String], at: Long)
  case StepEnded(
      step: String,
      result: String,
      sessions: Vector[String],
      usage: TokenUsage,
      judgmentUsage: TokenUsage,
      modelCalls: Int,
      at: Long
  )
  case CancelRequested(by: String, at: Long)
  case Ended(status: RunStatus, reason: Option[String], at: Long)

/**
 * The record of one run. The platform registers it; `start` and `cancel` are a caller's, and
 * `record` is the host's alone, by convention as an agent instance's record is.
 */
final class RunEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[RunRecord, RunEvent]:

  import RunEntity.*
  import RunEvent as E

  private val runId = context.entityId

  def emptyState: RunRecord = RunRecord(runId)

  def applyEvent(event: RunEvent): RunRecord =
    val s = currentState
    event match
      case E.Started(blueprint, version, input, by, deadline, at) =>
        RunRecord(
          runId,
          blueprint,
          version,
          input,
          RunStatus.Running,
          Vector.empty,
          by,
          at,
          None,
          None,
          deadline
        )
      case E.StepStarted(step, _) => s.withStep(step)(identity)
      case E.ItemEnded(step, item) =>
        s.withStep(step)(r => r.copy(items = r.items.filterNot(_.index == item.index) :+ item))
      case E.RoundEnded(step, round) => s.withStep(step)(r => r.copy(rounds = r.rounds :+ round))
      case E.WaitingForDecision(step, request, _) =>
        s.copy(status = RunStatus.WaitingForDecision)
          .withStep(step)(r =>
            r.copy(waiting = r.waiting.filterNot(_.approvalId == request.approvalId) :+ request)
          )
      case E.DecisionReceived(step, id, _) =>
        val next = s.withStep(step)(r => r.copy(waiting = r.waiting.filterNot(_.approvalId == id)))
        if next.awaiting.isEmpty then next.copy(status = RunStatus.Running) else next
      case E.ApprovalsRefused(step, ids, _) =>
        s.withStep(step)(r =>
          r.copy(waiting = r.waiting.filterNot(w => ids.contains(w.approvalId)))
        )
      case E.StepEnded(step, result, sessions, usage, judgmentUsage, calls, _) =>
        s.withStep(step)(r =>
          r.copy(
            ended = true,
            result = Some(result),
            sessions = (r.sessions ++ sessions).distinct,
            usage = usage,
            judgmentUsage = judgmentUsage,
            modelCalls = calls,
            waiting = Vector.empty
          )
        )
      case E.CancelRequested(by, _) => s.copy(cancelRequested = Some(by))
      case E.Ended(status, reason, at) =>
        s.copy(status = status, reason = reason, endedAt = Some(at))

  // ── Commands ──────────────────────────────────────────────────────────────

  /** Checked by the caller against the version's input shape; the entity decides only identity. */
  def start(request: Start): Effect[RunRecord] =
    if currentState.exists then
      if currentState.blueprint == request.blueprint && currentState.input == request.input then
        effects.reply(currentState)
      else
        effects.error(
          s"run '$runId' is already held for blueprint '${currentState.blueprint}' with another input",
          ErrorCode.Conflict
        )
    else
      effects
        .persist(
          E.Started(
            request.blueprint,
            request.version,
            request.input,
            request.startedBy,
            request.deadline,
            now()
          )
        )
        .thenReply(_ => currentState)

  def record(event: RunEvent): Effect[RunRecord] =
    if !currentState.exists then effects.error(s"no run '$runId'", ErrorCode.NotFound)
    else if currentState.status.ended then
      effects.error(s"run '$runId' has ended (${currentState.status.wire})", ErrorCode.Conflict)
    else effects.persist(event).thenReply(_ => currentState)

  def cancel(request: Cancel): Effect[RunRecord] =
    if !currentState.exists then effects.error(s"no run '$runId'", ErrorCode.NotFound)
    else if currentState.status.ended || currentState.cancelRequested.isDefined then
      effects.reply(currentState)
    else effects.persist(E.CancelRequested(request.by, now())).thenReply(_ => currentState)

  def get: ReadOnlyEffect[RunRecord] =
    if currentState.exists then effects.reply(currentState)
    else effects.error(s"no run '$runId'", ErrorCode.NotFound)

  private def now(): Long = System.currentTimeMillis()

object RunEntity
    extends EventSourcedEntity.Companion[RunEntity, RunRecord, RunEvent](
      componentId = ComponentId("ankka-run"),
      stateSerializer = Codecs.serializer[RunRecord]("run-record"),
      eventSerializer = Codecs.serializer[RunEvent]("run-event")
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  /** Run ids the platform gives scheduled runs; a caller's id may not start so. */
  val SchedulePrefix = "schedule:"

  final case class Start(
      blueprint: String,
      version: Int,
      input: String,
      startedBy: String,
      deadline: Option[Long]
  )
  final case class Cancel(by: String)

  given Serializer[Start]    = Codecs.serializer[Start]("run-start")
  given Serializer[Cancel]   = Codecs.serializer[Cancel]("run-cancel")
  given Serializer[RunEvent] = eventSerializer

  def create(context: EventSourcedEntityContext) = new RunEntity(context)

  val start  = command("start")(_.start)
  val record = command("record")(_.record)
  val cancel = command("cancel")(_.cancel)
  val get    = query("get")(_.get)
