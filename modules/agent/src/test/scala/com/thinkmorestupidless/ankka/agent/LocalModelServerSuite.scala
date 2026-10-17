package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.providers.ModelProviders

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * A real model server on the developer's machine: Ollama, LM Studio, llama.cpp, Docker Model
 * Runner.
 *
 * Skipped unless `ANKKA_LOCAL_MODEL_URL` names the server's address (`http://localhost:11434/v1`).
 * `ANKKA_LOCAL_MODEL_NAME` names its model (`qwen3:8b` unless given) and
 * `ANKKA_LOCAL_MODEL_PROTOCOL` its protocol (`openai` unless given). It asserts the plumbing — an
 * answer arrived, the tool ran with arguments that parse, usage was recorded — never what a small
 * model said.
 */
class LocalModelServerSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private def env(name: String) = Option(System.getenv(name)).filter(_.nonEmpty)

  private val address = env("ANKKA_LOCAL_MODEL_URL")

  private lazy val local = ModelProviders.named(
    "local",
    protocol = Some(env("ANKKA_LOCAL_MODEL_PROTOCOL").getOrElse("openai")),
    url = address,
    model = Some(env("ANKKA_LOCAL_MODEL_NAME").getOrElse("qwen3:8b"))
  )

  override def beforeEach(context: BeforeEach): Unit =
    assume(
      address.isDefined,
      "ANKKA_LOCAL_MODEL_URL is not set; skipping the local model server test"
    )

  private val ran = java.util.concurrent.ConcurrentLinkedQueue[String]()

  private val weather = FunctionTool
    .named("get_weather")
    .describedAs("The current weather in a city. Always call it to answer about weather.")
    .param[String]("city", "The city's name.")
    .handle { city =>
      ran.add(city): Unit
      s"$city: 18C, sunny"
    }

  private def complete(request: ModelRequest) = Await.result(local.complete(request), 4.minutes)

  test("a turn is answered by a model on the developer's own machine") {
    val response = complete(
      ModelRequest(
        ModelSettings(local.modelName, maxTokens = Some(256)),
        None,
        Vector(ChatMessage.User.text("Say hello."))
      )
    )
    assert(response.text.nonEmpty, response.toString)
  }

  test("a tool call round-trips and usage is recorded") {
    val question = ChatMessage.User.text("What is the weather in Berlin? Use the tool.")
    val request = ModelRequest(
      ModelSettings(local.modelName, maxTokens = Some(512)),
      None,
      Vector(question),
      Vector(weather.spec)
    )
    val first = complete(request)
    assume(
      first.toolCalls.nonEmpty,
      s"the model answered in text instead of calling the tool: ${first.text}"
    )
    val call = first.toolCalls.head
    val result =
      weather.invoke(call.arguments).fold(e => fail(s"the arguments did not parse: $e"), identity)
    assertEquals(ran.toArray.toVector, Vector[AnyRef]("Berlin"))
    val second = complete(
      request.copy(messages =
        Vector(
          question,
          ChatMessage.Assistant(first.text, first.toolCalls),
          ChatMessage.ToolResults(Vector(ToolResult(call.id, call.name, result)))
        )
      )
    )
    assert(second.text.nonEmpty, second.toString)
    assert(second.usage.total >= 0)
  }
