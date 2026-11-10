package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromStringReentrant}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A session's accumulated history.
 *
 * `usage` is what the text model cost; `judgmentUsage` what judgments asked in the session cost —
 * the handler's own and its guardrails'. They are kept apart because the two models are priced a
 * hundred times apart, and one figure summing both would mean nothing.
 *
 * `usageByProvider` is `usage` again, kept per model provider by the name its messages carry, so a
 * session continued across providers says what each cost. A message recorded before providers were
 * named counts in `usage` alone.
 *
 * `subject` is the data subject the session was started with (feature 042), fixed at its first
 * call: everything the session keeps of its conversation is then sealed under that subject's key,
 * and `erased` says the key was destroyed, so the conversation reads as empty. Both are absent from
 * a session started with none, whose bytes are what they always were.
 */
final case class SessionHistory(
    messages: Vector[SessionMessage],
    usage: TokenUsage,
    sizeInBytes: Int,
    judgmentUsage: TokenUsage = TokenUsage.zero,
    suspended: Option[SuspendedTurn] = None,
    usageByProvider: Map[String, TokenUsage] = Map.empty,
    subject: Option[String] = None,
    erased: Boolean = false
):
  def isEmpty: Boolean = messages.isEmpty && suspended.isEmpty

  /**
   * The session as an observer is shown it: a session with a data subject has every personal text
   * replaced by `Personal(<subject>)`, which names the data subject and never the value.
   */
  def observed: SessionHistory = subject.fold(this) { s =>
    val shown                                      = s"Personal($s)"
    def message(m: SessionMessage): SessionMessage = m match
      case u: SessionMessage.UserMessage => u.copy(text = shown)
      case a: SessionMessage.AiMessage   =>
        a.copy(text = shown, toolCalls = a.toolCalls.map(_.copy(arguments = shown)))
      case r: SessionMessage.ToolResultMessage => r.copy(content = shown)
      case m: SessionMessage.SummaryMessage    => m.copy(text = shown)
    copy(
      messages = messages.map(message),
      suspended = suspended.map(t =>
        t.copy(
          payload = shown,
          messages = t.messages.map(message),
          requests = t.requests.map(_.copy(arguments = Json.str(shown)))
        )
      )
    )
  }

  /** The most recent `count` messages, oldest first. */
  def lastMessages(count: Int): Vector[SessionMessage] =
    if count >= messages.size then messages else messages.takeRight(count)

  /** The approval requests of the suspended turn that are awaiting a decision. */
  def awaiting: Vector[ApprovalRequest] =
    suspended.map(_.requests.filter(_.awaiting)).getOrElse(Vector.empty)

object SessionHistory:
  def empty: SessionHistory = SessionHistory(Vector.empty, TokenUsage.zero, 0)

  /** A session whose data subject was erased: what it held of its conversation is gone. */
  private[agent] def erasedFrom(state: SessionHistory): SessionHistory =
    state.copy(messages = Vector.empty, sizeInBytes = 0, suspended = None, erased = true)

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
   * The session was started with a data subject (feature 042): written first, in the clear, and
   * never again. Every event after it is `Sealed`.
   */
  case Started(subject: String)

  /**
   * An event of a session with a data subject: the event's own JSON, sealed under the subject's
   * key. Read once the key is destroyed it is erased, and the conversation reads as empty. Anything
   * that reads these events, the entity and a consumer alike, reads a sealed one through
   * `SessionMemoryEvent.opened`.
   */
  case Sealed(body: Personal[String])

object SessionMemoryEvent:

  /**
   * The event a session memory event stands for: a plain event as it is, a sealed one as the event
   * its body holds, and `None` for a sealed one whose data subject was erased, which holds nothing.
   *
   * The one reading of a sealed event, so the entity's replay and a consumer of its events (the
   * compactor) cannot disagree about what one holds. It asks for no key: whoever decoded the event
   * opened its body then. A body never holds another `Sealed`, since only plain events are sealed.
   */
  private[agent] def opened(event: SessionMemoryEvent): Option[SessionMemoryEvent] = event match
    case Sealed(Personal.Present(_, json)) =>
      Some(readFromStringReentrant(json)(using SessionMemoryCodecs.event))
    case Sealed(Personal.Erased(_)) => None
    case plain                      => Some(plain)

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
      val next       = appended(message)
      val byProvider = message.provider match
        case Some(provider) =>
          next.usageByProvider.updated(
            provider,
            next.usageByProvider.getOrElse(provider, TokenUsage.zero) + usage
          )
        case None => next.usageByProvider
      next.copy(usage = next.usage + usage, usageByProvider = byProvider)

    case SessionMemoryEvent.ToolResultAdded(message) =>
      appended(message)

    case SessionMemoryEvent.HistoryCompacted(summary, droppedCount) =>
      val kept     = currentState.messages.drop(droppedCount)
      val messages = summary +: kept
      currentState.copy(messages = messages, sizeInBytes = sizeOf(messages))

    case SessionMemoryEvent.Cleared =>
      SessionHistory.empty.copy(subject = currentState.subject, erased = currentState.erased)

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

    case SessionMemoryEvent.Started(subject) =>
      held(subject)
      currentState.copy(subject = Some(subject))

    case sealedEvent: SessionMemoryEvent.Sealed =>
      SessionMemoryEvent.opened(sealedEvent) match
        case Some(event) => applyEvent(event)
        case None        => SessionHistory.erasedFrom(currentState)

  /**
   * Fixes the session's data subject at its first call (feature 042). A call naming the subject it
   * has is let through; one naming another, or naming one for a session that already holds a
   * conversation kept with none, is refused, since what it already holds is not that subject's.
   */
  def begin(request: SessionMemoryEntity.Begin): Effect[Done] =
    Sealing.subjectOf(request.subject) match
      case Left(problem) => effects.error(problem.message, problem.code)
      case Right(_)      =>
        currentState.subject match
          case Some(s) if s == request.subject =>
            held(s)
            effects.reply(Done)
          case Some(_) =>
            effects.error(
              s"session '$theSessionId' was started with another data subject",
              ErrorCode.Conflict
            )
          case None if currentState.isEmpty && currentState.usage == TokenUsage.zero =>
            effects.persist(SessionMemoryEvent.Started(request.subject)).thenReply(_ => Done)
          case None =>
            effects.error(
              s"session '$theSessionId' was started with no data subject, and holds a " +
                "conversation that cannot be kept under one",
              ErrorCode.Conflict
            )

  /** Notes that this session, in memory, holds `subject`'s conversation (`LiveSubjects`). */
  private def held(subject: String): Unit =
    Option(context.componentClient).foreach(
      LiveSubjects.hold(
        _,
        LiveSubjects.Holder.Entity(SessionMemoryEntity.componentId, EntityId(theSessionId)),
        subject
      )
    )

  /**
   * Persists `events` as the session keeps them: as they are with no data subject, sealed under the
   * subject's key with one. A session whose data subject was erased keeps nothing more.
   */
  private def record(events: Seq[SessionMemoryEvent]): Effect[Done] =
    if events.isEmpty || currentState.erased then effects.reply(Done)
    else effects.persistAll(events.map(sealedOr)).thenReply(_ => Done)

  private def sealedOr(event: SessionMemoryEvent): SessionMemoryEvent =
    currentState.subject.flatMap(DataSubject.from(_).toOption) match
      case None          => event
      case Some(subject) =>
        SessionMemoryEvent.Sealed(
          Sealing.seal(subject, event, erased = false)(using SessionMemoryCodecs.event)
        )

  def addUserMessage(message: SessionMessage.UserMessage): Effect[Done] =
    record(Vector(SessionMemoryEvent.UserMessageAdded(message)))

  def addAiMessage(request: SessionMemoryEntity.AddAiMessage): Effect[Done] =
    record(Vector(SessionMemoryEvent.AiMessageAdded(request.message, request.usage)))

  def addToolResult(message: SessionMessage.ToolResultMessage): Effect[Done] =
    record(Vector(SessionMemoryEvent.ToolResultAdded(message)))

  /**
   * Writes several messages in one go.
   *
   * One turn of an agent loop produces a user message, an AI message and any number of tool
   * results. Persisting them together means a reader never sees a turn half-written.
   */
  def append(batch: SessionMemoryEntity.Append): Effect[Done] =
    // A batch's usage is the whole turn's, and a turn with a tool round holds several answers: it is
    // recorded once, with the last of them, or the session would count the turn once per answer.
    val lastAnswer = batch.messages.lastIndexWhere(_.isInstanceOf[SessionMessage.AiMessage])
    val events     = batch.messages.zipWithIndex.map {
      case (message: SessionMessage.UserMessage, _) => SessionMemoryEvent.UserMessageAdded(message)
      case (message: SessionMessage.AiMessage, index) =>
        SessionMemoryEvent.AiMessageAdded(
          message,
          if index == lastAnswer then batch.usage else TokenUsage.zero
        )
      case (message: SessionMessage.ToolResultMessage, _) =>
        SessionMemoryEvent.ToolResultAdded(message)
      case (message: SessionMessage.SummaryMessage, _) =>
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
    record(events ++ judged ++ ended)

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
    else if currentState.erased then
      effects.error(
        s"session '$theSessionId' is of an erased data subject and keeps no turn",
        ErrorCode.Conflict
      )
    else effects.persist(sealedOr(SessionMemoryEvent.TurnSuspended(turn))).thenReply(_ => Done)

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
      case None          =>
        currentState.suspended.flatMap(turn => turn.requests.find(_.id == id).map(turn -> _)) match
          case Some((_, request)) if !request.awaiting =>
            effects.error(s"approval request '$id' is decided", ErrorCode.Conflict)
          case Some((turn, _)) =>
            effects
              .persist(sealedOr(SessionMemoryEvent.ApprovalDecided(id, decision)))
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
    else record(Vector(SessionMemoryEvent.TurnEnded))

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
      record(
        Vector(
          SessionMemoryEvent.HistoryCompacted(
            SessionMessage.SummaryMessage(
              System.currentTimeMillis(),
              request.summary,
              request.agentId
            ),
            request.droppedCount
          )
        )
      )

  def clear: Effect[Done] = record(Vector(SessionMemoryEvent.Cleared))

  def history: ReadOnlyEffect[SessionHistory] = effects.reply(currentState)

  /** The session as an observer is shown it: personal texts as `Personal(<subject>)`. */
  def observed: ReadOnlyEffect[SessionHistory] = effects.reply(currentState.observed)

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

/**
 * The session's codecs, apart from its companion: the companion's constructor names them, and runs
 * before the companion's own fields are set.
 */
private[agent] object SessionMemoryCodecs:
  given event: JsonValueCodec[SessionMemoryEvent] = Codecs.make[SessionMemoryEvent]
  given history: JsonValueCodec[SessionHistory]   = Codecs.make[SessionHistory]

  /**
   * The state as a snapshot keeps it: sealed whole under the session's data subject when it has
   * one, with only the token counts in the clear.
   */
  val events: Serializer[SessionMemoryEvent] =
    Sealing.eventSerializer[SessionMemoryEvent](
      "session-memory-event",
      {
        case SessionMemoryEvent.Sealed(Personal.Present(subject, _)) =>
          Some(SessionMemoryEvent.Sealed(Personal.Erased(subject)))
        case _ => None
      }
    )

  val state: Serializer[SessionHistory] = Sealing.stateSerializer[SessionHistory](
    "session-history",
    _.subject,
    _.erased,
    h =>
      SessionHistory.empty.copy(
        usage = h.usage,
        judgmentUsage = h.judgmentUsage,
        usageByProvider = h.usageByProvider,
        subject = h.subject
      ),
    SessionHistory.erasedFrom
  )

object SessionMemoryEntity
    extends EventSourcedEntity.Companion[
      SessionMemoryEntity,
      SessionHistory,
      SessionMemoryEvent
    ](
      componentId = ComponentId("ankka-session-memory"),
      stateSerializer = SessionMemoryCodecs.state,
      eventSerializer = SessionMemoryCodecs.events
    ):

  /** The data subject a session is started with: its first call names it. */
  final case class Begin(subject: String)

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
  given Serializer[Begin]          = Codecs.serializer[Begin]("session-begin")

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
  val begin          = command("begin")(_.begin)
  val observed       = query("observed")(_.observed)
