package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.providers.Quirks
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Anthropic's protocol at an address of the developer's own: a local server that speaks it. */
class AnthropicCompatibleSuite extends munit.FunSuite:

  override val munitTimeout = 1.minute

  private given system: ActorSystem =
    ActorSystem(
      "anthropic-compatible-suite",
      ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds): Unit

  private val standIn = FunFixture[ModelStandIn](_ => ModelStandIn.start(), _.close())

  private val lookup =
    ToolSpec("lookup", "Looks a thing up", Json.obj("type" -> Json.str("object")))
  private val call = ToolCall("toolu_1", "lookup", Json.obj("city" -> Json.str("Berlin")))

  private def ask(text: String) =
    ModelRequest(
      ModelSettings("qwen3:8b"),
      Some("Be brief."),
      Vector(ChatMessage.User.text(text)),
      Vector(lookup)
    )

  private def complete(p: ModelProvider, r: ModelRequest) = Await.result(p.complete(r), 30.seconds)

  standIn.test("a model provider at an address of the developer's own answers, with no key") { at =>
    at.answer(ModelResponse("Paris", usage = TokenUsage(9, 2, cacheReadTokens = 1)))
    val local    = AnthropicProvider.at("local", at.address, "qwen3:8b")
    val response = complete(local, ask("capital of France?"))
    assertEquals(response.text, "Paris")
    assertEquals(response.usage, TokenUsage(9, 2, cacheReadTokens = 1))
    assertEquals(at.lastRequest.path, "/v1/messages")
    assertEquals(at.lastRequest.header("x-api-key"), Some("none"))
    assertEquals(local.name, "local")
  }

  standIn.test(
    "an address ending in /v1 is the server's root, as the protocol's paths begin with it"
  ) { at =>
    at.answer(ModelResponse("ok"))
    complete(AnthropicProvider.at("local", s"${at.address}/v1", "qwen3:8b"), ask("hi"))
    assertEquals(at.lastRequest.path, "/v1/messages")
  }

  standIn.test("a tool call round-trips, and a stream ends with the call complete") { at =>
    at.answer(ModelResponse("", Vector(call), StopReason.ToolUse))
    at.answer(ModelResponse("", Vector(call), StopReason.ToolUse))
    val local = AnthropicProvider.at("local", at.address, "qwen3:8b")
    assertEquals(complete(local, ask("weather?")).toolCalls, Vector(call))
    val chunks = Await.result(local.stream(ask("weather?")).runWith(Sink.seq), 30.seconds)
    chunks.last match
      case ModelChunk.Completed(r) => assertEquals(r.toolCalls, Vector(call))
      case other                   => fail(s"the last chunk is $other")
  }

  standIn.test("Anthropic's own fields go only where the endpoint is known to take them") { at =>
    at.answer(ModelResponse("a")).answer(ModelResponse("b"))
    val defaults = AnthropicProvider.Defaults(effort = Some(Effort.High))
    complete(AnthropicProvider.at("local", at.address, "qwen3:8b", defaults = defaults), ask("hi"))
    assertEquals(at.lastRequest.body("thinking"), None, at.lastRequest.body.render)
    assertEquals(at.lastRequest.body("output_config"), None)
    complete(
      AnthropicProvider
        .at("claude", at.address, "claude-opus-5", Some("k"), defaults, Quirks.anthropic),
      ask("hi")
    )
    assert(at.lastRequest.body("thinking").isDefined, at.lastRequest.body.render)
    assert(at.lastRequest.body("output_config").isDefined)
    assertEquals(at.lastRequest.header("x-api-key"), Some("k"))
  }

  standIn.test("a failure is the provider's, under its name, and nothing is retried") { at =>
    at.failNext(
      529,
      """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
    )
    val f = intercept[ModelCallFailed](
      complete(AnthropicProvider.at("local", at.address, "m"), ask("hi"))
    )
    assertEquals(f.getMessage, "local: answered 529: Overloaded")
    assertEquals(at.requests.size, 1)
  }

  test("built from an environment without its key, every call fails naming the variable") {
    val p = AnthropicProvider.fromEnv(
      Map.empty,
      AnthropicProvider.DefaultModel,
      AnthropicProvider.Defaults()
    )
    assertEquals(p.name, "anthropic")
    val f = intercept[ModelCallFailed](complete(p, ask("hi")))
    assertEquals(f.getMessage, "anthropic: ANTHROPIC_API_KEY is not set")
  }
