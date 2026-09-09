package nakka.agent

import nakka.core.{Codecs, Serializer}

/**
 * One entry in a session's history.
 *
 * `agentId` is on every entry because a session is shared: several agents can collaborate in one
 * conversation, and each needs to be able to decide whose contributions it wants to see.
 */
sealed trait SessionMessage:
  def timestamp: Long
  def agentId: String

object SessionMessage:

  final case class UserMessage(timestamp: Long, text: String, agentId: String)
      extends SessionMessage

  /**
   * A model turn.
   *
   * Tool calls are recorded alongside the text so that replaying history shows the model what it
   * already tried. Without them, a model re-reads its own answer with no memory of how it got there
   * and tends to repeat the same tool calls.
   */
  final case class AiMessage(
      timestamp: Long,
      text: String,
      agentId: String,
      toolCalls: Vector[RecordedToolCall] = Vector.empty
  ) extends SessionMessage

  final case class ToolResultMessage(
      timestamp: Long,
      callId: String,
      toolName: String,
      content: String,
      isError: Boolean,
      agentId: String
  ) extends SessionMessage

  /** Replaces a stretch of history with a summary. See memory compaction. */
  final case class SummaryMessage(timestamp: Long, text: String, agentId: String)
      extends SessionMessage

final case class RecordedToolCall(id: String, name: String, arguments: String)

/** Which of a session's messages an agent wants to see. */
final case class MemoryFilter(
    agentIds: Set[String] = Set.empty,
    excludeToolResults: Boolean = false
):
  /** Adds an agent whose messages should be included. Filters combine with OR. */
  def includeFromAgentId(agentId: String): MemoryFilter =
    copy(agentIds = agentIds + agentId)

  /** Drops tool traffic, keeping just the conversation. */
  def withoutToolResults: MemoryFilter = copy(excludeToolResults = true)

  private[agent] def matches(message: SessionMessage): Boolean =
    val agentAllowed = agentIds.isEmpty || agentIds.contains(message.agentId)
    val kindAllowed = message match
      case _: SessionMessage.ToolResultMessage => !excludeToolResults
      case _                                   => true
    agentAllowed && kindAllowed

object MemoryFilter:
  /** Everything in the session. */
  val all: MemoryFilter = MemoryFilter()

  def includeFromAgentId(agentId: String): MemoryFilter = all.includeFromAgentId(agentId)

/**
 * Rewrites messages on their way into memory.
 *
 * The place to redact. Keep implementations stateless: one instance serves every session an agent
 * handles.
 */
trait SessionMemoryInterceptor:
  def beforeWrite(sessionId: String, message: SessionMessage): SessionMessage

/** How an agent reads and writes session history. */
final case class MemoryProvider(
    read: Boolean,
    write: Boolean,
    readLast: Option[Int],
    filter: MemoryFilter,
    interceptor: Option[SessionMemoryInterceptor]
):
  /** Only include the most recent `count` messages in the prompt. */
  def readLast(count: Int): MemoryProvider =
    if count <= 0 then throw IllegalArgumentException("readLast needs a positive count")
    else copy(readLast = Some(count))

  /** Read history but contribute nothing — for a classifier or a side-channel query. */
  def readOnly: MemoryProvider = copy(write = false)

  /** Contribute to history without being influenced by it. */
  def writeOnly: MemoryProvider = copy(read = false)

  def filtered(filter: MemoryFilter): MemoryProvider = copy(filter = filter)

  def withInterceptor(interceptor: SessionMemoryInterceptor): MemoryProvider =
    copy(interceptor = Some(interceptor))

  private[agent] def enabled: Boolean = read || write

object MemoryProvider:

  /**
   * No memory at all.
   *
   * The right choice for a one-shot classification or extraction: sharing a session with unrelated
   * turns makes those tasks worse, not better.
   */
  val none: MemoryProvider =
    MemoryProvider(read = false, write = false, None, MemoryFilter.all, None)

  /** Read and write the whole session, trimmed by the configured size limit. */
  val limitedWindow: MemoryProvider =
    MemoryProvider(read = true, write = true, None, MemoryFilter.all, None)

  private[nakka] val messageSerializer: Serializer[Vector[SessionMessage]] =
    Codecs.serializer[Vector[SessionMessage]]("session-messages")
