package com.thinkmorestupidless.ankka.agent.openai

import com.openai.core.JsonField
import com.openai.core.http.StreamResponse
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.chat.completions.{
  ChatCompletionChunk,
  ChatCompletionCreateParams,
  ChatCompletionMessageParam
}
import com.thinkmorestupidless.ankka.agent.*

/**
 * ankka's request and response in OpenAI's Chat Completions shape: what every compatible endpoint
 * serves, local servers included.
 *
 * Only what the shape defines is sent. The most tokens go as `max_tokens`, which every server
 * takes; a reasoning effort only where the preset knows the endpoint's values or one was asked for;
 * a temperature where the endpoint takes one; and a stream asks for its usage at the end where the
 * endpoint allows asking.
 */
private[openai] object ChatCompletionsShape:

  def build(
      request: ModelRequest,
      settings: OpenAiSettings,
      stream: Boolean
  ): ChatCompletionCreateParams =
    val messages = request.systemMessage
      .map(text => Json.obj("role" -> Json.str("system"), "content" -> Json.str(text)))
      .toVector ++ conversation(request.messages)
    val builder = ChatCompletionCreateParams
      .builder()
      .model(request.settings.modelName)
      // The messages are built whole, as ankka's JSON; the setter takes any JSON in place of the
      // SDK's own types, which erasure makes this cast safe for.
      .messages(
        OpenAiProvider
          .jsonValue(Json.Arr(messages))
          .asInstanceOf[JsonField[java.util.List[ChatCompletionMessageParam]]]
      )
    builder.putAdditionalBodyProperty(
      "max_tokens",
      OpenAiProvider.jsonValue(Json.num(settings.maxTokens(request).toDouble))
    ): Unit
    settings.temperature(request).foreach(t => builder.temperature(t): Unit)
    settings.reasoningEffort(request).foreach { effort =>
      builder.putAdditionalBodyProperty(
        "reasoning_effort",
        OpenAiProvider.jsonValue(Json.str(effort))
      ): Unit
    }
    if stream && settings.quirks.streamUsage then
      builder.putAdditionalBodyProperty(
        "stream_options",
        OpenAiProvider.jsonValue(Json.obj("include_usage" -> Json.bool(true)))
      ): Unit
    if request.tools.nonEmpty then
      builder.putAdditionalBodyProperty(
        "tools",
        OpenAiProvider.jsonValue(Json.Arr(request.tools.map(tool)))
      ): Unit
    builder.build()

  private def tool(spec: ToolSpec): Json =
    Json.obj(
      "type" -> Json.str("function"),
      "function" -> Json.obj(
        "name"        -> Json.str(spec.name),
        "description" -> Json.str(spec.description),
        "parameters"  -> spec.inputSchema
      )
    )

  private[openai] def conversation(messages: Vector[ChatMessage]): Vector[Json] =
    messages.flatMap {
      case ChatMessage.User(content) =>
        Vector(
          Json.obj(
            "role" -> Json.str("user"),
            "content" -> Json.str(
              content.collect { case MessageContent.Text(t) => t }.mkString("\n")
            )
          )
        )

      // Another provider's state means nothing here, and this shape has none of its own.
      case ChatMessage.Assistant(text, calls, _) =>
        if text.isBlank && calls.isEmpty then Vector.empty
        else
          val content = if text.isBlank then Json.Null else Json.str(text)
          val called =
            if calls.isEmpty then Vector.empty
            else
              Vector(
                "tool_calls" -> Json.Arr(calls.map { call =>
                  Json.obj(
                    "id"   -> Json.str(call.id),
                    "type" -> Json.str("function"),
                    "function" -> Json.obj(
                      "name"      -> Json.str(call.name),
                      "arguments" -> Json.str(call.arguments.render)
                    )
                  )
                })
              )
          Vector(
            Json.Obj(
              (Vector("role" -> Json.str("assistant"), "content" -> content) ++ called).toMap
            )
          )

      case ChatMessage.ToolResults(results) =>
        results.map { result =>
          Json.obj(
            "role"         -> Json.str("tool"),
            "tool_call_id" -> Json.str(result.callId),
            "content"      -> Json.str(Answers.toolOutput(result))
          )
        }
    }

  def translate(completion: Json): ModelResponse =
    val choice  = completion("choices").flatMap(_.asArray).flatMap(_.headOption)
    val message = choice.flatMap(_("message"))
    val text    = message.flatMap(_("content")).flatMap(_.asString).getOrElse("")
    val refusal = message.flatMap(_("refusal")).flatMap(_.asString).filter(_.nonEmpty)
    val calls =
      message.flatMap(_("tool_calls")).flatMap(_.asArray).getOrElse(Vector.empty).map { call =>
        val function = call("function")
        ToolCall(
          call("id").flatMap(_.asString).getOrElse(""),
          function.flatMap(_("name")).flatMap(_.asString).getOrElse(""),
          Answers.arguments(function.flatMap(_("arguments")).flatMap(_.asString))
        )
      }
    val stop =
      if refusal.isDefined then StopReason.Refusal
      else
        choice.flatMap(_("finish_reason")).flatMap(_.asString) match
          case Some("length")                             => StopReason.MaxTokens
          case Some("tool_calls") | Some("function_call") => StopReason.ToolUse
          case _ if calls.nonEmpty                        => StopReason.ToolUse
          case Some("stop") | None                        => StopReason.EndTurn
          case Some("content_filter")                     => StopReason.Other
          case Some(_)                                    => StopReason.EndTurn
    val usage = completion("usage")
    ModelResponse(
      text = text,
      toolCalls = calls,
      stopReason = stop,
      usage = Answers.usage(
        Answers.int(usage.flatMap(_("prompt_tokens"))),
        Answers.int(usage.flatMap(_("prompt_tokens_details")).flatMap(_("cached_tokens"))),
        Answers.int(usage.flatMap(_("completion_tokens")))
      ),
      refusalReason = refusal
    )

  /**
   * Text deltas as they arrive, fed to the SDK's accumulator as well, so the terminal chunk carries
   * the tool calls, whose arguments arrive in fragments, complete and nowhere earlier.
   */
  final class Streaming(response: StreamResponse[ChatCompletionChunk]) extends OpenAiStreaming:

    private val chunks      = response.stream().iterator()
    private val accumulator = ChatCompletionAccumulator.create()
    private var announced   = Set.empty[String]
    private var finished    = false
    private var any         = false

    def next(): Option[Option[ModelChunk]] =
      if chunks.hasNext then
        val chunk = chunks.next()
        accumulator.accumulate(chunk): Unit
        any = true
        val delta = OpenAiProvider
          .json(chunk)("choices")
          .flatMap(_.asArray)
          .flatMap(_.headOption)
          .flatMap(_("delta"))
        val text = delta.flatMap(_("content")).flatMap(_.asString).filter(_.nonEmpty)
        val started = delta
          .flatMap(_("tool_calls"))
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)
          .flatMap { call =>
            for
              id   <- call("id").flatMap(_.asString) if !announced.contains(id)
              name <- call("function").flatMap(_("name")).flatMap(_.asString)
            yield
              announced += id
              ModelChunk.ToolCallStarted(ToolCall(id, name, Json.Obj(Map.empty)))
          }
          .headOption
        Some(text.map(ModelChunk.TextDelta(_)).orElse(started))
      else if !finished && any then
        finished = true
        Some(
          Some(ModelChunk.Completed(translate(OpenAiProvider.json(accumulator.chatCompletion()))))
        )
      else None

    def close(): Unit = response.close()
