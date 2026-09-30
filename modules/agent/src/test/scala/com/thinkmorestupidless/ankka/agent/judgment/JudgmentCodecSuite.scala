package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.core.Serializer

import scala.concurrent.duration.DurationInt

/**
 * A judgment's stored form, how it is read, and what the platform checks before accepting one.
 *
 * A judgment can be a reply and can be kept in an entity's or a workflow's state, so its encoded
 * form is pinned like a journal's: `judgment.json` changes only as a reviewed diff.
 */
class JudgmentCodecSuite extends munit.FunSuite:

  import Questions.*

  private val judgment = Judgment(
    "jev-1.13.0",
    Map(
      "route" -> Judgment.Stored.Choice(
        "billing",
        Map("billing" -> 0.88, "technical" -> 0.08, "sales" -> 0.04),
        0.82
      ),
      "frustration" -> Judgment.Stored.Score(1.43, Vector(0.0, 0.57, 0.43, 0.0), 0.35),
      "refund"      -> Judgment.Stored.YesNo(0.95)
    ),
    TokenUsage(inputTokens = 296, outputTokens = 20)
  )

  private val serializer = summon[Serializer[Judgment]]

  test("a judgment encodes to its pinned form and decodes back equal") {
    val encoded = String(serializer.toBytes(judgment), "UTF-8")
    assertEquals(serializer.manifest, "judgment")
    assertEquals(serializer.fromBytes(serializer.toBytes(judgment)), judgment)
    val path = FixtureFiles.repositoryRoot.resolve(
      "modules/agent/src/test/resources/judgment/judgment.json"
    )
    FixtureFiles.check(path, encoded + "\n").foreach(fail(_))
  }

  test("each answer reads through its question, with its type") {
    val team: ChoiceAnswer[Team] = judgment(route)
    assertEquals(team.choice, Team.Billing)
    assertEquals(
      team.probabilities,
      Map(Team.Billing -> 0.88, Team.Technical -> 0.08, Team.Sales -> 0.04)
    )
    assertEquals(team.confidence, 0.82)

    val score = judgment(frustration)
    assertEquals(score.score, 1.43)
    assertEquals(score.level, 1)
    assertEquals(score.probabilities.size, 4)

    assertEquals(judgment(refund).probability, 0.95)
    assert(judgment.contains(route))
    assert(!judgment.contains(urgent))
  }

  test("reading a question that was not asked fails, naming it") {
    val failure = intercept[IllegalArgumentException](judgment(urgent))
    assert(failure.getMessage.contains("question 'urgent' was not asked"), failure.getMessage)
  }

  test("reading an answer of another kind fails, naming the question") {
    val asYesNo = Question.yesNo("route", "Is it billing?")
    val failure = intercept[IllegalArgumentException](judgment(asYesNo))
    assert(failure.getMessage.contains("question 'route' is a yes/no"), failure.getMessage)
  }

  test("reading an answer whose option the question no longer offers fails, naming it") {
    val withoutBilling = Question.choice[Team]("route", "Which team?")(
      Team.Technical -> ("technical", "Bugs"),
      Team.Sales     -> ("sales", "Pricing")
    )
    val failure = intercept[IllegalArgumentException](judgment(withoutBilling))
    assert(failure.getMessage.contains("question 'route' does not offer the option 'billing'"))
  }

  test("a question that gained an option reads an older judgment, the new option at 0") {
    val grown = Question.choiceByKey("route", "Which team?")(
      "billing"   -> "Payments",
      "technical" -> "Bugs",
      "sales"     -> "Pricing",
      "legal"     -> "Contracts"
    )
    val answer = judgment(grown)
    assertEquals(answer.choice, "billing")
    assertEquals(answer.probabilities("legal"), 0.0)
  }

  private def request(questions: Question[?]*) =
    JudgmentRequest(JudgmentState.Text("a ticket"), questions.toVector, 1.second)

  private def problem(questions: Question[?]*)(answers: (String, Judgment.Stored)*): String =
    Judgment
      .verify(request(questions*), Judgment("m", answers.toMap, TokenUsage.zero))
      .left
      .getOrElse(fail("expected the judgment to be refused"))

  test("verification accepts an answer that fits every question") {
    assertEquals(Judgment.verify(request(route, frustration, refund), judgment), Right(()))
  }

  test("verification refuses a missing answer, and an answer for a question not asked") {
    assert(problem(route, refund)("refund" -> Judgment.Stored.YesNo(0.1)).contains("'route'"))
    assert(
      problem(refund)(
        "refund" -> Judgment.Stored.YesNo(0.1),
        "urgent" -> Judgment.Stored.YesNo(0.1)
      ).contains("question 'urgent', which was not asked")
    )
  }

  test("verification refuses an answer of another kind") {
    assert(
      problem(refund)("refund" -> Judgment.Stored.Score(1, Vector(0, 1), 1))
        .contains("question 'refund' is a yes/no, but its answer is a score")
    )
  }

  test("verification refuses a choice that is not offered, or whose probabilities do not fit") {
    val probs = Map("billing" -> 0.5, "technical" -> 0.3, "sales" -> 0.2)
    assert(
      problem(route)("route" -> Judgment.Stored.Choice("legal", probs, 0.5))
        .contains("chose 'legal'")
    )
    assert(
      problem(route)("route" -> Judgment.Stored.Choice("billing", probs - "sales", 0.5))
        .contains("no probability for 'sales'")
    )
    assert(
      problem(route)(
        "route" -> Judgment.Stored.Choice("billing", probs + ("legal" -> 0.0), 0.5)
      ).contains("probability for 'legal'")
    )
    assert(
      problem(route)("route" -> Judgment.Stored.Choice("billing", probs, 1.5))
        .contains("confidence is outside 0 to 1")
    )
  }

  test("verification refuses a score off the scale or with the wrong number of levels") {
    assert(
      problem(frustration)("frustration" -> Judgment.Stored.Score(3.5, Vector(0, 0, 0, 1), 1))
        .contains("off the scale 0 to 3")
    )
    assert(
      problem(frustration)("frustration" -> Judgment.Stored.Score(1, Vector(0, 1, 0), 1))
        .contains("3 probabilities for 4 levels")
    )
  }

  test("verification refuses a probability outside 0 to 1") {
    assert(problem(refund)("refund" -> Judgment.Stored.YesNo(1.2)).contains("outside 0 to 1"))
  }
