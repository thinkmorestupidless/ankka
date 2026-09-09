package nakka.agent

import nakka.core.{ComponentId, EntityId}
import nakka.sdk.*

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** When and how aggressively to compact a session. */
final case class CompactionSettings(
    /**
     * Compact once a session's text exceeds this.
     *
     * A character count, not a token count: nakka does not tokenise, and the ratio is
     * model-specific. Treat it as a coarse ceiling well below the context window rather than a
     * precise budget.
     */
    maxHistoryBytes: Int = 100_000,

    /**
     * Recent messages left verbatim.
     *
     * The most recent exchanges are what the model needs in full; older ones are what a summary can
     * stand in for.
     */
    keepRecentMessages: Int = 10,

    /** Do not bother summarising fewer than this many messages. */
    minMessagesToCompact: Int = 4
):
  require(maxHistoryBytes > 0, "maxHistoryBytes must be positive")
  require(keepRecentMessages >= 0, "keepRecentMessages cannot be negative")
  require(minMessagesToCompact > 0, "minMessagesToCompact must be positive")

/** Turns a stretch of conversation into a short summary. */
trait Summariser:
  def summarise(messages: Vector[SessionMessage]): String

/**
 * Summarises by asking a model.
 *
 * Calls the `ModelProvider` directly rather than going through an `Agent`. An agent would need a
 * session, and the only sensible session is the one being summarised — so it would append its own
 * turns to the history it is trying to shrink. Bypassing the agent layer removes the problem rather
 * than configuring around it.
 */
final class ModelSummariser(
    provider: ModelProvider,
    timeout: FiniteDuration = 60.seconds
) extends Summariser:

  def summarise(messages: Vector[SessionMessage]): String =
    val response = Await.result(
      provider.complete(
        ModelRequest(
          settings = ModelSettings(provider.modelName),
          systemMessage = Some(ModelSummariser.SystemMessage),
          messages = Vector(ChatMessage.User.text(ModelSummariser.transcript(messages)))
        )
      ),
      timeout
    )
    if response.isRefusal then
      throw ModelCallFailed(
        provider.name,
        response.refusalReason.getOrElse("the model declined to summarise")
      )
    else response.text

object ModelSummariser:

  val SystemMessage: String =
    """You compress conversation history so it can be carried forward in a limited
      |context window. Preserve decisions, facts established, names, numbers and open
      |questions. Drop pleasantries and repetition. Write compact prose, no preamble,
      |no more than a short paragraph.""".stripMargin

  /** Renders messages for the summariser to read. */
  private[agent] def transcript(messages: Vector[SessionMessage]): String =
    messages
      .map {
        case m: SessionMessage.UserMessage    => s"User: ${m.text}"
        case m: SessionMessage.AiMessage      => s"Assistant: ${m.text}"
        case m: SessionMessage.SummaryMessage => s"Earlier summary: ${m.text}"
        case m: SessionMessage.ToolResultMessage =>
          val outcome = if m.isError then "failed" else "returned"
          s"Tool ${m.toolName} $outcome: ${m.content}"
      }
      .mkString("\n")

/**
 * Keeps sessions from outgrowing the context window.
 *
 * A consumer over session memory's own events, so compaction runs off the request path: the turn
 * that pushed a session over the limit is not the one that waits for a summarisation call.
 */
final class SessionCompactor(
    context: ConsumerContext,
    settings: CompactionSettings,
    summariser: Summariser
) extends Consumer[SessionMemoryEvent, Nothing]:

  private val client = context.componentClient

  def onMessage(event: SessionMemoryEvent): Effect =
    event match
      // Every completed turn ends with an AI message, so this fires once per turn.
      // `HistoryCompacted` deliberately does not: reacting to our own write would loop.
      case _: SessionMemoryEvent.AiMessageAdded => considerCompacting()
      case _                                    => effects.ignore()

  private def considerCompacting(): Effect =
    val sessionId = messageContext.subject
    val memory    = client.forEventSourcedEntity(EntityId(sessionId))

    try
      val history = memory.call(SessionMemoryEntity.history).invoke()

      if history.sizeInBytes <= settings.maxHistoryBytes then effects.ignore()
      else
        val dropCount = history.messages.size - settings.keepRecentMessages
        if dropCount < settings.minMessagesToCompact then
          // Over the limit but with too little history to be worth summarising — a
          // threshold set below what `keepRecentMessages` alone occupies. Compacting
          // would achieve nothing and would repeat on every turn.
          effects.ignore()
        else
          val stale   = history.messages.take(dropCount)
          val summary = summariser.summarise(stale)

          if summary.length >= SessionCompactor.sizeOf(stale) then
            // A summary no shorter than what it replaces is not progress.
            effects.ignore()
          else
            memory
              .call(SessionMemoryEntity.compact)
              .invoke(
                SessionMemoryEntity.Compact(summary, dropCount, SessionCompactor.AgentId)
              ): Unit
            SessionCompactor.compacted.add(sessionId): Unit
            effects.done()
    catch
      case NonFatal(failure) =>
        // Best-effort. A consumer that keeps throwing never advances its offset, which
        // would stall compaction for *every* session because one session's
        // summarisation is failing. Losing a compaction is far cheaper than that.
        SessionCompactor.failures.add(s"$sessionId: ${failure.getMessage}"): Unit
        effects.done()

object SessionCompactor:

  val ComponentId: nakka.core.ComponentId = nakka.core.ComponentId("nakka-session-compactor")

  /** Attributed to the compactor, so a memory filter can include or exclude summaries. */
  val AgentId: String = "nakka-compactor"

  /** Sessions compacted, and failures, for tests and diagnostics. */
  val compacted: java.util.concurrent.ConcurrentLinkedQueue[String] =
    java.util.concurrent.ConcurrentLinkedQueue[String]()

  val failures: java.util.concurrent.ConcurrentLinkedQueue[String] =
    java.util.concurrent.ConcurrentLinkedQueue[String]()

  private[agent] def sizeOf(messages: Vector[SessionMessage]): Int =
    messages.foldLeft(0) { (total, message) =>
      total + (message match
        case m: SessionMessage.UserMessage       => m.text.length
        case m: SessionMessage.AiMessage         => m.text.length
        case m: SessionMessage.ToolResultMessage => m.content.length
        case m: SessionMessage.SummaryMessage    => m.text.length)
    }

  /**
   * The registered form.
   *
   * Built directly rather than through `Consumer.Companion` because the settings and summariser are
   * chosen at wiring time, not declared statically — a companion object has nowhere to receive
   * them.
   *
   * `parallelism = 1`: compaction is infrequent maintenance, and one worker keeps a session's
   * checks strictly ordered.
   */
  def descriptor(
      settings: CompactionSettings,
      summariser: Summariser
  ): ConsumerDescriptor[SessionCompactor, SessionMemoryEvent, Nothing] =
    ConsumerDescriptor(
      componentId = ComponentId,
      source = ChangeSource.eventsOf(SessionMemoryEntity),
      outputSerializer = None,
      produceTo = None,
      create = context => new SessionCompactor(context, settings, summariser),
      parallelism = 1
    )
