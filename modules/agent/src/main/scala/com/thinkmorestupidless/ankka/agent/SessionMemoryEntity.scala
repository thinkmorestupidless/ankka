package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A session's accumulated history.
 *
 * `usage` is what the text model cost; `judgmentUsage` what judgments asked in the session cost —
 * the handler's own and its guardrails'. They are kept apart because the two models are priced a
 * hundred times apart, and one figure summing both would mean nothing.
 */
final case class SessionHistory(
    messages: Vector[SessionMessage],
    usage: TokenUsage,
    sizeInBytes: Int,
    judgmentUsage: TokenUsage = TokenUsage.zero,
    suspended: Option[SuspendedTurn] = None
):
  def isEmpty: Boolean = messages.isEmpty && suspended.isEmpty

  /** The most recent `count` messages, oldest first. */
  def lastMessages(count: Int): Vector[SessionMessage] =
    if count >= messages.size then messages else messages.takeRight(count)

  /** The approval requests of the suspended turn that are awaiting a decision. */
  def awaiting: Vector[ApprovalRequest] =
    suspended.map(_.requests.filter(_.awaiting)).getOrElse(Vector.empty)

object SessionHistory:
  def empty: SessionHistory = SessionHistory(Vector.empty, TokenUsage.zero, 0)

/**
 * A turn that stopped because the model called a tool that requires approval.
 *
 * Kept apart from `messages`, not appended to them: a turn reaches the history only when it ends
 * with an answer, so a turn that fails still leaves no trace, a memory filter never cuts a tool
 * call from its result, and compaction never touches a turn that is waiting.
 *
 * It holds what the loop needs to go on and what its effect cannot give back: the handler and the
 * request that built the effect (the effect itself holds closures and is rebuilt by running the
 * handler again), what the turn produced so far, and what it spent.
 */
final case class SuspendedTurn(
    agentId: String,
    handler: String,
    streaming: Boolean,
    payload: String,
    messages: Vector[SessionMessage],
    usage: TokenUsage,
    steps: Int,
    requests: Vector[ApprovalRequest],
    judgmentUsage: TokenUsage = TokenUsage.zero
):
  def awaiting: Vector[ApprovalRequest] = requests.filter(_.awaiting)

  /** The request payload the handler was called with. */
  def payloadBytes: Array[Byte] = java.util.Base64.getDecoder.decode(payload)

object SuspendedTurn:
  def encodePayload(bytes: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(bytes)

/** What can happen to a session's history. */
enum SessionMemoryEvent:
  case UserMessageAdded(message: SessionMessage.UserMessage)
  case AiMessageAdded(message: SessionMessage.AiMessage, usage: TokenUsage)
  case ToolResultAdded(message: SessionMessage.ToolResultMessage)

  /** Replaces everything before `keepFrom` with a summary. */
  case HistoryCompacted(summary: SessionMessage.SummaryMessage, droppedCount: Int)
  case Cleared

  /**
   * Tokens spent on judgments. Recorded with a turn's messages when there are some, and alone when
   * there are none — a refused request, a judgment handler — which is why it is not on a message.
   */
  case JudgmentUsageAdded(usage: TokenUsage)

  /** A turn stopped to wait for approval; replaces a turn that was waiting before it. */
  case TurnSuspended(turn: SuspendedTurn)

  /** A decision on one of the suspended turn's approval requests. */
  case ApprovalDecided(approvalId: String, decision: Decision)

  /** The suspended turn is over: answered, failed, or cut off. */
  case TurnEnded

/**
 * A conversation, as an event sourced entity.
 *
 * Memory is a first-class component rather than a field on the agent, which is what makes the
 * documented capabilities fall out for free: several agents can share one session by addressing the
 * same id, a consumer can subscribe to memory events to drive compaction, and history survives a
 * restart because it is a journal like any other.
 */
final class SessionMemoryEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[SessionHistory, SessionMemoryEvent]:

  private val theSessionId: String = context.entityId

  def emptyState: SessionHistory = SessionHistory.empty

  def applyEvent(event: SessionMemoryEvent): SessionHistory = event match
    case SessionMemoryEvent.UserMessageAdded(message) =>
      appended(message)

    case SessionMemoryEvent.AiMessageAdded(message, usage) =>
      val next = appended(message)
      next.copy(usage = next.usage + usage)

    case SessionMemoryEvent.ToolResultAdded(message) =>
      appended(message)

    case SessionMemoryEvent.HistoryCompacted(summary, droppedCount) =>
      val kept     = currentState.messages.drop(droppedCount)
      val messages = summary +: kept
      currentState.copy(messages = messages, sizeInBytes = sizeOf(messages))

    case SessionMemoryEvent.Cleared =>
      SessionHistory.empty

    case SessionMemoryEvent.JudgmentUsageAdded(usage) =>
      currentState.copy(judgmentUsage = currentState.judgmentUsage + usage)

    case SessionMemoryEvent.TurnSuspended(turn) =>
      currentState.copy(suspended = Some(turn))

    case SessionMemoryEvent.ApprovalDecided(approvalId, decision) =>
      currentState.copy(suspended = currentState.suspended.map { turn =>
        turn.copy(requests = turn.requests.map { request =>
          if request.id == approvalId then request.copy(decision = Some(decision)) else request
        })
      })

    case SessionMemoryEvent.TurnEnded =>
      currentState.copy(suspended = None)

  def addUserMessage(message: SessionMessage.UserMessage): Effect[Done] =
    effects.persist(SessionMemoryEvent.UserMessageAdded(message)).thenReply(_ => Done)

  def addAiMessage(request: SessionMemoryEntity.AddAiMessage): Effect[Done] =
    effects
      .persist(SessionMemoryEvent.AiMessageAdded(request.message, request.usage))
      .thenReply(_ => Done)

  def addToolResult(message: SessionMessage.ToolResultMessage): Effect[Done] =
    effects.persist(SessionMemoryEvent.ToolResultAdded(message)).thenReply(_ => Done)

  /**
   * Writes several messages in one go.
   *
   * One turn of an agent loop produces a user message, an AI message and any number of tool
   * results. Persisting them together means a reader never sees a turn half-written.
   */
  def append(batch: SessionMemoryEntity.Append): Effect[Done] =
    val events = batch.messages.map {
      case message: SessionMessage.UserMessage => SessionMemoryEvent.UserMessageAdded(message)
      case message: SessionMessage.AiMessage =>
        SessionMemoryEvent.AiMessageAdded(message, batch.usage)
      case message: SessionMessage.ToolResultMessage =>
        SessionMemoryEvent.ToolResultAdded(message)
      case message: SessionMessage.SummaryMessage =>
        SessionMemoryEvent.HistoryCompacted(message, 0)
    }
    val judged =
      Option.when(batch.judgmentUsage != TokenUsage.zero)(
        SessionMemoryEvent.JudgmentUsageAdded(batch.judgmentUsage)
      )
    // A turn that waited ends in the same write as its messages, so a reader never sees the
    // answer with the turn still waiting, or the turn gone with no answer.
    val ended =
      Option.when(batch.endsTurn && currentState.suspended.isDefined)(SessionMemoryEvent.TurnEnded)
    val all = events ++ judged ++ ended
    if all.isEmpty then effects.reply(Done)
    else effects.persistAll(all).thenReply(_ => Done)

  /**
   * Records a turn that stopped to wait for approval.
   *
   * Refused while another turn has a request awaiting a decision: a session waits for one turn at a
   * time, which is what keeps a decision and a new turn from interleaving. A turn whose requests
   * are all decided is replaced — that is the same turn waiting a second time.
   */
  def suspendTurn(turn: SuspendedTurn): Effect[Done] =
    if currentState.awaiting.nonEmpty then
      effects.error(
        s"session '$theSessionId' already has an approval request awaiting a decision",
        ErrorCode.Conflict
      )
    else effects.persist(SessionMemoryEvent.TurnSuspended(turn)).thenReply(_ => Done)

  /**
   * Records a decision, answering with the suspended turn as it then stands.
   *
   * An id decided before is a conflict whether its turn is still suspended or is over: the decision
   * is on the tool result it produced. An id with no record anywhere is not found — never asked,
   * discarded, or of a turn that was cut off before its results were written.
   */
  def decideApproval(decision: Decision): Effect[SuspendedTurn] =
    val id = decision.approvalId
    Decision.problem(decision) match
      case Some(problem) => effects.error(problem, ErrorCode.BadRequest)
      case None =>
        currentState.suspended.flatMap(turn => turn.requests.find(_.id == id).map(turn -> _)) match
          case Some((_, request)) if !request.awaiting =>
            effects.error(s"approval request '$id' is decided", ErrorCode.Conflict)
          case Some((turn, _)) =>
            effects
              .persist(SessionMemoryEvent.ApprovalDecided(id, decision))
              .thenReply(after => after.suspended.getOrElse(turn))
          case None if decidedBefore(id) =>
            effects.error(s"approval request '$id' is decided", ErrorCode.Conflict)
          case None if currentState.isEmpty =>
            effects.error(
              s"session '$theSessionId' holds no approval request",
              ErrorCode.NotFound
            )
          case None =>
            effects.error(
              s"session '$theSessionId' holds no approval request '$id'",
              ErrorCode.NotFound
            )

  /** Ends the suspended turn, if there is one, adding nothing to the history. */
  def endTurn: Effect[Done] =
    if currentState.suspended.isEmpty then effects.reply(Done)
    else effects.persist(SessionMemoryEvent.TurnEnded).thenReply(_ => Done)

  private def decidedBefore(approvalId: String): Boolean =
    val recorded = currentState.messages ++ currentState.suspended.toVector.flatMap(_.messages)
    recorded.exists {
      case m: SessionMessage.ToolResultMessage => m.decision.exists(_.approvalId == approvalId)
      case _                                   => false
    }

  /** Replaces the oldest `droppedCount` messages with `summary`. */
  def compact(request: SessionMemoryEntity.Compact): Effect[Done] =
    if request.droppedCount <= 0 then effects.error("nothing to compact")
    else if request.droppedCount > currentState.messages.size then
      effects.error(
        s"cannot drop ${request.droppedCount} messages; session '$theSessionId' has " +
          s"${currentState.messages.size}"
      )
    else
      effects
        .persist(
          SessionMemoryEvent.HistoryCompacted(
            SessionMessage.SummaryMessage(
              System.currentTimeMillis(),
              request.summary,
              request.agentId
            ),
            request.droppedCount
          )
        )
        .thenReply(_ => Done)

  def clear: Effect[Done] =
    effects.persist(SessionMemoryEvent.Cleared).thenReply(_ => Done)

  def history: ReadOnlyEffect[SessionHistory] = effects.reply(currentState)

  private def appended(message: SessionMessage): SessionHistory =
    val messages = currentState.messages :+ message
    currentState.copy(messages = messages, sizeInBytes = sizeOf(messages))

  private def sizeOf(messages: Vector[SessionMessage]): Int =
    messages.foldLeft(0) { (total, message) =>
      total + (message match
        case m: SessionMessage.UserMessage       => m.text.length
        case m: SessionMessage.AiMessage         => m.text.length
        case m: SessionMessage.ToolResultMessage => m.content.length
        case m: SessionMessage.SummaryMessage    => m.text.length)
    }

object SessionMemoryEntity
    extends EventSourcedEntity.Companion[
      SessionMemoryEntity,
      SessionHistory,
      SessionMemoryEvent
    ](
      componentId = ComponentId("ankka-session-memory"),
      stateSerializer = Codecs.serializer[SessionHistory]("session-history"),
      eventSerializer = Codecs.serializer[SessionMemoryEvent]("session-memory-event")
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  final case class AddAiMessage(message: SessionMessage.AiMessage, usage: TokenUsage)

  /** A turn's messages, what the text model cost, and what the turn's judgments cost. */
  final case class Append(
      messages: Vector[SessionMessage],
      usage: TokenUsage,
      judgmentUsage: TokenUsage = TokenUsage.zero,
      endsTurn: Boolean = false
  )
  final case class Compact(summary: String, droppedCount: Int, agentId: String)

  given Serializer[SessionMessage.UserMessage] =
    Codecs.serializer[SessionMessage.UserMessage]("session-user-message")
  given Serializer[SessionMessage.ToolResultMessage] =
    Codecs.serializer[SessionMessage.ToolResultMessage]("session-tool-result")
  given Serializer[AddAiMessage]   = Codecs.serializer[AddAiMessage]("session-add-ai")
  given Serializer[Append]         = Codecs.serializer[Append]("session-append")
  given Serializer[Compact]        = Codecs.serializer[Compact]("session-compact")
  given Serializer[SessionHistory] = Codecs.serializer[SessionHistory]("session-history")
  given Serializer[SuspendedTurn]  = Codecs.serializer[SuspendedTurn]("session-suspended-turn")
  given Serializer[Decision]       = Codecs.serializer[Decision]("session-decision")

  def create(context: EventSourcedEntityContext) = new SessionMemoryEntity(context)

  val addUserMessage = command("add-user-message")(_.addUserMessage)
  val addAiMessage   = command("add-ai-message")(_.addAiMessage)
  val addToolResult  = command("add-tool-result")(_.addToolResult)
  val append         = command("append")(_.append)
  val compact        = command("compact")(_.compact)
  val clear          = command("clear")(_.clear)
  val history        = query("history")(_.history)
  val suspendTurn    = command("suspend-turn")(_.suspendTurn)
  val decideApproval = command("decide-approval")(_.decideApproval)
  val endTurn        = command("end-turn")(_.endTurn)
