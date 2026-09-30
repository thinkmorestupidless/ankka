package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.judgment.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, SessionId}

import scala.concurrent.duration.DurationInt

/**
 * Judgments end to end: an agent's handler asks, a scripted provider answers, the caller reads
 * typed answers — on a running service with real session memory, so "a judgment leaves the
 * conversation alone" is measured against the journal rather than assumed.
 */
class JudgmentAgentSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val model                 = TestModelProvider()
  private val judge                 = TestJudgmentProvider()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(TriageAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model).withJudgments(judge))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    judge.reset()
    TriageAgent.second.reset()

  private def agent(session: String) = testKit.componentClient.forAgent(SessionId(session))

  private def historyOf(session: String) =
    testKit.componentClient
      .forSessionMemory(SessionId(session))
      .call(SessionMemoryEntity.history)
      .invoke()

  private val ticket =
    Ticket("t-1", "I was charged twice for order A-104. Please refund one today.")

  private def scriptTriage(): Unit =
    judge.expect(
      Answers.choice(TriageAgent.route, Team.Billing, confidence = 0.9),
      Answers.score(TriageAgent.frustration, 1.5),
      Answers.yesNo(TriageAgent.refund, 0.97)
    ): Unit

  private def refusal(body: => Any): CommandError = intercept[CommandError](body)

  // ── US1 ──────────────────────────────────────────────────────────────────

  test("one request carries the state and every question") {
    scriptTriage()
    agent("s-one").call(TriageAgent.triage).invoke(ticket): Unit
    assertEquals(judge.callCount, 1)
    assertEquals(judge.lastRequest.state, JudgmentState.Text(ticket.text))
    assertEquals(judge.lastRequest.questions.map(_.id), Vector("route", "frustration", "refund"))
  }

  test("the caller reads each answer through its question, typed") {
    scriptTriage()
    val judgment = agent("s-typed").call(TriageAgent.triage).invoke(ticket)

    val team: ChoiceAnswer[Team] = judgment(TriageAgent.route)
    assertEquals(team.choice, Team.Billing)
    assertEquals(team.probabilities.keySet, Set(Team.Billing, Team.Technical, Team.Sales))
    assertEqualsDouble(team.confidence, 0.9, 1e-9)

    val frustration = judgment(TriageAgent.frustration)
    assertEquals(frustration.score, 1.5)
    assertEquals(frustration.probabilities.size, 4)

    assertEquals(judgment(TriageAgent.refund).probability, 0.97)
  }

  test("a judgment reads nothing from the session and writes nothing to it") {
    model.expectText("Sorry to hear that — let me look.")
    agent("s-quiet").call(TriageAgent.chat).invoke("My card was declined.")
    val before = historyOf("s-quiet").messages
    assertEquals(before.size, 2)

    scriptTriage()
    agent("s-quiet").call(TriageAgent.triage).invoke(ticket): Unit

    val state = judge.lastRequest.state
    assertEquals(state, JudgmentState.Text(ticket.text))
    assertEquals(historyOf("s-quiet").messages, before)
  }

  test("a handler replies with a value of its own, judged from a structured state") {
    judge.expect(
      Answers.choice(TriageAgent.route, Team.Billing, confidence = 0.8),
      Answers.yesNo(TriageAgent.urgent, 0.9)
    )
    val routing = agent("s-routing").call(TriageAgent.routing).invoke(ticket)
    assertEquals(routing, Routing(Some(Team.Billing), urgent = true))
    judge.lastRequest.state match
      case JudgmentState.Structured(json) =>
        assertEquals(json("id").flatMap(_.asString), Some("t-1"))
        assertEquals(json("text").flatMap(_.asString), Some(ticket.text))
      case other => fail(s"expected a structured state, got $other")
  }

  test("a provider the handler names answers instead of the service's") {
    TriageAgent.second.expect(
      Answers.choice(TriageAgent.route, Team.Sales),
      Answers.score(TriageAgent.frustration, 0),
      Answers.yesNo(TriageAgent.refund, 0.1)
    )
    val judgment = agent("s-named").call(TriageAgent.triageWith).invoke(ticket)
    assertEquals(judgment.model, "second-judge")
    assertEquals((TriageAgent.second.callCount, judge.callCount), (1, 0))
  }

  test("a provider's failure reaches the caller, and the session is untouched") {
    judge.failNext("down")
    val down = refusal(agent("s-fail").call(TriageAgent.triage).invoke(ticket))
    assertEquals(down.code, ErrorCode.Unavailable)
    assert(down.getMessage.contains("test: down"), down.getMessage)

    judge.failNext("slow", timedOut = true)
    assertEquals(
      refusal(agent("s-fail").call(TriageAgent.triage).invoke(ticket)).code,
      ErrorCode.Timeout
    )
    assert(historyOf("s-fail").messages.isEmpty)
  }

  test("reading a question the handler did not ask fails, naming it") {
    judge.expect(Answers.yesNo(TriageAgent.refund, 0.2))
    val failure = refusal(agent("s-bad").call(TriageAgent.badReply).invoke(ticket))
    assert(failure.getMessage.contains("question 'urgent'"), failure.getMessage)
  }

  test("a question with no scripted answer fails the call, naming the question") {
    val failure = refusal(agent("s-empty").call(TriageAgent.triage).invoke(ticket))
    assertEquals(failure.code, ErrorCode.Internal)
    assert(failure.getMessage.contains("'route'"), failure.getMessage)
  }

  test("a question asked twice is refused before any provider is asked") {
    val failure = refusal(agent("s-twice").call(TriageAgent.invalid).invoke(ticket))
    assert(failure.getMessage.contains("question 'route' is asked twice"), failure.getMessage)
    assertEquals(judge.callCount, 0)
  }
