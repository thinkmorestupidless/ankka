package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * A vendor's preset against the vendor's real endpoint.
 *
 * Skipped unless `ANKKA_MODEL_<PRESET>_KEY` is set (and, for a preset with no fixed address,
 * `ANKKA_MODEL_<PRESET>_URL`; for one with no default model, `ANKKA_MODEL_<PRESET>_MODEL`), so an
 * ordinary `sbt test` stays offline. The stand-in proves the translation; only this proves the
 * endpoint takes it, and that a preset's default model still exists.
 */
abstract class PresetLiveSuite(preset: String) extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private val upper = preset.toUpperCase.replace('-', '_')

  private def env(setting: String): Option[String] =
    Option(System.getenv(s"ANKKA_MODEL_${upper}_$setting")).filter(_.nonEmpty)

  private lazy val provider =
    ModelProviders.named(
      "live",
      preset = Some(preset),
      key = env("KEY"),
      url = env("URL"),
      model = env("MODEL")
    )

  override def beforeEach(context: BeforeEach): Unit =
    assume(
      env("KEY").isDefined,
      s"ANKKA_MODEL_${upper}_KEY is not set; skipping the live $preset test"
    )

  private def complete(request: ModelRequest): ModelResponse =
    Await.result(provider.complete(request), 2.minutes)

  private val weather = FunctionTool
    .named("get_weather")
    .describedAs("The current weather in a city.")
    .param[String]("city", "The city's name.")
    .handle(city => s"$city: 18C, sunny")

  private def ask(
      text: String,
      tools: Vector[ToolSpec] = Vector.empty,
      messages: Vector[ChatMessage] = Vector.empty
  ) =
    ModelRequest(
      ModelSettings(provider.modelName, maxTokens = Some(1024)),
      Some("Answer briefly. Use a tool when one would help."),
      if messages.isEmpty then Vector(ChatMessage.User.text(text)) else messages,
      tools
    )

  test(s"$preset: a plain question gets an answer") {
    val response = complete(ask("What is the capital of France? One word."))
    assert(response.text.toLowerCase.contains("paris"), response.text)
  }

  test(s"$preset: a tool call round-trips into an answer") {
    val first = complete(ask("What's the weather in Berlin?", Vector(weather.spec)))
    assertEquals(first.stopReason, StopReason.ToolUse, first.toString)
    val call   = first.toolCalls.head
    val result = weather.invoke(call.arguments).fold(e => fail(e), identity)
    val second = complete(
      ask(
        "",
        Vector(weather.spec),
        Vector(
          ChatMessage.User.text("What's the weather in Berlin?"),
          ChatMessage.Assistant(first.text, first.toolCalls, first.providerState),
          ChatMessage.ToolResults(Vector(ToolResult(call.id, call.name, result)))
        )
      )
    )
    assert(second.text.contains("18") || second.text.toLowerCase.contains("sunny"), second.text)
  }

class GroqLiveSuite        extends PresetLiveSuite("groq")
class MistralLiveSuite     extends PresetLiveSuite("mistral")
class DeepSeekLiveSuite    extends PresetLiveSuite("deepseek")
class XaiLiveSuite         extends PresetLiveSuite("xai")
class GeminiLiveSuite      extends PresetLiveSuite("gemini")
class OpenRouterLiveSuite  extends PresetLiveSuite("openrouter")
class HuggingFaceLiveSuite extends PresetLiveSuite("huggingface")
class BedrockLiveSuite     extends PresetLiveSuite("bedrock")
class AzureLiveSuite       extends PresetLiveSuite("azure")
