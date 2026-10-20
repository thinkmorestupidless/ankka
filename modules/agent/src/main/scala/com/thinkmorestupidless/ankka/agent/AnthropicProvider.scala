package com.thinkmorestupidless.ankka.agent

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
import com.anthropic.errors.AnthropicServiceException
import com.thinkmorestupidless.ankka.agent.providers.{ModelProviders, Quirks}
import org.slf4j.LoggerFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

/**
 * Talks to Claude through Anthropic's own Java SDK.
 *
 * The SDK is used only as transport: ankka owns the agent loop, tool dispatch, memory and token
 * accounting, and this class does nothing but translate between ankka's request and response types
 * and the SDK's. Hand-rolling the HTTP would mean owning SSE framing, thinking-block replay rules
 * and beta-header churn for no gain.
 */
final class AnthropicProvider private (
    connection: Either[String, AnthropicClient],
    val modelName: String,
    defaults: AnthropicProvider.Defaults,
    val name: String,
    quirks: Quirks
) extends ModelProvider:

  ModelProviders.validName(name).left.foreach(problem => throw IllegalArgumentException(problem))

  /**
   * The SDK is blocking, so calls run on a virtual-thread executor.
   *
   * A dedicated executor rather than the caller's: the agent loop already awaits this on a virtual
   * thread, and borrowing a Pekko dispatcher for a call that can take minutes would starve it.
   */
  private given ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())

  def complete(request: ModelRequest): Future[ModelResponse] =
    connection match
      case Left(reason) => Future.failed(ModelCallFailed(name, reason))
      case Right(client) =>
        Future(
          try translate(client.messages().create(build(request)))
          catch case NonFatal(failure) => throw AnthropicProvider.failed(name, failure)
        )

  /**
   * Streams a response as it is generated.
   *
   * Text deltas are emitted as they arrive *and* fed to a `MessageAccumulator`, so the terminal
   * `Completed` chunk carries the same tool calls, stop reason and token usage a non-streaming call
   * would have returned. A caller gets live tokens without giving up anything the batch path
   * provides.
   */
  override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
    connection match
      case Left(reason)  => Source.failed(ModelCallFailed(name, reason))
      case Right(client) => streamWith(client, request)

  private def streamWith(
      client: AnthropicClient,
      request: ModelRequest
  ): Source[ModelChunk, NotUsed] =
    Source
      .unfoldResource[Option[ModelChunk], Streaming](
        () =>
          try new Streaming(client.messages().createStreaming(build(request)))
          catch case NonFatal(failure) => throw AnthropicProvider.failed(name, failure),
        state =>
          try state.next()
          catch case NonFatal(failure) => throw AnthropicProvider.failed(name, failure),
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
    // the current Opus and Fable models. Both are Anthropic's own fields, sent only where the
    // endpoint is known to take them: a compatible server may refuse what it does not know.
    val thinking = quirks.sendsThinking && (request.settings.thinking || defaults.thinking)
    if thinking then
      builder.thinking(
        ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build())
      ): Unit

    request.settings.effort.orElse(defaults.effort).filter(_ => quirks.sendsThinking).foreach {
      effort =>
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

    val required = spec
      .inputSchema("required")
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

      case ChatMessage.Assistant(text, calls, _) =>
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
          // `_input()` is the raw JSON value; re-parsing keeps ankka's own AST as the
          // single representation the tool layer understands. Its `toString` is a Java map's
          // rendering, not JSON, so it is written through the SDK's own mapper.
          Json.parse(AnthropicProvider.render(block._input())).getOrElse(Json.Obj(Map.empty))
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
   * Mutable and single-threaded by construction: `unfoldResource` guarantees `next` is never called
   * concurrently for one materialisation.
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
            .map(block =>
              ModelChunk.ToolCallStarted(ToolCall(block.id(), block.name(), Json.Obj(Map.empty)))
            )
        )

  /** ankka's JSON tree as plain Java values, which is what `JsonValue.from` accepts. */
  private def toJava(value: Json): Any = value match
    case Json.Null    => null
    case Json.Bool(v) => java.lang.Boolean.valueOf(v)
    case Json.Str(v)  => v
    case Json.Num(v) =>
      if v.isWhole && v.abs <= Long.MaxValue.toDouble then java.lang.Long.valueOf(v.toLong)
      else java.lang.Double.valueOf(v)
    case Json.Arr(items) => items.map(toJava).toList.asJava
    case Json.Obj(fields) =>
      fields.map((key, field) => key -> toJava(field)).asJava

object AnthropicProvider:

  /**
   * `claude-opus-5` by default.
   *
   * Choosing a cheaper model is a decision for whoever runs the service, not a default ankka should
   * make on their behalf.
   */
  val DefaultModel: String = "claude-opus-5"

  final case class Defaults(
      maxTokens: Long = 16000L,
      thinking: Boolean = true,
      effort: Option[Effort] = None
  )

  /** The name a provider of Anthropic's own API answers under unless given another. */
  val Name: String = "anthropic"

  private val KeyVariable = "ANTHROPIC_API_KEY"

  private val log = LoggerFactory.getLogger(classOf[AnthropicProvider])

  /**
   * Reads `ANTHROPIC_API_KEY` from the environment.
   *
   * Without it the provider still exists, under its name, and every call fails naming the variable:
   * a service with one vendor's key and not the other's starts, and refuses only the work that
   * needs the missing one.
   */
  def fromEnv(
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults()
  ): AnthropicProvider =
    if sys.env.get(KeyVariable).exists(_.nonEmpty) then
      new AnthropicProvider(
        Right(AnthropicOkHttpClient.fromEnv()),
        modelName,
        defaults,
        Name,
        Quirks.anthropic
      )
    else withoutKey(modelName, defaults)

  /**
   * The same, from the variables given rather than the process's: `ANTHROPIC_API_KEY` and
   * `ANTHROPIC_BASE_URL`.
   */
  def fromEnv(env: Map[String, String], modelName: String, defaults: Defaults): AnthropicProvider =
    env.get(KeyVariable).filter(_.nonEmpty) match
      case None => withoutKey(modelName, defaults)
      case Some(key) =>
        val builder = AnthropicOkHttpClient.builder().apiKey(key)
        env.get("ANTHROPIC_BASE_URL").filter(_.nonEmpty).foreach(url => builder.baseUrl(url): Unit)
        new AnthropicProvider(Right(builder.build()), modelName, defaults, Name, Quirks.anthropic)

  private def withoutKey(modelName: String, defaults: Defaults): AnthropicProvider =
    log.warn(
      "model provider '{}' has no key: {} is not set, so every call to it fails until it is",
      Name,
      KeyVariable
    )
    new AnthropicProvider(
      Left(s"$KeyVariable is not set"),
      modelName,
      defaults,
      Name,
      Quirks.anthropic
    )

  def withApiKey(
      apiKey: String,
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults()
  ): AnthropicProvider =
    new AnthropicProvider(
      Right(AnthropicOkHttpClient.builder().apiKey(apiKey).build()),
      modelName,
      defaults,
      Name,
      Quirks.anthropic
    )

  /** For a pre-configured client — a proxy, a custom timeout, Bedrock or Vertex. */
  def apply(
      client: AnthropicClient,
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults(),
      name: String = Name
  ): AnthropicProvider =
    new AnthropicProvider(Right(client), modelName, defaults, name, Quirks.anthropic)

  /**
   * Anthropic's protocol at any address, under a name the service chose: a local server that speaks
   * it, or Anthropic's own address behind a proxy. With no key the placeholder `none` is sent,
   * which a local server ignores. The address is the server's root; a trailing `/v1` is dropped,
   * since the protocol's paths begin with it.
   *
   * Nothing is retried here: a failed call is the provider's failure, as ankka treats every one.
   */
  def at(
      name: String,
      address: String,
      modelName: String,
      key: Option[String] = None,
      defaults: Defaults = Defaults(),
      quirks: Quirks = Quirks.compatibleAnthropic
  ): AnthropicProvider =
    val root = address.stripSuffix("/").stripSuffix("/v1")
    val client = AnthropicOkHttpClient
      .builder()
      .baseUrl(root)
      .apiKey(key.filter(_.nonEmpty).getOrElse(ModelProviders.NoKey))
      .maxRetries(0)
      .build()
    new AnthropicProvider(Right(client), modelName, defaults, name, quirks)

  /**
   * A failure in ankka's words: the provider's name, and the endpoint's status and message or what
   * stopped the connection. Never the SDK's own: a provider the service named `local` must not
   * report itself as a vendor's.
   */
  private[agent] def failed(name: String, failure: Throwable): ModelCallFailed =
    failure match
      case already: ModelCallFailed => already
      case service: AnthropicServiceException =>
        ModelCallFailed(
          name,
          s"answered ${service.statusCode()}: ${summary(render(service.body()))}"
        )
      case other =>
        ModelCallFailed(name, s"could not be reached: ${rootMessage(other)}", Some(other))

  /** A value of the SDK's as JSON text. */
  private[agent] def render(value: AnyRef): String =
    com.anthropic.core.ObjectMappers.jsonMapper().writeValueAsString(value)

  private[agent] def summary(body: String): String =
    val message = Json.parse(body).toOption.flatMap { json =>
      json("error")
        .flatMap(e => e("message").flatMap(_.asString).orElse(e.asString))
        .orElse(json("message").flatMap(_.asString))
    }
    val text = message.getOrElse(body)
    if text.length > 300 then text.take(300) + "…" else text

  private[agent] def rootMessage(failure: Throwable): String =
    var cause = failure
    while cause.getCause != null && cause.getCause != cause do cause = cause.getCause
    Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getSimpleName)
