package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.TokenUsage

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

/** The one place the platform asks a provider: resolution, the timeout, and verification. */
class JudgmentsSuite extends munit.FunSuite:

  import Questions.*

  /** Answers every yes/no with `probability`, or whatever `answer` says. */
  private final class Fixed(
      val name: String,
      answer: JudgmentRequest => Future[Judgment] = null
  ) extends JudgmentProvider:
    val calls     = AtomicInteger()
    val modelName = s"$name-model"
    def judge(request: JudgmentRequest): Future[Judgment] =
      calls.incrementAndGet(): Unit
      if answer != null then answer(request)
      else
        Future.successful(
          Judgment(
            modelName,
            request.questions.map(q => q.id -> Judgment.Stored.YesNo(0.5)).toMap,
            TokenUsage.zero
          )
        )

  private val state = JudgmentState.Text("a ticket")

  test("a request with no questions, a repeated id or no time is refused at construction") {
    intercept[IllegalArgumentException](JudgmentRequest(state, Vector.empty, 1.second))
    val twice = intercept[IllegalArgumentException](
      JudgmentRequest(state, Vector(refund, Question.yesNo("refund", "Again?")), 1.second)
    )
    assert(twice.getMessage.contains("question 'refund' is asked twice"), twice.getMessage)
    intercept[IllegalArgumentException](JudgmentRequest(state, Vector(refund), 0.seconds))
  }

  test("a request that cannot be made asks no provider") {
    val provider = Fixed("fixed")
    intercept[IllegalArgumentException](
      Judgments(Some(provider), 1.second).ask(None, state, Vector(refund, refund))
    )
    assertEquals(provider.calls.get, 0)
  }

  test("the default answers when none is named, and a named provider wins") {
    val default   = Fixed("default")
    val named     = Fixed("named")
    val judgments = Judgments(Some(default), 1.second)
    assertEquals(judgments.ask(None, state, Vector(refund)).model, "default-model")
    assertEquals(judgments.ask(Some(named), state, Vector(refund)).model, "named-model")
    assertEquals((default.calls.get, named.calls.get), (1, 1))
  }

  test("with no provider named and none configured, nothing is asked") {
    intercept[NoJudgmentProvider](Judgments.none.ask(None, state, Vector(refund)))
  }

  test("a provider that never answers is a failure that timed out, within the timeout") {
    val silent  = Fixed("silent", _ => Promise[Judgment]().future)
    val started = System.nanoTime()
    val failure = intercept[JudgmentFailed](
      Judgments(Some(silent), 200.millis).ask(None, state, Vector(refund))
    )
    assert(failure.timedOut)
    assertEquals(failure.provider, "silent")
    assert((System.nanoTime() - started) < 2.seconds.toNanos)
  }

  test("an answer that does not fit the request fails, naming the question") {
    def answering(stored: (String, Judgment.Stored)*) =
      Fixed("odd", _ => Future.successful(Judgment("m", stored.toMap, TokenUsage.zero)))

    val missing = intercept[JudgmentFailed](
      Judgments(Some(answering()), 1.second).ask(None, state, Vector(refund))
    )
    assert(missing.getMessage.contains("question 'refund' was asked and not answered"))

    val wrongKind = intercept[JudgmentFailed](
      Judgments(Some(answering("refund" -> Judgment.Stored.Score(0, Vector(1, 0), 1))), 1.second)
        .ask(None, state, Vector(refund))
    )
    assert(wrongKind.getMessage.contains("question 'refund' is a yes/no"))

    val notOffered = intercept[JudgmentFailed](
      Judgments(
        Some(answering("route" -> Judgment.Stored.Choice("legal", Map("legal" -> 1.0), 1))),
        1.second
      ).ask(None, state, Vector(route))
    )
    assert(notOffered.getMessage.contains("question 'route'"), notOffered.getMessage)
  }

  test("a provider's own failure passes through unchanged") {
    val own     = JudgmentFailed("own", "overloaded")
    val failing = Fixed("own", _ => Future.failed(own))
    val failure = intercept[JudgmentFailed](
      Judgments(Some(failing), 1.second).ask(None, state, Vector(refund))
    )
    assert(failure eq own)
  }

  test("any other exception from a provider becomes a failure naming it") {
    val broken = Fixed("broken", _ => Future.failed(IllegalStateException("socket closed")))
    val failure = intercept[JudgmentFailed](
      Judgments(Some(broken), 1.second).ask(None, state, Vector(refund))
    )
    assertEquals(failure.getMessage, "broken: socket closed")
  }
