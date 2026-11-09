package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge
import com.thinkmorestupidless.ankka.agent.javasdk.{
  AgentClients,
  ChoiceQuestion,
  ScoreQuestion,
  YesNoQuestion
}
import com.thinkmorestupidless.ankka.agent.judgment
import com.thinkmorestupidless.ankka.agent.judgment.{Answers, TestJudgmentProvider}
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.util.Optional
import scala.concurrent.duration.DurationInt

/** The Java samples of the judgments page, against a scripted model and a scripted judge. */
class DocsJudgmentsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model = TestModelProvider()
  private val judge = TestJudgmentProvider()

  private var kit: AnkkaTestKit = null
  private def client            = kit.componentClient

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(TriageCalls.service(model, AgentBridge.judgmentProvider(judge)))
  override def afterAll(): Unit                      = if kit != null then kit.close()
  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    judge.reset()

  private def scala[T](q: ChoiceQuestion[T]): judgment.ChoiceQuestion[T] =
    AgentBridge.scalaQuestion(q).asInstanceOf[judgment.ChoiceQuestion[T]]
  private def scala(q: ScoreQuestion): judgment.ScoreQuestion =
    AgentBridge.scalaQuestion(q).asInstanceOf[judgment.ScoreQuestion]
  private def scala(q: YesNoQuestion): judgment.YesNoQuestion =
    AgentBridge.scalaQuestion(q).asInstanceOf[judgment.YesNoQuestion]

  test("a handler judges a ticket, and the caller reads each answer through its question") {
    judge.expect(
      Answers.choice(scala(TriageAgent.ROUTE), TriageAgent.Team.BILLING, 0.9),
      Answers.score(scala(TriageAgent.FRUSTRATION), 1.0),
      Answers.yesNo(scala(TriageAgent.REFUND), 0.8)
    )
    val where = TriageCalls.dispatch(client, TriageAgent.Ticket("t-1", "I was charged twice"))
    assertEquals(where, "billing, refund asked")
    judge.expect(
      Answers.choice(scala(TriageAgent.ROUTE), TriageAgent.Team.SALES, 0.4),
      Answers.score(scala(TriageAgent.FRUSTRATION), 0.0),
      Answers.yesNo(scala(TriageAgent.REFUND), 0.1)
    )
    assertEquals(TriageCalls.dispatch(client, TriageAgent.Ticket("t-2", "Hello?")), "a person")
  }

  test("a handler replies with its own decision computed from the judgment") {
    judge.expect(
      Answers.choice(scala(TriageAgent.ROUTE), TriageAgent.Team.TECHNICAL, 0.8),
      Answers.yesNo(scala(TriageAgent.URGENT), 0.9)
    )
    val routing = AgentClients
      .forAgent(client, "t-3")
      .call(DocsRefs.routing)
      .invoke(TriageAgent.Ticket("t-3", "The site is down"))
    assertEquals(routing, TriageAgent.Routing(Optional.of(TriageAgent.Team.TECHNICAL), true))
  }

  test("a judged guardrail checks the text going in and coming out, on every request") {
    judge.always(
      Answers.yesNo(scala(TriageAgent.Safety.OVERRIDES_INSTRUCTIONS), 0.02),
      Answers.yesNo(scala(TriageAgent.Safety.GIVES_MEDICAL_ADVICE), 0.01),
      Answers.score(scala(TriageAgent.Safety.HOSTILITY), 0),
      Answers.choice(scala(TriageAgent.Safety.TOPIC), "general")
    )
    model.expectText("Your order ships tomorrow.").expectText("It left the warehouse today.")
    val agent  = AgentClients.forAgent(client, "s-both")
    val first  = agent.call(DocsRefs.guarded).invoke("Where is my order?")
    val second = agent.call(DocsRefs.guarded).invoke("And now?")
    assertEquals((first, second), ("Your order ships tomorrow.", "It left the warehouse today."))
    assertEquals(judge.callCount, 4) // an input and an output check per call
    assertEquals(model.callCount, 2)
  }
