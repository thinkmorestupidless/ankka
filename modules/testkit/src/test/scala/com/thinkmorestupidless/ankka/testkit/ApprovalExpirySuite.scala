package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, SessionId}
import com.thinkmorestupidless.ankka.runtime.TimerRuntime

import scala.concurrent.duration.DurationInt

/**
 * A request agent's approval request with a time limit, end to end: the timer in Postgres, the
 * cluster's sweeper firing it, the platform's decision going on with the turn.
 *
 * The time-limit scenarios of `features/agents/approvals.feature`, with the fixture's two-second
 * limit standing for the scenarios' thirty minutes.
 */
class ApprovalExpirySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val model                 = TestModelProvider()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ApprovalAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), TimerRuntime())
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    ApprovalAgent.runs.clear()

  private def agent(session: String) = testKit.componentClient.forAgent(SessionId(session))

  private def historyOf(session: String) =
    testKit.componentClient
      .forSessionMemory(SessionId(session))
      .call(SessionMemoryEntity.history)
      .invoke()

  private def askToClose(session: String): ApprovalRequest =
    model.expectToolCall(
      "close_account",
      Json.obj("customer" -> Json.str("c-9")),
      "call-close"
    ): Unit
    agent(session).ask(ApprovalAgent.ask).invoke("close my account") match
      case AgentOutcome.AwaitingApproval(requests) => requests.head
      case other => fail(s"expected an approval request, got $other")

  private def expiredResults(session: String) =
    historyOf(session).messages.collect {
      case m: SessionMessage.ToolResultMessage if m.decision.exists(_.expired) => m
    }

  private def awaitExpiry(session: String): SessionMessage.ToolResultMessage =
    testKit.eventually(s"$session's approval request expires", 30.seconds)(
      Option
        .when(historyOf(session).suspended.isEmpty)(())
        .flatMap(_ => expiredResults(session).headOption)
    )

  test("an approval request with a time limit is refused when nobody decides it in time") {
    val request = askToClose("s-expires")
    assert(request.expiresAt.exists(_ > request.requestedAt), request.toString)
    model.expectText("I could not close it without a supervisor's approval."): Unit

    val result = awaitExpiry("s-expires")

    assertEquals(ApprovalAgent.runsOf("close_account"), Vector.empty, "the tool has not run")
    assert(result.isError && result.content.contains("expired"), result.content)
    assertEquals(result.decision.map(_.by), Some(Decision.Platform), "the platform decided it")
    // The model was told, and its answer is in the session though nobody was waiting for it.
    assert(
      historyOf("s-expires").messages.lastOption.exists {
        case m: SessionMessage.AiMessage => m.text.contains("could not close")
        case _                           => false
      },
      historyOf("s-expires").messages.toString
    )
  }

  test("an approval request with no time limit waits however long nobody decides it") {
    model.expectToolCall(
      "issue_refund",
      Json.obj("order" -> Json.str("o-7"), "amount" -> Json.num(40)),
      "call-refund"
    ): Unit
    agent("s-waits").ask(ApprovalAgent.ask).invoke("refund o-7"): Unit

    // Longer than the fixture's time limit and the sweeper's poll together.
    Thread.sleep(5000)

    assertEquals(agent("s-waits").approvals().map(_.tool), Vector("issue_refund"))
  }

  test("a decision for an approval request that has expired is refused") {
    val request = askToClose("s-late")
    model.expectText("Not closed."): Unit
    awaitExpiry("s-late"): Unit

    val refused = intercept[CommandError](
      agent("s-late").decide(ApprovalAgent.ask)(Decision.approved(request.id, "dana"))
    )

    assertEquals(refused.code, ErrorCode.Conflict)
    assert(refused.getMessage.contains("is decided"), refused.getMessage)
    assertEquals(ApprovalAgent.runsOf("close_account"), Vector.empty)
  }

  test("an approval request's time limit holds after the service restarts") {
    askToClose("s-restart-expiry")

    testKit.restartService()

    model.expectText("Not closed."): Unit
    awaitExpiry("s-restart-expiry"): Unit
    // Once: one expired result, and the model asked once about it.
    Thread.sleep(2000)
    assertEquals(expiredResults("s-restart-expiry").size, 1)
    assertEquals(model.callCount, 2)
  }

  test("a decision made in time forgets the time limit") {
    val request = askToClose("s-in-time")
    model.expectText("Closed."): Unit

    agent("s-in-time").decide(ApprovalAgent.ask)(Decision.approved(request.id, "dana")): Unit
    Thread.sleep(4000)

    assertEquals(ApprovalAgent.runsOf("close_account"), Vector("close_account(c-9)"))
    assertEquals(expiredResults("s-in-time"), Vector.empty)
    assertEquals(model.callCount, 2, "the expiry did not resume anything")
  }
