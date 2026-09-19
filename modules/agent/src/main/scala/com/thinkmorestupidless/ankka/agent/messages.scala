package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

/** A piece of content in a message. */
sealed trait MessageContent

object MessageContent:
  final case class Text(text: String) extends MessageContent

  /**
   * Inline binary content — an image or a PDF a tool returned.
   *
   * Not written to session memory: a few hundred kilobytes of base64 per turn would exhaust the
   * context window within a handful of exchanges. Memory records a placeholder, so persist the
   * bytes somewhere and return a URI if a later turn needs to see them again.
   */
  final case class Inline(bytes: Array[Byte], mediaType: String) extends MessageContent:
    override def toString: String = s"Inline(${bytes.length} bytes, $mediaType)"

/** A tool invocation the model asked for. */
final case class ToolCall(id: String, name: String, arguments: Json)

/** The outcome of running one tool call. */
final case class ToolResult(
    callId: String,
    name: String,
    content: String,
    isError: Boolean = false
)

/** One turn in a conversation, in the shape every provider needs. */
sealed trait ChatMessage

object ChatMessage:
  final case class User(content: Vector[MessageContent]) extends ChatMessage
  object User:
    def text(value: String): User = User(Vector(MessageContent.Text(value)))

  /**
   * A model turn.
   *
   * Carries both prose and tool calls because a single response can contain both, and dropping
   * either half when replaying the conversation confuses the model about what it already did.
   */
  final case class Assistant(text: String, toolCalls: Vector[ToolCall] = Vector.empty)
      extends ChatMessage

  /**
   * Results for the calls in the preceding assistant turn.
   *
   * All of them, in one message. A model that asked for three tools in parallel and gets its
   * results split across three messages learns to stop asking in parallel.
   */
  final case class ToolResults(results: Vector[ToolResult]) extends ChatMessage

/** How hard the model should work. Maps onto a provider's own effort control. */
enum Effort:
  case Low, Medium, High, XHigh, Max

/** Per-request model configuration. */
final case class ModelSettings(
    modelName: String,
    /**
     * Sampling temperature.
     *
     * Ignored by the Anthropic provider: current Claude models reject sampling parameters outright.
     * Use `effort` to trade cost against quality there.
     */
    temperature: Option[Double] = None,
    maxTokens: Option[Int] = None,
    effort: Option[Effort] = None,
    /**
     * Whether the model may reason before answering.
     *
     * On by default: the current generation of models is tuned for it, and turning it off is a
     * deliberate cost/latency trade rather than a neutral default.
     */
    thinking: Boolean = true
)

/** Everything a provider needs for one call. */
final case class ModelRequest(
    settings: ModelSettings,
    systemMessage: Option[String],
    messages: Vector[ChatMessage],
    tools: Vector[ToolSpec] = Vector.empty
)

/** Why the model stopped. */
enum StopReason:
  case EndTurn
  case ToolUse
  case MaxTokens

  /**
   * The model declined.
   *
   * Modelled explicitly because it arrives as a *successful* response — a caller that only checks
   * for exceptions will happily treat a refusal as an answer.
   */
  case Refusal
  case Other

/** What a call cost. */
final case class TokenUsage(
    inputTokens: Int = 0,
    outputTokens: Int = 0,
    cacheReadTokens: Int = 0,
    cacheWriteTokens: Int = 0
):
  def total: Int = inputTokens + outputTokens
  def +(other: TokenUsage): TokenUsage =
    TokenUsage(
      inputTokens + other.inputTokens,
      outputTokens + other.outputTokens,
      cacheReadTokens + other.cacheReadTokens,
      cacheWriteTokens + other.cacheWriteTokens
    )

object TokenUsage:
  val zero: TokenUsage = TokenUsage()
  private[ankka] val serializer: Serializer[TokenUsage] =
    Codecs.serializer[TokenUsage]("token-usage")

/** One model response. */
final case class ModelResponse(
    text: String,
    toolCalls: Vector[ToolCall] = Vector.empty,
    stopReason: StopReason = StopReason.EndTurn,
    usage: TokenUsage = TokenUsage.zero,
    /** Present when `stopReason` is `Refusal`. */
    refusalReason: Option[String] = None
):
  def wantsTools: Boolean = toolCalls.nonEmpty
  def isRefusal: Boolean  = stopReason == StopReason.Refusal

/** A fragment of a streaming response. */
sealed trait ModelChunk

object ModelChunk:
  final case class TextDelta(text: String)            extends ModelChunk
  final case class ToolCallStarted(call: ToolCall)    extends ModelChunk
  final case class Completed(response: ModelResponse) extends ModelChunk
  final case class Failed(error: String)              extends ModelChunk

/** A tool as described to the model. */
final case class ToolSpec(
    name: String,
    description: String,
    /** JSON Schema for the tool's arguments. */
    inputSchema: Json
)
