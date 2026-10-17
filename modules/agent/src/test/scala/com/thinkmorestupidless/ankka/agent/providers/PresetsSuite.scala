package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * Every preset the platform names, configured from its name against a stand-in at the preset's
 * address moved to loopback: the request arrives in the preset's protocol and shape, for its
 * default model, with the quirks it says the endpoint has.
 */
class PresetsSuite extends munit.FunSuite:

  override val munitTimeout = 1.minute

  /** The presets FR-012 requires, which the living feature's outline lists. */
  private val required = Set(
    "openai",
    "anthropic",
    "ollama",
    "lmstudio",
    "llamacpp",
    "docker-model-runner",
    "vllm",
    "groq",
    "mistral",
    "deepseek",
    "xai",
    "gemini",
    "openrouter",
    "huggingface",
    "bedrock",
    "azure"
  )

  private val standIn = FunFixture[ModelStandIn](_ => ModelStandIn.start(), _.close())

  test("the presets are exactly those the platform names") {
    assertEquals(Presets.all.keySet, required)
    Presets.all.foreach((name, preset) => assertEquals(preset.name, name))
  }

  private def ask = ModelRequest(
    ModelSettings("ignored"),
    Some("Be brief."),
    Vector(
      ChatMessage.User.text("weather?"),
      ChatMessage.Assistant("", Vector(ToolCall("toolu_01ABC", "lookup", Json.obj()))),
      ChatMessage.ToolResults(Vector(ToolResult("toolu_01ABC", "lookup", "sunny")))
    )
  )

  for name <- required.toVector.sorted do
    standIn.test(s"every preset the platform names is configured from its name: $name") { at =>
      val preset = Presets.named(name).get
      val url =
        if preset.protocol == ModelProtocol.Anthropic then at.address else s"${at.address}/v1"
      val config = ProviderConfig(
        "p",
        preset = Some(name),
        url = Some(url),
        key = Option.when(preset.keyRequired)("k"),
        model = Option.when(preset.defaultModel.isEmpty)("given-model")
      )
      val resolved = config.resolve.fold(p => fail(p.describe), identity)
      assertEquals(resolved.protocol, preset.protocol)
      assertEquals(resolved.shape, preset.shape)
      assertEquals(resolved.quirks, preset.quirks)
      at.answer(ModelResponse("ok"))
      val provider = ModelProviders.configure(resolved)
      val request  = ask.copy(settings = ModelSettings(provider.modelName, temperature = Some(0.3)))
      assertEquals(Await.result(provider.complete(request), 30.seconds).text, "ok")
      val sent = at.lastRequest
      val expectedPath = (preset.protocol, preset.shape) match
        case (ModelProtocol.Anthropic, _)            => "/v1/messages"
        case (ModelProtocol.OpenAi, Shape.Responses) => "/v1/responses"
        case (ModelProtocol.OpenAi, _)               => "/v1/chat/completions"
      assertEquals(sent.path, expectedPath)
      assertEquals(
        sent.body("model").flatMap(_.asString),
        Some(preset.defaultModel.getOrElse("given-model"))
      )
      assertEquals(
        sent.body("temperature").isDefined,
        preset.quirks.acceptsTemperature,
        sent.body.render
      )
      val rendered = sent.body.render
      preset.quirks.toolCallIds match
        case Some(rule) =>
          assert(!rendered.contains("toolu_01ABC"), rendered)
          assert(rendered.contains(ToolCallIds.under(rule)("toolu_01ABC")), rendered)
        case None => assert(rendered.contains("toolu_01ABC"), rendered)
    }

  for name <- Vector("bedrock", "azure") do
    test(s"$name has no address of its own, so one must be given") {
      assertEquals(
        ProviderConfig("p", preset = Some(name), key = Some("k"), model = Some("m")).resolve.left
          .map(_.describe),
        Left("model provider 'p': an address is missing")
      )
    }

  for (name, preset) <- Presets.all.toVector.sortBy(_._1) if preset.defaultModel.isEmpty do
    test(s"$name names no model, so one must be given") {
      val config = ProviderConfig(
        "p",
        preset = Some(name),
        key = Some("k"),
        url = Some("http://127.0.0.1:1/v1")
      )
      assertEquals(
        config.resolve.left.map(_.describe),
        Left("model provider 'p': a model is missing")
      )
    }
