package com.thinkmorestupidless.ankka.agent.openai

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.providers.{Presets, Quirks, Shape, ToolCallIds}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * The OpenAI provider against a stand-in on loopback, in both of its shapes: what it sends, and
 * what it makes of what it is answered. No key and no network, so `sbt test` proves the
 * translation.
 */
class OpenAiProviderSuite extends munit.FunSuite:

  override val munitTimeout = 1.minute

  // A plain actor system for the streams: one that formed a cluster would bind Pekko's fixed port.
  private given system: ActorSystem =
    ActorSystem(
      "openai-provider-suite",
      ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit =
    Await.result(system.terminate(), 30.seconds): Unit

  private val standIn = FunFixture[ModelStandIn](_ => ModelStandIn.start(), _.close())

  private val lookup = ToolSpec(
    "lookup",
    "Looks a thing up",
    Json.obj(
      "type"       -> Json.str("object"),
      "properties" -> Json.obj("city" -> Json.obj("type" -> Json.str("string"))),
      "required"   -> Json.arr(Json.str("city"))
    )
  )

  private def request(messages: ChatMessage*): ModelRequest =
    ModelRequest(
      ModelSettings("test-model"),
      Some("You are helpful."),
      messages.toVector,
      Vector(lookup)
    )

  private def ask(text: String): ModelRequest = request(ChatMessage.User.text(text))

  private def provider(
      at: ModelStandIn,
      shape: Shape,
      name: String = "local",
      key: Option[String] = None,
      quirks: Quirks = Quirks.compatibleOpenAi,
      defaults: OpenAiProvider.Defaults = OpenAiProvider.Defaults()
  ): OpenAiProvider =
    OpenAiProvider.at(name, s"${at.address}/v1", "test-model", key, shape, defaults, quirks)

  private def complete(p: ModelProvider, r: ModelRequest): ModelResponse =
    Await.result(p.complete(r), 30.seconds)

  private def streamed(p: ModelProvider, r: ModelRequest): Vector[ModelChunk] =
    Await.result(p.stream(r).runWith(Sink.seq), 30.seconds).toVector

  private def failure(p: ModelProvider, r: ModelRequest): ModelCallFailed =
    intercept[ModelCallFailed](complete(p, r))

  private val shapes =
    Vector(Shape.Responses -> "/v1/responses", Shape.ChatCompletions -> "/v1/chat/completions")

  private val call = ToolCall("call-1", "lookup", Json.obj("city" -> Json.str("Berlin")))

  for (shape, path) <- shapes do
    val s = shape.wireName

    standIn.test(s"$s: a plain answer is the model's text, ended, with usage in ankka's units") {
      at =>
        at.answer(
          ModelResponse(
            "Paris",
            usage = TokenUsage(inputTokens = 10, outputTokens = 3, cacheReadTokens = 4)
          )
        )
        val response = complete(provider(at, shape), ask("capital of France?"))
        assertEquals(response.text, "Paris")
        assertEquals(response.stopReason, StopReason.EndTurn)
        assertEquals(
          response.usage,
          TokenUsage(inputTokens = 10, outputTokens = 3, cacheReadTokens = 4)
        )
        assertEquals(at.lastRequest.path, path)
        assertEquals(at.lastRequest.body("model").flatMap(_.asString), Some("test-model"))
    }

    standIn.test(s"$s: a tool call has its arguments parsed and stops for the tool") { at =>
      at.answer(ModelResponse("", Vector(call), StopReason.ToolUse))
      val response = complete(provider(at, shape), ask("weather?"))
      assertEquals(response.toolCalls, Vector(call))
      assertEquals(response.stopReason, StopReason.ToolUse)
      val tools = at.lastRequest.body("tools").flatMap(_.asArray).getOrElse(Vector.empty)
      assertEquals(tools.size, 1, at.lastRequest.body.render)
    }

    standIn.test(s"$s: a tool's result is sent paired with its call by id") { at =>
      at.answer(ModelResponse("Sunny."))
      val r = request(
        ChatMessage.User.text("weather?"),
        ChatMessage.Assistant("", Vector(call)),
        ChatMessage.ToolResults(Vector(ToolResult("call-1", "lookup", "sunny")))
      )
      assertEquals(complete(provider(at, shape), r).text, "Sunny.")
      val sent = at.lastRequest.body.render
      shape match
        case Shape.Responses =>
          assert(sent.contains("\"type\":\"function_call\""), sent)
          assert(sent.contains("\"type\":\"function_call_output\""), sent)
        case Shape.ChatCompletions =>
          assert(sent.contains("\"tool_call_id\":\"call-1\""), sent)
          assert(sent.contains("\"tool_calls\""), sent)
      assertEquals("\"call-1\"".r.findAllIn(sent).size >= 2, true, sent)
    }

    standIn.test(s"$s: a refusal is a refusal with its reason, not an answer") { at =>
      at.answer(
        ModelResponse("", stopReason = StopReason.Refusal, refusalReason = Some("outside policy"))
      )
      val response = complete(provider(at, shape), ask("something"))
      assertEquals(response.stopReason, StopReason.Refusal)
      assertEquals(response.refusalReason, Some("outside policy"))
    }

    standIn.test(s"$s: a length stop is the most tokens") { at =>
      at.answer(ModelResponse("Once upon", stopReason = StopReason.MaxTokens))
      assertEquals(complete(provider(at, shape), ask("a story")).stopReason, StopReason.MaxTokens)
    }

    standIn.test(s"$s: an endpoint that reports no usage is recorded as none") { at =>
      at.reportNoUsage().answer(ModelResponse("Hi", usage = TokenUsage(5, 5)))
      assertEquals(complete(provider(at, shape), ask("hi")).usage, TokenUsage.zero)
    }

    standIn.test(
      s"$s: a stream sends text as it comes and ends with what a completion would carry"
    ) { at =>
      at.answer(ModelResponse("It is sunny", usage = TokenUsage(7, 2)))
      at.answer(ModelResponse("", Vector(call), StopReason.ToolUse, TokenUsage(7, 2)))
      val chunks = streamed(provider(at, shape), ask("weather?"))
      val deltas = chunks.collect { case ModelChunk.TextDelta(t) => t }
      assert(deltas.size > 1, chunks.toString)
      assertEquals(deltas.mkString, "It is sunny")
      chunks.last match
        case ModelChunk.Completed(r) =>
          assertEquals(r.text, "It is sunny")
          assertEquals(r.usage, TokenUsage(7, 2))
        case other => fail(s"the last chunk is $other")
      val called = streamed(provider(at, shape), ask("weather?"))
      called.last match
        case ModelChunk.Completed(r) =>
          assertEquals(r.toolCalls, Vector(call), "the arguments are whole in the terminal chunk")
          assertEquals(r.stopReason, StopReason.ToolUse)
        case other => fail(s"the last chunk is $other")
      assert(at.requests.forall(_.body("stream").flatMap(_.asBoolean).contains(true)))
    }

    standIn.test(
      s"$s: a rate limit and a server error are the provider's failure, under its name"
    ) { at =>
      at.failNext(429, """{"error":{"message":"slow down"}}""")
      val limited = failure(provider(at, shape), ask("hi"))
      assertEquals(limited.provider, "local")
      assert(
        limited.getMessage.contains("429") && limited.getMessage.contains("slow down"),
        limited.getMessage
      )
      at.failNext(500)
      val broken = failure(provider(at, shape), ask("hi"))
      assert(broken.getMessage.contains("500"), broken.getMessage)
      Vector(limited, broken).foreach { f =>
        assert(
          !f.getMessage.toLowerCase.contains("openai"),
          s"a provider named local said: ${f.getMessage}"
        )
      }
      assertEquals(at.requests.size, 2, "nothing is retried")
    }

    standIn.test(
      s"$s: a model the endpoint does not know fails the call, not the provider's construction"
    ) { at =>
      at.failNext(404, """{"error":{"message":"model 'nope' not found"}}""")
      val p = provider(at, shape)
      assert(failure(p, ask("hi")).getMessage.contains("not found"))
    }

    standIn.test(s"$s: an address nothing listens on fails naming the provider, never the SDK") {
      _ =>
        val closed = ModelStandIn.start()
        closed.close()
        val f = failure(provider(closed, shape), ask("hi"))
        assertEquals(f.provider, "local")
        assert(f.getMessage.startsWith("local: could not be reached"), f.getMessage)
        assert(!f.getMessage.contains("OpenAI"), f.getMessage)
    }

    standIn.test(
      s"$s: the key is the bearer, and an address that needs none is sent the placeholder"
    ) { at =>
      at.answer(ModelResponse("a")).answer(ModelResponse("b"))
      complete(provider(at, shape, key = Some("sk-local")), ask("hi"))
      assertEquals(at.lastRequest.header("authorization"), Some("Bearer sk-local"))
      complete(provider(at, shape), ask("hi"))
      assertEquals(at.lastRequest.header("authorization"), Some("Bearer none"))
    }

    standIn.test(s"$s: ankka never sends a tool choice") { at =>
      at.answer(ModelResponse("ok"))
      complete(provider(at, shape), ask("hi"))
      assertEquals(at.lastRequest.body("tool_choice"), None, at.lastRequest.body.render)
    }

    standIn.test(s"$s: a provider reports the name it was given") { at =>
      assertEquals(provider(at, shape, name = "fast").name, "fast")
      assertEquals(provider(at, shape).modelName, "test-model")
    }

  // ── Settings ───────────────────────────────────────────────────────────────

  private def effortSent(at: ModelStandIn, shape: Shape): Option[String] =
    shape match
      case Shape.Responses =>
        at.lastRequest.body("reasoning").flatMap(_("effort")).flatMap(_.asString)
      case Shape.ChatCompletions => at.lastRequest.body("reasoning_effort").flatMap(_.asString)

  for (shape, _) <- shapes do
    val s = shape.wireName
    val known =
      Quirks.compatibleOpenAi.copy(reasoningEfforts = Some(Vector("low", "medium", "high")))

    standIn.test(s"$s: an effort above what the model offers is the highest it offers") { at =>
      at.answer(ModelResponse("ok"))
      complete(
        provider(
          at,
          shape,
          quirks = known,
          defaults = OpenAiProvider.Defaults(effort = Some(Effort.Max))
        ),
        ask("hi")
      )
      assertEquals(effortSent(at, shape), Some("high"))
    }

    standIn.test(
      s"$s: thinking off is the lowest effort offered, or nothing on an endpoint with none"
    ) { at =>
      at.answer(ModelResponse("a")).answer(ModelResponse("b"))
      val off = OpenAiProvider.Defaults(thinking = false, effort = Some(Effort.High))
      complete(provider(at, shape, quirks = known, defaults = off), ask("hi"))
      assertEquals(effortSent(at, shape), Some("low"))
      complete(provider(at, shape, defaults = off), ask("hi"))
      assertEquals(effortSent(at, shape), None)
    }

    standIn.test(s"$s: a bare endpoint is sent an effort only when one was asked for, as asked") {
      at =>
        at.answer(ModelResponse("a")).answer(ModelResponse("b"))
        complete(provider(at, shape), ask("hi"))
        assertEquals(effortSent(at, shape), None)
        complete(
          provider(at, shape, defaults = OpenAiProvider.Defaults(effort = Some(Effort.Medium))),
          ask("hi")
        )
        assertEquals(effortSent(at, shape), Some("medium"))
    }

    standIn.test(
      s"$s: a temperature is sent where the endpoint takes one and withheld from OpenAI's own"
    ) { at =>
      at.answer(ModelResponse("a")).answer(ModelResponse("b"))
      val warm = OpenAiProvider.Defaults(temperature = Some(0.4))
      complete(provider(at, shape, defaults = warm), ask("hi"))
      assertEquals(at.lastRequest.body("temperature").flatMap(_.asDouble), Some(0.4))
      complete(provider(at, shape, quirks = Quirks.openAi, defaults = warm), ask("hi"))
      assertEquals(at.lastRequest.body("temperature"), None)
    }

  standIn.test("chat-completions: a stream asks for its usage where the endpoint allows asking") {
    at =>
      at.answer(ModelResponse("a", usage = TokenUsage(3, 1)))
        .answer(ModelResponse("b", usage = TokenUsage(3, 1)))
      val asking = streamed(provider(at, Shape.ChatCompletions), ask("hi")).last
      assertEquals(asking, ModelChunk.Completed(ModelResponse("a", usage = TokenUsage(3, 1))))
      val notAsking = provider(
        at,
        Shape.ChatCompletions,
        quirks = Quirks.compatibleOpenAi.copy(streamUsage = false)
      )
      streamed(notAsking, ask("hi")): Unit
      assertEquals(at.lastRequest.body("stream_options"), None)
  }

  standIn.test("responses: nothing is stored on the vendor's side") { at =>
    at.answer(ModelResponse("ok"))
    complete(provider(at, Shape.Responses), ask("hi"))
    assertEquals(at.lastRequest.body("store").flatMap(_.asBoolean), Some(false))
    assertEquals(at.lastRequest.body("previous_response_id"), None)
    assertEquals(at.lastRequest.body("instructions").flatMap(_.asString), Some("You are helpful."))
  }

  // ── Tool call ids ──────────────────────────────────────────────────────────

  standIn.test(
    "a tool call's id is rewritten for a model provider that constrains it and the session keeps the model's"
  ) { at =>
    at.answer(ModelResponse("Sunny."))
    val mistral = Presets.named("mistral").get.quirks
    val original = request(
      ChatMessage.User.text("weather?"),
      ChatMessage.Assistant("", Vector(call.copy(id = "toolu_01ABC"))),
      ChatMessage.ToolResults(Vector(ToolResult("toolu_01ABC", "lookup", "sunny")))
    )
    complete(provider(at, Shape.ChatCompletions, quirks = mistral), original)
    val messages = at.lastRequest.body("messages").flatMap(_.asArray).getOrElse(Vector.empty)
    val sentCall = messages
      .flatMap(_("tool_calls").flatMap(_.asArray).getOrElse(Vector.empty))
      .flatMap(_("id").flatMap(_.asString))
    val sentResult = messages.flatMap(_("tool_call_id").flatMap(_.asString))
    assertEquals(sentCall.size, 1)
    assert(sentCall.head.matches("[a-zA-Z0-9]{9}"), sentCall.head)
    assertEquals(sentResult, sentCall, "the result keeps its call's id")
    assertEquals(sentCall.head, ToolCallIds.under(mistral.toolCallIds.get)("toolu_01ABC"))
    assert(
      original.messages.exists {
        case ChatMessage.Assistant(_, calls, _) => calls.exists(_.id == "toolu_01ABC")
        case _                                  => false
      },
      "what the session holds is unchanged"
    )
  }

  standIn.test("ids the endpoint returns are kept as given, whatever the rule") { at =>
    at.answer(ModelResponse("", Vector(call.copy(id = "abcdefghi")), StopReason.ToolUse))
    val mistral = Presets.named("mistral").get.quirks
    assertEquals(
      complete(provider(at, Shape.ChatCompletions, quirks = mistral), ask("hi")).toolCalls.head.id,
      "abcdefghi"
    )
  }

  // ── Provider state ─────────────────────────────────────────────────────────

  standIn.test(
    "responses: a tool call made while the model reasons is sent back with the provider state the model asked to keep"
  ) { at =>
    at.requireReasoningReplay()
    at.answer(ModelResponse("", Vector(call), StopReason.ToolUse))
    at.answer(ModelResponse("Sunny."))
    at.answer(ModelResponse("unused"))
    val p     = provider(at, Shape.Responses, quirks = Quirks.openAi)
    val first = complete(p, ask("weather?"))
    val state = first.providerState.getOrElse(fail("a reasoning model's tool call carries state"))
    assertEquals(state.provider, "local")
    val back = request(
      ChatMessage.User.text("weather?"),
      ChatMessage.Assistant("", first.toolCalls, Some(state)),
      ChatMessage.ToolResults(Vector(ToolResult("call-1", "lookup", "sunny")))
    )
    assertEquals(complete(p, back).text, "Sunny.", "the reasoning item went back before its call")
    val input = at.lastRequest
      .body("input")
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(_("type").flatMap(_.asString))
    assertEquals(input, Vector("message", "reasoning", "function_call", "function_call_output"))

    val without = back.copy(messages = back.messages.map {
      case a: ChatMessage.Assistant => a.copy(providerState = None)
      case other                    => other
    })
    val refused = failure(p, without)
    assert(
      refused.getMessage.contains("400") && refused.getMessage.contains("reasoning"),
      refused.getMessage
    )
  }

  standIn.test("responses: another provider's state is not this one's to replay") { at =>
    at.answer(ModelResponse("ok"))
    val foreign = ProviderState("anthropic", """[{"type":"reasoning","id":"rs_x"}]""")
    complete(
      provider(at, Shape.Responses),
      request(
        ChatMessage.User.text("hi"),
        ChatMessage.Assistant("Hello", Vector.empty, Some(foreign)),
        ChatMessage.User.text("again")
      )
    )
    val sent = at.lastRequest.body.render
    assert(!sent.contains("rs_x"), sent)
    assert(sent.contains("\"output_text\""), sent)
  }

  standIn.test("responses: an answer that did not reason carries no state") { at =>
    at.answer(ModelResponse("", Vector(call), StopReason.ToolUse))
    assertEquals(complete(provider(at, Shape.Responses), ask("hi")).providerState, None)
  }

  // ── Without a key ──────────────────────────────────────────────────────────

  test(
    "built from an environment without its key, the provider exists and every call fails naming the variable"
  ) {
    val p =
      OpenAiProvider.fromEnv(Map.empty, OpenAiProvider.DefaultModel, OpenAiProvider.Defaults())
    assertEquals(p.name, "openai")
    val f = intercept[ModelCallFailed](complete(p, ask("hi")))
    assertEquals(f.getMessage, "openai: OPENAI_API_KEY is not set")
    val streamFailure = intercept[ModelCallFailed](streamed(p, ask("hi")))
    assertEquals(streamFailure.provider, "openai")
  }

  test("a reserved or malformed name is refused when the provider is built") {
    Vector("default", "scripted", "Fast", "local-fast").foreach { name =>
      intercept[IllegalArgumentException](OpenAiProvider.at(name, "http://127.0.0.1:1/v1", "m"))
    }
  }
