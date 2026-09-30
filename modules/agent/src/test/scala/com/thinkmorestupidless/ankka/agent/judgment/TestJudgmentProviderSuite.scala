package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.TokenUsage

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** The scripted provider: what it answers, when it refuses to, and what it records. */
class TestJudgmentProviderSuite extends munit.FunSuite:

  import Questions.*

  private val judge = TestJudgmentProvider()

  override def beforeEach(context: BeforeEach): Unit = judge.reset()

  private def ask(questions: Question[?]*): Judgment =
    Await.result(
      judge.judge(JudgmentRequest(JudgmentState.Text("a ticket"), questions.toVector, 1.second)),
      1.second
    )

  private def scriptFailure(questions: Question[?]*): String =
    intercept[JudgmentScriptFailed](ask(questions*)).getMessage

  test("a queued judgment answers a request and is consumed") {
    judge.expect(Answers.choice(route, Team.Billing), Answers.yesNo(refund, 0.9))
    val judgment = ask(route, refund)
    assertEquals(judgment(route).choice, Team.Billing)
    assertEquals(judgment(refund).probability, 0.9)
    assertEquals(judgment.model, "test-judge")
    assertEquals(judge.callCount, 1)
    assert(scriptFailure(route, refund).contains("run out"))
  }

  test("queued judgments are taken in order, and every request is recorded") {
    judge
      .expect(Answers.yesNo(refund, 0.1))
      .expect(Answers.yesNo(refund, 0.2))
    assertEquals(ask(refund)(refund).probability, 0.1)
    assertEquals(ask(refund)(refund).probability, 0.2)
    assertEquals(
      judge.requests.map(_.questions.map(_.id)),
      Vector(Vector("refund"), Vector("refund"))
    )
    assertEquals(judge.lastRequest.state, JudgmentState.Text("a ticket"))
  }

  test("an empty queue names exactly the questions it cannot answer") {
    judge.always(Answers.yesNo(refund, 0.1))
    val message = scriptFailure(refund, route, urgent)
    assert(message.contains("'route', 'urgent'"), message)
    assert(!message.contains("'refund'"), message)
  }

  test("a queued judgment missing a question asked names it") {
    judge.expect(Answers.yesNo(refund, 0.1))
    val message = scriptFailure(refund, urgent)
    assert(message.contains("no answer for 'urgent'"), message)
  }

  test("a queued answer for a question the request did not ask names it") {
    judge.expect(Answers.yesNo(refund, 0.1), Answers.yesNo(urgent, 0.1))
    val message = scriptFailure(refund)
    assert(message.contains("answers 'urgent', which the request did not ask"), message)
  }

  test("an answer that does not fit its question is refused as it is scripted") {
    def refused(body: => Any): String = intercept[IllegalArgumentException](body).getMessage
    val notOffered = Question.choiceByKey("topic", "What?")("a" -> "A", "b" -> "B")
    assert(refused(Answers.choice(notOffered, "c")).contains("question 'topic'"))
    assert(refused(Answers.score(frustration, 3.5)).contains("off the scale 0 to 3"))
    assert(refused(Answers.yesNo(refund, 1.2)).contains("question 'refund'"))
    assert(
      refused(Answers.choiceWith(route, Map(Team.Billing -> 0.5, Team.Sales -> 0.5)))
        .contains("no probability is given for 'technical'")
    )
    assert(refused(Answers.scoreWith(frustration, Vector(0.5, 0.5))).contains("2 probabilities"))
    assert(refused(Answers.choice(route, Team.Billing, confidence = 2)).contains("confidence"))
  }

  test("a standing answer answers every request without consuming the queue") {
    judge.always(Answers.yesNo(urgent, 0.3)).expect(Answers.yesNo(refund, 0.7))
    (1 to 3).foreach(_ => assertEquals(ask(urgent)(urgent).probability, 0.3))
    val mixed = ask(urgent, refund)
    assertEquals((mixed(urgent).probability, mixed(refund).probability), (0.3, 0.7))
  }

  test("an answer scripted by its value carries consistent probabilities and confidence") {
    judge.always(Answers.choice(route, Team.Billing))
    val certain = ask(route)(route)
    assertEquals(certain.probabilities(Team.Billing), 1.0)
    assertEquals(certain.confidence, 1.0)

    judge.always(Answers.choice(route, Team.Billing, confidence = 0.7))
    val unsure = ask(route)(route)
    assertEqualsDouble(unsure.probabilities(Team.Billing), 0.8, 1e-9)
    assertEqualsDouble(unsure.probabilities(Team.Sales), 0.1, 1e-9)
    assertEqualsDouble(unsure.confidence, 0.7, 1e-9)

    judge.always(Answers.score(frustration, 1.5))
    val between = ask(frustration)(frustration)
    assertEquals(between.score, 1.5)
    assertEquals(between.probabilities, Vector(0.0, 0.5, 0.5, 0.0))
  }

  test("an answer scripted by its probabilities reads back the largest, or the weighted mean") {
    judge.always(
      Answers.choiceWith(route, Map(Team.Billing -> 0.2, Team.Technical -> 0.7, Team.Sales -> 0.1)),
      Answers.scoreWith(frustration, Vector(0.0, 0.5, 0.5, 0.0))
    )
    val judgment = ask(route, frustration)
    assertEquals(judgment(route).choice, Team.Technical)
    assertEqualsDouble(judgment(frustration).score, 1.5, 1e-9)
  }

  test("every scripted judgment passes the platform's verification") {
    judge.expect(
      Answers.choice(route, Team.Sales, confidence = 0.4),
      Answers.score(frustration, 2.25),
      Answers.yesNo(urgent, 0.0)
    )
    val request =
      JudgmentRequest(JudgmentState.Text("t"), Vector(route, frustration, urgent), 1.second)
    val judgment = Await.result(judge.judge(request), 1.second)
    assertEquals(Judgment.verify(request, judgment), Right(()))
  }

  test("failNext fails one judgment each, in order, before any answer is looked up") {
    judge.always(Answers.yesNo(refund, 0.1)).failNext("down").failNext("slow", timedOut = true)
    val first = intercept[JudgmentFailed](ask(refund))
    assertEquals((first.provider, first.message, first.timedOut), ("test", "down", false))
    assert(intercept[JudgmentFailed](ask(refund)).timedOut)
    assertEquals(ask(refund)(refund).probability, 0.1)
  }

  test("every judgment reports the scripted usage") {
    judge.reporting(TokenUsage(inputTokens = 100)).always(Answers.yesNo(refund, 0.1))
    assertEquals(ask(refund).usage, TokenUsage(inputTokens = 100))
  }

  test("reset leaves nothing behind, the reported usage included") {
    judge
      .reporting(TokenUsage(inputTokens = 100))
      .always(Answers.yesNo(refund, 0.1))
      .expect(Answers.yesNo(urgent, 0.1))
      .failNext("down")
    judge.reset()
    assertEquals(judge.callCount, 0)
    assert(scriptFailure(refund).contains("'refund'"))
    judge.always(Answers.yesNo(refund, 0.1))
    assertEquals(ask(refund).usage, TokenUsage.zero)
  }
