package com.thinkmorestupidless.ankka.agent.openai

import com.thinkmorestupidless.ankka.agent.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * Exercises OpenAI's real API.
 *
 * Skipped unless `OPENAI_API_KEY` is set, so an ordinary `sbt test` stays free, offline and
 * deterministic. The stand-in proves ankka's translation; only this proves the request is one
 * OpenAI accepts, and that the default model still exists.
 */
class OpenAiProviderLiveSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private val apiKey = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  private lazy val provider = OpenAiProvider.fromEnv()

  override def beforeEach(context: BeforeEach): Unit =
    assume(apiKey.isDefined, "OPENAI_API_KEY is not set; skipping the live API test")

  private def complete(request: ModelRequest): ModelResponse =
    Await.result(provider.complete(request), 2.minutes)

  private val weather = FunctionTool
    .named("get_weather")
    .describedAs("The current weather in a city.")
    .param[String]("city", "The city's name.")
    .handle(city => s"$city: 18C, sunny")

  private def ask(text: String, tools: Vector[ToolSpec] = Vector.empty) =
    ModelRequest(
      ModelSettings(OpenAiProvider.DefaultModel, maxTokens = Some(2048)),
      Some("Answer briefly. Use a tool when one would help."),
      Vector(ChatMessage.User.text(text)),
      tools
    )

  test("a plain question gets an answer and reports token usage") {
    val response = complete(ask("What is the capital of France? One word."))
    assert(response.text.toLowerCase.contains("paris"), response.text)
    assertEquals(response.stopReason, StopReason.EndTurn)
    assert(
      response.usage.inputTokens > 0 && response.usage.outputTokens > 0,
      response.usage.toString
    )
  }

  test(
    "a declared tool is called with well-formed arguments, and its result round-trips while reasoning"
  ) {
    val first = complete(ask("What's the weather in Berlin?", Vector(weather.spec)))
    assertEquals(first.stopReason, StopReason.ToolUse, first.toString)
    val call = first.toolCalls.head
    assertEquals(call.name, "get_weather")
    val result = weather.invoke(call.arguments).fold(e => fail(e), identity)
    val second = complete(
      ask("What's the weather in Berlin?", Vector(weather.spec)).copy(messages =
        Vector(
          ChatMessage.User.text("What's the weather in Berlin?"),
          ChatMessage.Assistant(first.text, first.toolCalls, first.providerState),
          ChatMessage.ToolResults(Vector(ToolResult(call.id, call.name, result)))
        )
      )
    )
    assertEquals(second.stopReason, StopReason.EndTurn)
    assert(second.text.contains("18") || second.text.toLowerCase.contains("sunny"), second.text)
  }

  test("a history holding another vendor's tool call id is accepted") {
    val response = complete(
      ask("And how warm was it?", Vector(weather.spec)).copy(messages =
        Vector(
          ChatMessage.User.text("What's the weather in Berlin?"),
          ChatMessage.Assistant(
            "",
            Vector(
              ToolCall(
                "toolu_01A09q90qw90lq917835lq9",
                "get_weather",
                Json.obj("city" -> Json.str("Berlin"))
              )
            )
          ),
          ChatMessage.ToolResults(
            Vector(ToolResult("toolu_01A09q90qw90lq917835lq9", "get_weather", "Berlin: 18C, sunny"))
          ),
          ChatMessage.User.text("How warm was it?")
        )
      )
    )
    assert(response.text.contains("18"), response.text)
  }
