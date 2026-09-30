package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.Json

import scala.concurrent.duration.DurationInt

/**
 * Jev itself: the adapter against the real endpoint.
 *
 * Skipped unless `TYPESAFE_API_KEY` is set, so an ordinary `sbt test` stays free, offline and
 * deterministic. It is the check that the provider's documented shapes — which the offline suite's
 * canned response is built from — are what the endpoint actually returns.
 */
class JevProviderLiveSuite extends munit.FunSuite:

  import Questions.*

  override val munitTimeout = 1.minute

  private val apiKey = Option(System.getenv(JevProvider.KeyVariable)).filter(_.nonEmpty)

  override def beforeEach(context: BeforeEach): Unit =
    assume(apiKey.isDefined, s"${JevProvider.KeyVariable} is not set; skipping the live API test")

  private def judgments = Judgments(Some(JevProvider.fromEnv()), 10.seconds)

  private val ticket =
    "I was charged twice for order A-104 and nobody has answered my last two emails. " +
      "Refund the duplicate today or I am cancelling my subscription."

  test("a choice, a score and a yes/no come back verified, from a named version") {
    val judgment =
      judgments.ask(None, JudgmentState.Text(ticket), Vector(route, frustration, refund, urgent))
    assert(judgment.model.startsWith("jev-"), judgment.model)
    assert(!judgment.model.endsWith("latest"), judgment.model)
    assert(judgment.usage.inputTokens > 0, judgment.usage.toString)
    assert(route.optionFor(judgment(route).choice).isDefined)
    val score = judgment(frustration).score
    assert(score >= 0 && score <= frustration.top, score.toString)
    val refundAsked = judgment(refund).probability
    assert(refundAsked >= 0 && refundAsked <= 1, refundAsked.toString)
  }

  test("a structured state is accepted") {
    val state = Json.obj(
      "ticket"   -> Json.obj("id" -> Json.str("A-104"), "text" -> Json.str(ticket)),
      "customer" -> Json.obj("plan" -> Json.str("pro"), "contacts_this_week" -> Json.num(3))
    )
    val judgment = judgments.ask(None, JudgmentState.Structured(state), Vector(refund))
    assert(judgment.contains(refund))
  }

  test("a key the provider refuses fails at once, naming the status") {
    val failure = intercept[JudgmentFailed](
      Judgments(Some(JevProvider.withApiKey("not-a-key")), 10.seconds)
        .ask(None, JudgmentState.Text(ticket), Vector(refund))
    )
    assert(failure.getMessage.contains("401"), failure.getMessage)
  }
