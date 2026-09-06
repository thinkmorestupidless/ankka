package nakka.agent

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * Exercises the real Anthropic API.
 *
 * Skipped unless `ANTHROPIC_API_KEY` is set, so an ordinary `sbt test` stays free,
 * offline and deterministic. Run it deliberately to check the translation layer against
 * the live API — a scripted model can prove nakka's logic but not that the request shape
 * is one Anthropic accepts.
 */
class AnthropicProviderSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private val apiKey = Option(System.getenv("ANTHROPIC_API_KEY")).filter(_.nonEmpty)

  private lazy val provider = AnthropicProvider.fromEnv()

  /** Marks a test as skipped, rather than failed, when there is no key to use. */
  private def requireApiKey(): Unit =
    assume(apiKey.isDefined, "ANTHROPIC_API_KEY is not set; skipping the live API test")

  private def complete(request: ModelRequest): ModelResponse =
    Await.result(provider.complete(request), 2.minutes)

  test("a plain question gets an answer and reports token usage") {
    requireApiKey()
    val response = complete(
      ModelRequest(
        settings = ModelSettings(AnthropicProvider.DefaultModel, maxTokens = Some(256)),
        systemMessage = Some("Answer in exactly one word."),
        messages = Vector(ChatMessage.User.text("What is the capital of France?"))
      )
    )
    assert(response.text.toLowerCase.contains("paris"), response.text)
    assertEquals(response.stopReason, StopReason.EndTurn)
    assert(response.usage.inputTokens > 0, response.usage.toString)
    assert(response.usage.outputTokens > 0, response.usage.toString)
  }

  test("a declared tool is called with well-formed arguments") {
    requireApiKey()
    val weather = FunctionTool
      .named("get_weather")
      .describedAs("Returns the current weather for a city.")
      .param[String]("location", "The city name.")
      .handle(location => s"$location: 18C")

    val response = complete(
      ModelRequest(
        settings = ModelSettings(AnthropicProvider.DefaultModel, maxTokens = Some(1024)),
        systemMessage = Some("Use the tools available to you."),
        messages = Vector(ChatMessage.User.text("What is the weather in Berlin?")),
        tools = Vector(weather.spec)
      )
    )

    assertEquals(response.stopReason, StopReason.ToolUse, response.text)
    val call = response.toolCalls.headOption.getOrElse(fail("no tool call"))
    assertEquals(call.name, "get_weather")
    // The arguments must satisfy the schema nakka generated.
    assertEquals(weather.invoke(call.arguments), Right("Berlin: 18C"))
  }

  test("a tool result round-trips into a final answer") {
    requireApiKey()
    val weather = FunctionTool
      .named("get_weather")
      .describedAs("Returns the current weather for a city.")
      .param[String]("location", "The city name.")
      .handle(location => s"$location: 18C and sunny")

    val settings = ModelSettings(AnthropicProvider.DefaultModel, maxTokens = Some(1024))
    val first = complete(
      ModelRequest(
        settings,
        Some("Use the tools available to you, then answer briefly."),
        Vector(ChatMessage.User.text("What is the weather in Berlin?")),
        Vector(weather.spec)
      )
    )
    val call    = first.toolCalls.headOption.getOrElse(fail("no tool call"))
    val result  = weather.invoke(call.arguments).getOrElse(fail("tool failed"))

    val second = complete(
      ModelRequest(
        settings,
        Some("Use the tools available to you, then answer briefly."),
        Vector(
          ChatMessage.User.text("What is the weather in Berlin?"),
          ChatMessage.Assistant(first.text, first.toolCalls),
          ChatMessage.ToolResults(Vector(ToolResult(call.id, call.name, result)))
        ),
        Vector(weather.spec)
      )
    )
    assertEquals(second.stopReason, StopReason.EndTurn, second.text)
    assert(second.text.toLowerCase.contains("sunny") || second.text.contains("18"), second.text)
  }
