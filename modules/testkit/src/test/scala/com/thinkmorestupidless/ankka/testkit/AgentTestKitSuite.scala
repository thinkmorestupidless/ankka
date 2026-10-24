package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.ErrorCode

/**
 * The agent test kit runs a turn as the runtime does, with no runtime: the model is asked, its
 * tools run, guardrails refuse, a turn stops for a decision, and the session it writes is read
 * again.
 */
class AgentTestKitSuite extends munit.FunSuite with LogCapturing:

  private def json(text: String): Json = Json.parse(text).fold(e => fail(e), identity)

  test("a turn runs the tool the model asks for and answers what the model says next") {
    WeatherAgent.toolCalls.clear()
    val model = TestModelProvider()
      .expectToolCall("get_weather", json("""{"location":"Oslo"}"""))
      .expectText("Oslo: 18C and sunny")
    val kit    = AgentTestKit.of(WeatherAgent, model)
    val result = kit.call(WeatherAgent.ask)("How is Oslo?")
    assertEquals(result.reply, "Oslo: 18C and sunny")
    assertEquals(WeatherAgent.toolCalls.toArray.toList, List("get_weather(Oslo,-)"))
    assert(model.lastRequest.toString.contains("Oslo: 18C, sunny"), model.lastRequest.toString)
    assert(result.messages.nonEmpty, "the turn recorded nothing")
  }

  test("the next turn reads what the last one recorded in the session") {
    val model = TestModelProvider().expectText("first").expectText("second")
    val kit   = AgentTestKit.of(WeatherAgent, model)
    kit.call(WeatherAgent.ask)("Remember the word heron.").reply: Unit
    kit.call(WeatherAgent.ask)("What was the word?").reply: Unit
    assert(model.lastRequest.toString.contains("heron"), model.lastRequest.toString)
    assertEquals(kit.history.messages.size, 4)
  }

  test("a guardrail's refusal is the rejection the caller would get, and no model is asked") {
    val model  = TestModelProvider()
    val kit    = AgentTestKit.of(WeatherAgent, model)
    val result = kit.call(WeatherAgent.guarded)("x" * 80)
    assertEquals(result.error.code, ErrorCode.Forbidden)
    assertEquals(model.callCount, 0)
    assertEquals(kit.history.messages, Vector.empty)
  }

  test("a structured reply is read through the handler's own serializer") {
    val model = TestModelProvider()
      .expectText("""{"location":"Oslo","summary":"sunny","degreesCelsius":18}""")
    val kit = AgentTestKit.of(WeatherAgent, model)
    assertEquals(kit.call(WeatherAgent.askStructured)("Oslo?").reply, Forecast("Oslo", "sunny", 18))
  }

  test("a tool that needs approval stops the turn, and the tool has not run") {
    ApprovalAgent.runs.clear()
    val model = TestModelProvider()
      .expectToolCall("issue_refund", json("""{"order":"o-1","amount":5}"""))
    val kit    = AgentTestKit.of(ApprovalAgent, model)
    val result = kit.call(ApprovalAgent.ask)("Refund o-1")
    assertEquals(result.awaiting.map(_.tool), Vector("issue_refund"))
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)
    assertEquals(kit.history.awaiting.map(_.tool), Vector("issue_refund"))
  }

  test("a script that runs out fails the turn, naming it") {
    val kit    = AgentTestKit.of(WeatherAgent, TestModelProvider())
    val thrown = intercept[Throwable](kit.call(WeatherAgent.ask)("Anyone there?").reply)
    assert(thrown.getMessage.toLowerCase.contains("script"), thrown.getMessage)
  }
