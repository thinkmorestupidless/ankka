package nakka.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.{
  ContentBlockParam,
  MessageCreateParams,
  MessageParam,
  OutputConfig,
  StopReason as SdkStopReason,
  ThinkingConfigAdaptive,
  ThinkingConfigParam,
  Tool,
  ToolResultBlockParam,
  ToolUseBlockParam,
  Message as SdkMessage
}

import com.anthropic.models.messages.RawMessageStreamEvent
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Talks to Claude through Anthropic's own Java SDK.
 *
 * The SDK is used only as transport: nakka owns the agent loop, tool dispatch, memory
 * and token accounting, and this class does nothing but translate between nakka's request
 * and response types and the SDK's. Hand-rolling the HTTP would mean owning SSE framing,
 * thinking-block replay rules and beta-header churn for no gain.
 */
final class AnthropicProvider private (
    client: AnthropicClient,
    val modelName: String,
    defaults: AnthropicProvider.Defaults
) extends ModelProvider:

  def name: String = "anthropic"

  /**
   * The SDK is blocking, so calls run on a virtual-thread executor.
   *
   * A dedicated executor rather than the caller's: the agent loop already awaits this on
   * a virtual thread, and borrowing a Pekko dispatcher for a call that can take minutes
   * would starve it.
   */
  private given ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())

  def complete(request: ModelRequest): Future[ModelResponse] =
    Future(translate(client.messages().create(build(request))))

  /**
   * Streams a response as it is generated.
   *
   * Text deltas are emitted as they arrive *and* fed to a `MessageAccumulator`, so the
   * terminal `Completed` chunk carries the same tool calls, stop reason and token usage a
   * non-streaming call would have returned. A caller gets live tokens without giving up
   * anything the batch path provides.
   */
  override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
    Source
      .unfoldResource[Option[ModelChunk], Streaming](
        () => new Streaming(client.messages().createStreaming(build(request))),
        state => state.next(),
        state => state.close()
      )
      // `unfoldResource` pulls from a blocking iterator; keeping it off the default
      // dispatcher stops one slow stream from starving unrelated actors.
      .withAttributes(ActorAttributes.dispatcher("pekko.actor.default-blocking-io-dispatcher"))
      .collect { case Some(chunk) => chunk }
      .mapMaterializedValue(_ => NotUsed)

  // ── Request ───────────────────────────────────────────────────────────────

  private def build(request: ModelRequest): MessageCreateParams =
    val builder = MessageCreateParams
      .builder()
      .model(request.settings.modelName)
      .maxTokens(request.settings.maxTokens.map(_.toLong).getOrElse(defaults.maxTokens))

    request.systemMessage.foreach(text => builder.system(text): Unit)

    // `temperature` is deliberately not forwarded. Sampling parameters were removed from
    // the current Claude models — sending one returns a 400 — so honouring the field
    // here would break every request that set it. Other providers still use it.

    // Adaptive thinking, never a token budget: `budget_tokens` is rejected outright on
    // the current Opus and Fable models.
    val thinking = request.settings.thinking || defaults.thinking
    if thinking then
      builder.thinking(
        ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build())
      ): Unit

    request.settings.effort.orElse(defaults.effort).foreach { effort =>
      builder.outputConfig(OutputConfig.builder().effort(sdkEffort(effort)).build()): Unit
    }

    request.tools.foreach(spec => builder.addTool(tool(spec)): Unit)
    request.messages.foreach(message => appendMessage(builder, message))

    builder.build()

  private def sdkEffort(effort: Effort): OutputConfig.Effort = effort match
    case Effort.Low    => OutputConfig.Effort.LOW
    case Effort.Medium => OutputConfig.Effort.MEDIUM
    case Effort.High   => OutputConfig.Effort.HIGH
    case Effort.XHigh  => OutputConfig.Effort.XHIGH
    case Effort.Max    => OutputConfig.Effort.MAX

  private def tool(spec: ToolSpec): Tool =
    val properties = Tool.InputSchema.Properties.builder()
    spec.inputSchema("properties").foreach {
      case Json.Obj(fields) =>
        fields.foreach { (name, schema) =>
          properties.putAdditionalProperty(name, JsonValue.from(toJava(schema))): Unit
        }
      case _ => ()
    }

    val required = spec.inputSchema("required")
      .flatMap(_.asArray)
      .map(_.flatMap(_.asString).toList.asJava)
      .getOrElse(java.util.List.of[String]())

    Tool
      .builder()
      .name(spec.name)
      .description(spec.description)
      .inputSchema(
        Tool.InputSchema.builder().properties(properties.build()).required(required).build()
      )
      .build()

  private def appendMessage(
      builder: MessageCreateParams.Builder,
      message: ChatMessage
  ): Unit =
    message match
      case ChatMessage.User(content) =>
        val blocks = content.collect { case MessageContent.Text(text) =>
          ContentBlockParam.ofText(text)
        }
        builder.addMessage(
          MessageParam
            .builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(blocks.toList.asJava)
            .build()
        ): Unit

      case ChatMessage.Assistant(text, calls) =>
        val textBlocks =
          if text.isBlank then Vector.empty else Vector(ContentBlockParam.ofText(text))

        val callBlocks = calls.map { call =>
          val input = ToolUseBlockParam.Input.builder()
          call.arguments match
            case Json.Obj(fields) =>
              fields.foreach { (key, value) =>
                input.putAdditionalProperty(key, JsonValue.from(toJava(value))): Unit
              }
            case _ => ()
          ContentBlockParam.ofToolUse(
            ToolUseBlockParam
              .builder()
              .id(call.id)
              .name(call.name)
              .input(input.build())
              .build()
          )
        }

        val blocks = textBlocks ++ callBlocks
        // An assistant turn with no content at all is rejected; skip it rather than
        // send something the API will refuse.
        if blocks.nonEmpty then
          builder.addMessage(
            MessageParam
              .builder()
              .role(MessageParam.Role.ASSISTANT)
              .contentOfBlockParams(blocks.toList.asJava)
              .build()
          ): Unit

      case ChatMessage.ToolResults(results) =>
        // All results in one user message — splitting them trains the model out of
        // asking for tools in parallel.
        val blocks = results.map { result =>
          ContentBlockParam.ofToolResult(
            ToolResultBlockParam
              .builder()
              .toolUseId(result.callId)
              .content(result.content)
              .isError(result.isError)
              .build()
          )
        }
        builder.addMessage(
          MessageParam
            .builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(blocks.toList.asJava)
            .build()
        ): Unit

  // ── Response ──────────────────────────────────────────────────────────────

  private[agent] def translate(message: SdkMessage): ModelResponse =
    val text = message
      .content()
      .asScala
      .flatMap(_.text().toScala)
      .map(_.text())
      .mkString

    val toolCalls = message
      .content()
      .asScala
      .flatMap(_.toolUse().toScala)
      .map { block =>
        ToolCall(
          block.id(),
          block.name(),
          // `_input()` is the raw JSON value; re-parsing keeps nakka's own AST as the
          // single representation the tool layer understands.
          Json.parse(block._input().toString).getOrElse(Json.Obj(Map.empty))
        )
      }
      .toVector

    val stop = message.stopReason().toScala.map(reason => stopReason(reason))

    ModelResponse(
      text = text,
      toolCalls = toolCalls,
      stopReason = stop.getOrElse(StopReason.EndTurn),
      usage = usage(message),
      refusalReason =
        if stop.contains(StopReason.Refusal) then
          message
            .stopDetails()
            .toScala
            .flatMap(_.explanation().toScala)
            .orElse(Some("the model declined the request"))
        else None
    )

  private def stopReason(reason: SdkStopReason): StopReason =
    reason.asString match
      case "end_turn"   => StopReason.EndTurn
      case "tool_use"   => StopReason.ToolUse
      case "max_tokens" => StopReason.MaxTokens
      case "refusal"    => StopReason.Refusal
      case _            => StopReason.Other

  private def usage(message: SdkMessage): TokenUsage =
    val reported = message.usage()
    TokenUsage(
      inputTokens = reported.inputTokens().toInt,
      outputTokens = reported.outputTokens().toInt,
      cacheReadTokens = reported.cacheReadInputTokens().toScala.map(_.toInt).getOrElse(0),
      cacheWriteTokens = reported.cacheCreationInputTokens().toScala.map(_.toInt).getOrElse(0)
    )


  /**
   * Pulls one SSE event at a time, accumulating the whole message as it goes.
   *
   * Mutable and single-threaded by construction: `unfoldResource` guarantees `next` is
   * never called concurrently for one materialisation.
   */
  private[agent] final class Streaming(response: StreamResponse[RawMessageStreamEvent]):

    private val events      = response.stream().iterator()
    private val accumulator = MessageAccumulator.create()
    private var finished    = false

    /** `None` ends the stream; `Some(None)` is an event that produces no chunk. */
    def next(): Option[Option[ModelChunk]] =
      if events.hasNext then
        val event = events.next()
        accumulator.accumulate(event): Unit
        Some(chunkFor(event))
      else if !finished then
        finished = true
        Some(Some(ModelChunk.Completed(translate(accumulator.message()))))
      else None

    def close(): Unit = response.close()

    private def chunkFor(event: RawMessageStreamEvent): Option[ModelChunk] =
      event
        .contentBlockDelta()
        .toScala
        .flatMap(_.delta().text().toScala)
        .map(delta => ModelChunk.TextDelta(delta.text()))
        .orElse(
          // A tool call announces itself before its arguments have streamed in; the
          // complete arguments arrive with the accumulated message.
          event
            .contentBlockStart()
            .toScala
            .flatMap(_.contentBlock().toolUse().toScala)
            .map(block => ModelChunk.ToolCallStarted(ToolCall(block.id(), block.name(), Json.Obj(Map.empty))))
        )

  /** nakka's JSON tree as plain Java values, which is what `JsonValue.from` accepts. */
  private def toJava(value: Json): Any = value match
    case Json.Null       => null
    case Json.Bool(v)    => java.lang.Boolean.valueOf(v)
    case Json.Str(v)     => v
    case Json.Num(v)     =>
      if v.isWhole && v.abs <= Long.MaxValue.toDouble then java.lang.Long.valueOf(v.toLong)
      else java.lang.Double.valueOf(v)
    case Json.Arr(items) => items.map(toJava).toList.asJava
    case Json.Obj(fields) =>
      fields.map((key, field) => key -> toJava(field)).asJava

object AnthropicProvider:

  /**
   * `claude-opus-5` by default.
   *
   * Choosing a cheaper model is a decision for whoever runs the service, not a default
   * nakka should make on their behalf.
   */
  val DefaultModel: String = "claude-opus-5"

  final case class Defaults(
      maxTokens: Long = 16000L,
      thinking: Boolean = true,
      effort: Option[Effort] = None
  )

  /** Reads `ANTHROPIC_API_KEY` from the environment. */
  def fromEnv(
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults()
  ): AnthropicProvider =
    new AnthropicProvider(AnthropicOkHttpClient.fromEnv(), modelName, defaults)

  def withApiKey(
      apiKey: String,
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults()
  ): AnthropicProvider =
    new AnthropicProvider(
      AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
      modelName,
      defaults
    )

  /** For a pre-configured client — a proxy, a custom timeout, Bedrock or Vertex. */
  def apply(
      client: AnthropicClient,
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults()
  ): AnthropicProvider =
    new AnthropicProvider(client, modelName, defaults)
