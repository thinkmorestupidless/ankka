package com.thinkmorestupidless.ankka.agent.openai

import com.openai.core.http.StreamResponse
import com.openai.models.responses.{ResponseCreateParams, ResponseStreamEvent}
import com.thinkmorestupidless.ankka.agent.*

/**
 * ankka's request and response in OpenAI's Responses shape, spoken without state on the vendor's
 * side.
 *
 * Nothing is ever stored by the endpoint (`store: false`, no `previous_response_id`): the whole
 * conversation is sent each time, as ankka sends it to every provider. What the endpoint would
 * otherwise have kept — the reasoning a model did before calling a tool — comes back as provider
 * state: the answer's whole output, replayed as it was returned before the tool's result on the
 * next call of the turn, which keeps each reasoning item in front of the call it led to without
 * ankka knowing the rule.
 */
private[openai] object ResponsesShape:

  def build(
      provider: String,
      request: ModelRequest,
      settings: OpenAiSettings,
      traceparent: Option[String] = None
  ): ResponseCreateParams =
    val builder = ResponseCreateParams
      .builder()
      .model(request.settings.modelName)
      .store(false)
      .maxOutputTokens(settings.maxTokens(request))
    request.systemMessage.foreach(text => builder.instructions(text): Unit)
    settings.temperature(request).foreach(t => builder.temperature(t): Unit)
    settings.reasoningEffort(request).foreach { effort =>
      builder.putAdditionalBodyProperty(
        "reasoning",
        OpenAiProvider.jsonValue(Json.obj("effort" -> Json.str(effort)))
      ): Unit
    }
    // Reasoning items come back encrypted by default without state; asking costs nothing and
    // helps a compatible server that still needs to be asked.
    if settings.reasons then
      builder.putAdditionalBodyProperty(
        "include",
        OpenAiProvider.jsonValue(Json.arr(Json.str("reasoning.encrypted_content")))
      ): Unit
    builder.putAdditionalBodyProperty(
      "input",
      OpenAiProvider.jsonValue(Json.Arr(input(provider, request.messages)))
    ): Unit
    if request.tools.nonEmpty then
      builder.putAdditionalBodyProperty(
        "tools",
        OpenAiProvider.jsonValue(Json.Arr(request.tools.map(tool)))
      ): Unit
    traceparent.foreach(t => builder.putAdditionalHeader("traceparent", t): Unit)
    builder.build()

  private def tool(spec: ToolSpec): Json =
    Json.obj(
      "type"        -> Json.str("function"),
      "name"        -> Json.str(spec.name),
      "description" -> Json.str(spec.description),
      "parameters"  -> spec.inputSchema,
      // Strict schemas demand every property be required and nothing else allowed, which a tool's
      // own schema need not say; ankka checks the arguments itself when the tool runs.
      "strict" -> Json.bool(false)
    )

  private[openai] def input(provider: String, messages: Vector[ChatMessage]): Vector[Json] =
    messages.flatMap {
      case ChatMessage.User(content) =>
        Vector(
          Json.obj(
            "type" -> Json.str("message"),
            "role" -> Json.str("user"),
            "content" -> Json.str(
              content.collect { case MessageContent.Text(t) => t }.mkString("\n")
            )
          )
        )

      case ChatMessage.Assistant(_, _, Some(state)) if state.provider == provider =>
        // The answer as the endpoint gave it: its reasoning, its text and its calls, in its order.
        Json.parse(state.value).toOption.flatMap(_.asArray).getOrElse(Vector.empty)

      case ChatMessage.Assistant(text, calls, _) =>
        val said =
          if text.isBlank then Vector.empty
          else
            Vector(
              Json.obj(
                "type" -> Json.str("message"),
                "role" -> Json.str("assistant"),
                "content" -> Json.arr(
                  Json.obj("type" -> Json.str("output_text"), "text" -> Json.str(text))
                )
              )
            )
        said ++ calls.map { call =>
          Json.obj(
            "type"      -> Json.str("function_call"),
            "call_id"   -> Json.str(call.id),
            "name"      -> Json.str(call.name),
            "arguments" -> Json.str(call.arguments.render)
          )
        }

      case ChatMessage.ToolResults(results) =>
        results.map { result =>
          Json.obj(
            "type"    -> Json.str("function_call_output"),
            "call_id" -> Json.str(result.callId),
            "output"  -> Json.str(Answers.toolOutput(result))
          )
        }
    }

  def translate(provider: String, response: Json): ModelResponse =
    if response("status").flatMap(_.asString).contains("failed") then
      throw ModelCallFailed(provider, failure(response))
    val output = response("output").flatMap(_.asArray).getOrElse(Vector.empty)
    val parts = output
      .filter(_("type").flatMap(_.asString).contains("message"))
      .flatMap(_("content").flatMap(_.asArray).getOrElse(Vector.empty))
    val text = parts
      .filter(_("type").flatMap(_.asString).contains("output_text"))
      .flatMap(_("text").flatMap(_.asString))
      .mkString
    val refusal = parts
      .filter(_("type").flatMap(_.asString).contains("refusal"))
      .flatMap(_("refusal").flatMap(_.asString))
      .headOption
    val calls = output.filter(_("type").flatMap(_.asString).contains("function_call")).map { item =>
      ToolCall(
        item("call_id").flatMap(_.asString).getOrElse(""),
        item("name").flatMap(_.asString).getOrElse(""),
        Answers.arguments(item("arguments").flatMap(_.asString))
      )
    }
    val reasoned = output.exists(_("type").flatMap(_.asString).contains("reasoning"))
    val cutOff = response("incomplete_details")
      .flatMap(_("reason"))
      .flatMap(_.asString)
      .contains("max_output_tokens")
    val stop =
      if refusal.isDefined then StopReason.Refusal
      else if cutOff then StopReason.MaxTokens
      else if calls.nonEmpty then StopReason.ToolUse
      else if response("status").flatMap(_.asString).forall(_ == "completed") then
        StopReason.EndTurn
      else StopReason.Other
    val usage = response("usage")
    ModelResponse(
      text = text,
      toolCalls = calls,
      stopReason = stop,
      usage = Answers.usage(
        Answers.int(usage.flatMap(_("input_tokens"))),
        Answers.int(usage.flatMap(_("input_tokens_details")).flatMap(_("cached_tokens"))),
        Answers.int(usage.flatMap(_("output_tokens")))
      ),
      refusalReason = refusal,
      providerState =
        Option.when(reasoned && calls.nonEmpty)(ProviderState(provider, Json.Arr(output).render))
    )

  private def failure(response: Json): String =
    response("error").flatMap(_("message")).flatMap(_.asString).getOrElse("the response failed")

  /** Text deltas as they arrive; the terminal response from the event that completes the stream. */
  final class Streaming(provider: String, response: StreamResponse[ResponseStreamEvent])
      extends OpenAiStreaming:

    private val events = response.stream().iterator()
    private var ended  = false

    def next(): Option[Option[ModelChunk]] =
      if ended || !events.hasNext then None
      else
        val event = OpenAiProvider.json(events.next())
        event("type").flatMap(_.asString) match
          case Some("response.output_text.delta") =>
            Some(event("delta").flatMap(_.asString).filter(_.nonEmpty).map(ModelChunk.TextDelta(_)))
          case Some("response.output_item.added") =>
            Some(event("item").filter(_("type").flatMap(_.asString).contains("function_call")).map {
              item =>
                ModelChunk.ToolCallStarted(
                  ToolCall(
                    item("call_id").flatMap(_.asString).getOrElse(""),
                    item("name").flatMap(_.asString).getOrElse(""),
                    Json.Obj(Map.empty)
                  )
                )
            })
          case Some("response.completed" | "response.incomplete") =>
            ended = true
            Some(event("response").map(r => ModelChunk.Completed(translate(provider, r))))
          case Some("response.failed") =>
            throw ModelCallFailed(
              provider,
              event("response").map(failure).getOrElse("the response failed")
            )
          case Some("error") =>
            throw ModelCallFailed(
              provider,
              event("message").flatMap(_.asString).getOrElse("the stream failed")
            )
          case _ => Some(None)

    def close(): Unit = response.close()
