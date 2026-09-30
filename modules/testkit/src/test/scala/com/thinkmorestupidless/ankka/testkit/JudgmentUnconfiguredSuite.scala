package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, SessionId}

import scala.concurrent.duration.DurationInt

/**
 * A service that asks for judgments without having configured anyone to answer them: it starts, and
 * the work that needs a provider says what to configure.
 */
class JudgmentUnconfiguredSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val model                 = TestModelProvider()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(TriageAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def agent(session: String) = testKit.componentClient.forAgent(SessionId(session))

  private val ticket = Ticket("t-1", "Where is my order?")

  test("a judged guardrail with no provider anywhere says what to configure") {
    val failure =
      intercept[CommandError](agent("s-guard").call(TriageAgent.guarded).invoke("Hello"))
    assertEquals(failure.code, ErrorCode.Internal)
    assert(failure.getMessage.contains("withJudgments"), failure.getMessage)
    assertEquals(model.callCount, 0)
  }

  test("a judgment with no provider anywhere says what to configure") {
    val failure = intercept[CommandError](agent("s-none").call(TriageAgent.triage).invoke(ticket))
    assertEquals(failure.code, ErrorCode.Internal)
    assert(failure.getMessage.contains("withJudgments"), failure.getMessage)
    assertEquals(model.callCount, 0)
  }
