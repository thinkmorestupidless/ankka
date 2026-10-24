package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import com.thinkmorestupidless.ankka.agent.providers.{ModelProviders, Quirks, Shape}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, SessionId}

import scala.concurrent.duration.DurationInt

/**
 * More than one model provider in one Scala service: each handler is answered by the provider it
 * names, a session is continued across providers, and what each cost is kept under its name.
 *
 * The providers are scripted and named as a service would name them (`anthropic`, `openai`), or a
 * real provider against a stand-in on loopback where the wire matters.
 */
class ModelProvidersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val anthropic             = TestModelProvider("claude-opus-5", name = "anthropic")
  private val openai                = TestModelProvider("gpt-6.1-sol", name = "openai")

  override def beforeAll(): Unit =
    ProvidersAgent.second = openai
    testKit = AnkkaTestKit.start(
      Seq(ProvidersAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(anthropic))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    anthropic.reset()
    openai.reset()
    ProvidersAgent.second = openai
    ProvidersAgent.lookups.clear()

  private def agent(session: String) = testKit.componentClient.forAgent(SessionId(session))

  private def history(session: String) =
    testKit.componentClient
      .forSessionMemory(SessionId(session))
      .call(SessionMemoryEntity.history)
      .invoke()

  /** Runs `body` with `provider` as the one `triage` names. */
  private def namingSecond[A](provider: ModelProvider)(body: => A): A =
    ProvidersAgent.second = provider
    try body
    finally ProvidersAgent.second = openai

  // ── User story 1 ─────────────────────────────────────────────────────────

  test("a Scala service runs one agent on OpenAI and another on Anthropic") {
    openai.expectText("Route to billing.")
    anthropic.expectText("Dear customer,")
    assertEquals(
      agent("s-1").call(ProvidersAgent.triage).invoke("my bill is wrong"),
      "Route to billing."
    )
    assertEquals(
      agent("s-2").call(ProvidersAgent.drafting).invoke("reply to them"),
      "Dear customer,"
    )
    assertEquals(openai.callCount, 1)
    assertEquals(anthropic.callCount, 1)
  }

  test("a handler naming a model provider is answered by that model provider and no other") {
    openai.expectText("Route to billing.")
    agent("s-3").call(ProvidersAgent.triage).invoke("my bill is wrong"): Unit
    assertEquals(anthropic.callCount, 0, "the default was not asked")
    val answer = history("s-3").messages.collect { case m: SessionMessage.AiMessage => m }
    assertEquals(
      answer.map(m => (m.provider, m.model)),
      Vector((Some("openai"), Some("gpt-6.1-sol")))
    )
  }

  test(
    "a service with the key of one model provider alone refuses only the agent that names the other"
  ) {
    val keyless =
      OpenAiProvider.fromEnv(Map.empty, OpenAiProvider.DefaultModel, OpenAiProvider.Defaults())
    namingSecond(keyless) {
      anthropic.expectText("Dear customer,")
      assertEquals(agent("s-4").call(ProvidersAgent.drafting).invoke("reply"), "Dear customer,")
      val refused =
        intercept[CommandError](agent("s-5").call(ProvidersAgent.triage).invoke("route"))
      assertEquals(refused.code, ErrorCode.Unavailable)
      assert(
        refused.getMessage.contains("openai") && refused.getMessage.contains("OPENAI_API_KEY"),
        refused.getMessage
      )
    }
  }

  test("an OpenAI model's refusal is the caller's refusal, not an answer") {
    openai.expectRefusal("outside policy")
    val refused =
      intercept[CommandError](agent("s-6").call(ProvidersAgent.triage).invoke("something"))
    assertEquals(refused.code, ErrorCode.Forbidden)
    assert(refused.getMessage.contains("outside policy"), refused.getMessage)
    assertEquals(
      history("s-6").messages.collect { case m: SessionMessage.AiMessage => m },
      Vector.empty
    )
  }

  test("a model provider configured with a reserved name is refused at the service's start") {
    val refused = intercept[Throwable] {
      AnkkaTestKit
        .start(
          Seq(ProvidersAgent.descriptor) ++ AgentRuntime.descriptors,
          Seq(AgentRuntime.withDefaultModel(TestModelProvider(name = "default")))
        )
        .stop()
    }
    val messages =
      Iterator.iterate(refused)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString(" / ")
    assert(messages.contains("'default' is reserved"), messages)
  }

  // ── User story 3 ─────────────────────────────────────────────────────────

  test("a Scala service builds a model provider from the same four values") {
    val standIn = ModelStandIn.start()
    try
      standIn.answer(ModelResponse("From the laptop.", usage = TokenUsage(4, 2)))
      val local = ModelProviders.named(
        "local",
        protocol = Some("openai"),
        url = Some(s"${standIn.address}/v1"),
        model = Some("qwen3:8b")
      )
      assertEquals(local.name, "local")
      namingSecond(local) {
        assertEquals(agent("s-7").call(ProvidersAgent.triage).invoke("hello"), "From the laptop.")
      }
      assertEquals(standIn.lastRequest.body("model").flatMap(_.asString), Some("qwen3:8b"))
      assertEquals(history("s-7").usageByProvider, Map("local" -> TokenUsage(4, 2)))
    finally standIn.close()
  }

  // ── User story 4 ─────────────────────────────────────────────────────────

  private val berlin = Json.obj("city" -> Json.str("Berlin"))

  /** A turn on Anthropic in which the model looked something up. */
  private def beganOnAnthropic(session: String): Unit =
    anthropic
      .expect(
        ModelResponse(
          "",
          Vector(ToolCall("toolu_01ABC", "lookup", berlin)),
          StopReason.ToolUse,
          TokenUsage(10, 5)
        )
      )
      .expect(ModelResponse("It is sunny in Berlin.", usage = TokenUsage(12, 6)))
    agent(session).call(ProvidersAgent.drafting).invoke("weather in Berlin?"): Unit

  test("an agent on one model provider continues a session another model provider began") {
    beganOnAnthropic("s-8")
    openai.expect(ModelResponse("Still sunny.", usage = TokenUsage(7, 3)))
    assertEquals(agent("s-8").call(ProvidersAgent.triage).invoke("and now?"), "Still sunny.")
    val sent  = openai.lastRequest.messages
    val users = sent.collect { case ChatMessage.User(c) =>
      c.collect { case MessageContent.Text(t) => t }.mkString
    }
    assertEquals(users, Vector("weather in Berlin?", "and now?"), "the conversation is sent whole")
    assert(sent.exists {
      case ChatMessage.Assistant(t, _, _) => t == "It is sunny in Berlin."; case _ => false
    })
  }

  test("a tool call one model provider made is sent to the other with its result") {
    beganOnAnthropic("s-9")
    openai.expectText("Yes.")
    agent("s-9").call(ProvidersAgent.triage).invoke("sure?"): Unit
    val sent  = openai.lastRequest.messages
    val calls = sent.collect { case ChatMessage.Assistant(_, cs, _) if cs.nonEmpty => cs }.flatten
    assertEquals(
      calls,
      Vector(ToolCall("toolu_01ABC", "lookup", berlin)),
      "with the arguments the model gave"
    )
    val callAt = sent.indexWhere {
      case ChatMessage.Assistant(_, cs, _) => cs.nonEmpty; case _ => false
    }
    val resultAt = sent.indexWhere { case _: ChatMessage.ToolResults => true; case _ => false }
    assert(resultAt == callAt + 1, sent.toString)
    assertEquals(
      sent.collect { case ChatMessage.ToolResults(r) => r }.flatten.map(r => (r.callId, r.content)),
      Vector("toolu_01ABC" -> "Berlin: sunny")
    )
  }

  test("a session's model usage is both model providers' added together") {
    beganOnAnthropic("s-10")
    openai.expect(ModelResponse("Still sunny.", usage = TokenUsage(7, 3)))
    agent("s-10").call(ProvidersAgent.triage).invoke("and now?"): Unit
    val h = history("s-10")
    assertEquals(h.usage, TokenUsage(29, 14))
    assertEquals(
      h.usageByProvider,
      Map("anthropic" -> TokenUsage(22, 11), "openai" -> TokenUsage(7, 3))
    )
    val answers = h.messages.collect { case m: SessionMessage.AiMessage =>
      (m.provider, m.model)
    }.distinct
    assertEquals(
      answers,
      Vector((Some("anthropic"), Some("claude-opus-5")), (Some("openai"), Some("gpt-6.1-sol")))
    )
  }

  // ── User story 6 ─────────────────────────────────────────────────────────

  private def reasoningOpenAi(standIn: ModelStandIn) =
    OpenAiProvider.at(
      "openai",
      s"${standIn.address}/v1",
      "gpt-6.1-sol",
      Some("k"),
      Shape.Responses,
      quirks = Quirks.openAi
    )

  test(
    "a tool call made while the model reasons is sent back with the provider state the model asked to keep"
  ) {
    val standIn = ModelStandIn.start().requireReasoningReplay()
    try
      standIn.answer(
        ModelResponse("", Vector(ToolCall("call_1", "lookup", berlin)), StopReason.ToolUse)
      )
      standIn.answer(ModelResponse("Sunny in Berlin."))
      namingSecond(reasoningOpenAi(standIn)) {
        assertEquals(
          agent("s-11").call(ProvidersAgent.triage).invoke("weather?"),
          "Sunny in Berlin."
        )
      }
      assertEquals(ProvidersAgent.lookups.toArray.toVector, Vector("Berlin"), "the tool ran")
      val kinds = standIn.lastRequest
        .body("input")
        .flatMap(_.asArray)
        .getOrElse(Vector.empty)
        .flatMap(_("type").flatMap(_.asString))
      assert(
        kinds.containsSlice(Vector("reasoning", "function_call", "function_call_output")),
        kinds.toString
      )
    finally standIn.close()
  }

  test("provider state is never recorded in the session") {
    val standIn = ModelStandIn.start().requireReasoningReplay()
    try
      standIn.answer(
        ModelResponse("", Vector(ToolCall("call_2", "lookup", berlin)), StopReason.ToolUse)
      )
      standIn.answer(ModelResponse("Sunny."))
      standIn.answer(ModelResponse("Still sunny."))
      namingSecond(reasoningOpenAi(standIn)) {
        agent("s-12").call(ProvidersAgent.triage).invoke("weather?"): Unit
        val recorded = String(
          SessionMemoryEntity.stateSerializer.toBytes(history("s-12")),
          java.nio.charset.StandardCharsets.UTF_8
        )
        assert(!recorded.contains("enc-") && !recorded.contains("\"reasoning\""), recorded)
        assertEquals(agent("s-12").call(ProvidersAgent.triage).invoke("and now?"), "Still sunny.")
      }
      val firstOfNextTurn =
        standIn.lastRequest
          .body("input")
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)
          .flatMap(_("type").flatMap(_.asString))
      assert(!firstOfNextTurn.contains("reasoning"), firstOfNextTurn.toString)
    finally standIn.close()
  }

  test("a turn interrupted for approval goes on without the provider state, which was never kept") {
    val standIn = ModelStandIn.start().reasons()
    try
      standIn.answer(
        ModelResponse(
          "",
          Vector(ToolCall("call_3", "issue_refund", Json.obj("order" -> Json.str("o-7")))),
          StopReason.ToolUse
        )
      )
      standIn.answer(ModelResponse("Refunded."))
      namingSecond(reasoningOpenAi(standIn)) {
        val waiting = agent("s-13").ask(ProvidersAgent.triage).invoke("refund o-7") match
          case AgentOutcome.AwaitingApproval(requests) => requests
          case other                                   => fail(s"expected to wait, got $other")
        val decided =
          agent("s-13").decide(ProvidersAgent.triage)(Decision.approved(waiting.head.id, "dana"))
        assertEquals(decided, AgentOutcome.Answered("Refunded."))
      }
      val resumed = standIn.lastRequest
        .body("input")
        .flatMap(_.asArray)
        .getOrElse(Vector.empty)
        .flatMap(_("type").flatMap(_.asString))
      assert(!resumed.contains("reasoning"), resumed.toString)
      assert(resumed.contains("function_call_output"), resumed.toString)
    finally standIn.close()
  }
