package nakka.agent

import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

/** A session's accumulated history. */
final case class SessionHistory(
    messages: Vector[SessionMessage],
    usage: TokenUsage,
    sizeInBytes: Int
):
  def isEmpty: Boolean = messages.isEmpty

  /** The most recent `count` messages, oldest first. */
  def lastMessages(count: Int): Vector[SessionMessage] =
    if count >= messages.size then messages else messages.takeRight(count)

object SessionHistory:
  def empty: SessionHistory = SessionHistory(Vector.empty, TokenUsage.zero, 0)

/** What can happen to a session's history. */
enum SessionMemoryEvent:
  case UserMessageAdded(message: SessionMessage.UserMessage)
  case AiMessageAdded(message: SessionMessage.AiMessage, usage: TokenUsage)
  case ToolResultAdded(message: SessionMessage.ToolResultMessage)

  /** Replaces everything before `keepFrom` with a summary. */
  case HistoryCompacted(summary: SessionMessage.SummaryMessage, droppedCount: Int)
  case Cleared

/**
 * A conversation, as an event sourced entity.
 *
 * Memory is a first-class component rather than a field on the agent, which is what
 * makes the documented capabilities fall out for free: several agents can share one
 * session by addressing the same id, a consumer can subscribe to memory events to drive
 * compaction, and history survives a restart because it is a journal like any other.
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
      val kept = currentState.messages.drop(droppedCount)
      val messages = summary +: kept
      currentState.copy(messages = messages, sizeInBytes = sizeOf(messages))

    case SessionMemoryEvent.Cleared =>
      SessionHistory.empty

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
   * One turn of an agent loop produces a user message, an AI message and any number of
   * tool results. Persisting them together means a reader never sees a turn half-written.
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
    if events.isEmpty then effects.reply(Done)
    else effects.persistAll(events).thenReply(_ => Done)

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
      componentId = ComponentId("nakka-session-memory"),
      stateSerializer = Codecs.serializer[SessionHistory]("session-history"),
      eventSerializer = Codecs.serializer[SessionMemoryEvent]("session-memory-event")
    ):

  final case class AddAiMessage(message: SessionMessage.AiMessage, usage: TokenUsage)
  final case class Append(messages: Vector[SessionMessage], usage: TokenUsage)
  final case class Compact(summary: String, droppedCount: Int, agentId: String)

  given Serializer[SessionMessage.UserMessage] =
    Codecs.serializer[SessionMessage.UserMessage]("session-user-message")
  given Serializer[SessionMessage.ToolResultMessage] =
    Codecs.serializer[SessionMessage.ToolResultMessage]("session-tool-result")
  given Serializer[AddAiMessage] = Codecs.serializer[AddAiMessage]("session-add-ai")
  given Serializer[Append]       = Codecs.serializer[Append]("session-append")
  given Serializer[Compact]      = Codecs.serializer[Compact]("session-compact")
  given Serializer[SessionHistory] = Codecs.serializer[SessionHistory]("session-history")

  def create(context: EventSourcedEntityContext) = new SessionMemoryEntity(context)

  val addUserMessage = command("add-user-message")(_.addUserMessage)
  val addAiMessage   = command("add-ai-message")(_.addAiMessage)
  val addToolResult  = command("add-tool-result")(_.addToolResult)
  val append         = command("append")(_.append)
  val compact        = command("compact")(_.compact)
  val clear          = command("clear")(_.clear)
  val history        = query("history")(_.history)
