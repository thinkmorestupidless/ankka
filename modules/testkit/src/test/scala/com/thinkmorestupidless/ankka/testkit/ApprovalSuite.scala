package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, SessionId}

import scala.concurrent.duration.DurationInt

/**
 * A request agent's tool that waits for a person, end to end: sharded per session, the turn
 * suspended in real session memory in Postgres, decided after a restart.
 *
 * One case per scenario of `features/agents/approvals.feature`, named for it, and the turn cut off
 * after its last decision, which the spec's edge cases describe.
 */
class ApprovalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val model                 = TestModelProvider()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ApprovalAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
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

  private val refundArguments = Json.obj("order" -> Json.str("o-7"), "amount" -> Json.num(40))

  private def scriptRefund(callId: String = "call-refund"): Unit =
    model.expectToolCall("issue_refund", refundArguments, callId): Unit

  private def ask(session: String, question: String = "refund order o-7") =
    agent(session).ask(ApprovalAgent.ask).invoke(question)

  private def awaiting(outcome: AgentOutcome[?]): Vector[ApprovalRequest] = outcome match
    case AgentOutcome.AwaitingApproval(requests) => requests
    case other                                   => fail(s"expected approval requests, got $other")

  private def answered[O](outcome: AgentOutcome[O]): O = outcome match
    case AgentOutcome.Answered(value) => value
    case other                        => fail(s"expected an answer, got $other")

  private def decide(session: String, decision: Decision) =
    agent(session).decide(ApprovalAgent.ask)(decision)

  private def refusalOf(body: => Any): CommandError = intercept[CommandError](body)

  /** The tool results the model was sent in its most recent request. */
  private def resultsSent: Vector[ToolResult] =
    model.lastRequest.messages.collect { case ChatMessage.ToolResults(results) => results }.flatten

  test(
    "a tool call that requires approval gives the caller an approval request instead of an answer"
  ) {
    scriptRefund()

    val requests = awaiting(ask("s-asks"))

    assertEquals(requests.map(_.tool), Vector("issue_refund"))
    assertEquals(requests.head.arguments, refundArguments)
    assert(requests.head.awaiting)
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty, "the tool has not run")
  }

  test("an approved tool call runs once and the model is told its result") {
    scriptRefund()
    val request = awaiting(ask("s-approved")).head
    model.expectText("Your refund of 40 is on its way.")

    val outcome = decide("s-approved", Decision.approved(request.id, "dana"))

    assertEquals(answered(outcome), "Your refund of 40 is on its way.")
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector("issue_refund(o-7,40)"))
    assertEquals(resultsSent.map(_.content), Vector("refunded 40 on o-7"))
    val history = historyOf("s-approved")
    assertEquals(history.suspended, None)
    assertEquals(
      history.messages.map(_.getClass.getSimpleName),
      Vector("UserMessage", "AiMessage", "ToolResultMessage", "AiMessage")
    )
  }

  test("an approval request is still awaiting a decision after the service restarts") {
    scriptRefund()
    val request = awaiting(ask("s-restart")).head

    testKit.restartService()

    assertEquals(agent("s-restart").approvals().map(_.id), Vector(request.id))
    model.expectText("Done.")
    assertEquals(answered(decide("s-restart", Decision.approved(request.id, "dana"))), "Done.")
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector("issue_refund(o-7,40)"))
  }

  test("a refused tool call never runs and the model is told the note") {
    scriptRefund()
    val request = awaiting(ask("s-refused")).head
    model.expectText("I'm sorry, that refund was not approved.")

    val outcome = decide("s-refused", Decision.refused(request.id, "dana", "over the limit"))

    assertEquals(answered(outcome), "I'm sorry, that refund was not approved.")
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)
    val sent = resultsSent.head
    assert(sent.isError, sent.toString)
    assert(sent.content.contains("over the limit"), sent.content)
    assert(sent.content.contains("dana"), sent.content)
  }

  Vector(
    "approves" -> ((id: String) => Decision.approved(id, "sam")),
    "refuses"  -> ((id: String) => Decision.refused(id, "sam"))
  ).foreach { (verb, again) =>
    test(s"an approval request is decided once (a person $verb it again)") {
      val session = s"s-once-$verb"
      scriptRefund()
      val request = awaiting(ask(session)).head
      model.expectText("Refunded.")
      answered(decide(session, Decision.approved(request.id, "dana"))): Unit

      val refused = refusalOf(decide(session, again(request.id)))

      assertEquals(refused.code, ErrorCode.Conflict)
      assert(refused.getMessage.contains("is decided"), refused.getMessage)
      assertEquals(ApprovalAgent.runsOf("issue_refund").size, 1)
    }
  }

  test("a decision for an approval request the session does not hold is refused") {
    scriptRefund()
    awaiting(ask("s-unknown-id")): Unit

    val refused = refusalOf(decide("s-unknown-id", Decision.approved("a-404", "dana")))

    assertEquals(refused.code, ErrorCode.NotFound)
    assert(refused.getMessage.contains("'a-404'"), refused.getMessage)
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)
  }

  test("a decision sent to a session no agent has used is refused") {
    val refused = refusalOf(decide("s-9", Decision.approved("a-1", "dana")))

    assertEquals(refused.code, ErrorCode.NotFound)
    assert(refused.getMessage.contains("'s-9'"), refused.getMessage)
  }

  test("a session with an approval request awaiting a decision takes no new request") {
    scriptRefund()
    awaiting(ask("s-busy")): Unit
    val calls = model.callCount

    val refused = refusalOf(ask("s-busy", "and another thing"))

    assertEquals(refused.code, ErrorCode.Conflict)
    assert(refused.getMessage.contains("awaiting a decision"), refused.getMessage)
    assertEquals(model.callCount, calls, "the model is not asked")
  }

  test("a tool that requires no approval runs beside one that waits") {
    model.expectParallelToolCalls(
      ToolCall("call-read", "read_order", Json.obj("order" -> Json.str("o-7"))),
      ToolCall("call-refund", "issue_refund", refundArguments)
    ): Unit

    val requests = awaiting(ask("s-beside"))

    assertEquals(requests.map(_.tool), Vector("issue_refund"))
    assertEquals(ApprovalAgent.runsOf("read_order"), Vector("read_order(o-7)"))
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)

    // Decided, both results go back to the model together, in the order it made the calls.
    model.expectText("Refunded.")
    answered(decide("s-beside", Decision.approved(requests.head.id, "dana"))): Unit
    assertEquals(resultsSent.map(_.callId), Vector("call-read", "call-refund"))
    assertEquals(ApprovalAgent.runsOf("read_order").size, 1, "the tool that ran is not run again")
  }

  test("two tool calls made together are two approval requests, each decided alone") {
    model.expectParallelToolCalls(
      ToolCall("call-1", "issue_refund", refundArguments),
      ToolCall(
        "call-2",
        "issue_refund",
        Json.obj("order" -> Json.str("o-8"), "amount" -> Json.num(15))
      )
    ): Unit
    val requests = awaiting(ask("s-two"))
    assertEquals(requests.size, 2)
    assertNotEquals(requests(0).id, requests(1).id)
    val calls = model.callCount

    val first = decide("s-two", Decision.approved(requests(0).id, "dana"))

    assertEquals(awaiting(first).map(_.id), Vector(requests(1).id))
    assertEquals(model.callCount, calls, "the model is not asked until both are decided")
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)

    model.expectText("One refunded, one not.")
    val second = decide("s-two", Decision.refused(requests(1).id, "dana"))
    assertEquals(answered(second), "One refunded, one not.")
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector("issue_refund(o-7,40)"))
  }

  test("a session shows an approval request that is awaiting a decision") {
    scriptRefund()
    val request = awaiting(ask("s-shows")).head

    assertEquals(agent("s-shows").approvals(), Vector(request))
    val turn = historyOf("s-shows").suspended.getOrElse(fail("no suspended turn"))
    assertEquals(turn.handler, "ask")
    assertEquals(
      turn.messages.collect { case m: SessionMessage.AiMessage => m.toolCalls.map(_.name) }.flatten,
      Vector("issue_refund")
    )
  }

  test("a tool that requires no approval runs when the model calls it") {
    model.expectToolCall("read_order", Json.obj("order" -> Json.str("o-7")), "call-read"): Unit
    model.expectText("It is one teapot.")

    assertEquals(answered(ask("s-plain", "what is in o-7?")), "It is one teapot.")
    assertEquals(ApprovalAgent.runsOf("read_order"), Vector("read_order(o-7)"))
    assertEquals(historyOf("s-plain").suspended, None)
  }

  test("compaction keeps an approval request that is awaiting a decision") {
    model.expectText("Hello.")
    answered(ask("s-compact", "hello")): Unit
    scriptRefund()
    val request = awaiting(ask("s-compact")).head

    // What the compactor does to a session: replace its oldest messages with a summary.
    testKit.componentClient
      .forSessionMemory(SessionId("s-compact"))
      .call(SessionMemoryEntity.compact)
      .invoke(SessionMemoryEntity.Compact("They said hello.", 2, "ankka-compactor")): Unit

    assertEquals(agent("s-compact").approvals().map(_.id), Vector(request.id))
    model.expectText("Refunded.")
    assertEquals(answered(decide("s-compact", Decision.approved(request.id, "dana"))), "Refunded.")
  }

  test("a session shows who decided an approval request") {
    scriptRefund()
    val request = awaiting(ask("s-who")).head
    model.expectText("Refunded.")
    answered(decide("s-who", Decision.approved(request.id, "dana"))): Unit

    val result = historyOf("s-who").messages.collectFirst {
      case m: SessionMessage.ToolResultMessage => m
    }
    assertEquals(result.flatMap(_.decision).map(d => (d.by, d.approved)), Some(("dana", true)))
  }

  test("a decision that does not say who made it is refused") {
    scriptRefund()
    val request = awaiting(ask("s-nobody")).head

    val refused = refusalOf(decide("s-nobody", Decision.approved(request.id, "")))

    assertEquals(refused.code, ErrorCode.BadRequest)
    assert(refused.getMessage.contains("name who made it"), refused.getMessage)
    assertEquals(agent("s-nobody").approvals().map(_.id), Vector(request.id))
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)
  }

  test("a turn cut off after its last decision is ended, and its tool is not run again") {
    scriptRefund()
    val request = awaiting(ask("s-cut")).head
    // No answer scripted: the model fails after the approved tool has run.

    val failed = refusalOf(decide("s-cut", Decision.approved(request.id, "dana")))

    assert(failed.getMessage.contains("no scripted response"), failed.getMessage)
    assertEquals(ApprovalAgent.runsOf("issue_refund").size, 1)
    assertEquals(historyOf("s-cut").suspended, None, "the turn is ended")
    assertEquals(historyOf("s-cut").messages, Vector.empty, "nothing is added to the history")

    model.expectText("Hello again.")
    assertEquals(answered(ask("s-cut", "hello?")), "Hello again.", "the session takes requests")
    val again = refusalOf(decide("s-cut", Decision.approved(request.id, "dana")))
    assertEquals(again.code, ErrorCode.NotFound)
    assertEquals(ApprovalAgent.runsOf("issue_refund").size, 1, "and the tool never runs twice")
  }

  test("a turn that remembers nothing still waits in the session") {
    model.expectToolCall("issue_refund", refundArguments, "call-refund"): Unit
    val request = awaiting(agent("s-once").ask(ApprovalAgent.askOnce).invoke("refund o-7"))

    val busy = refusalOf(agent("s-once").ask(ApprovalAgent.askOnce).invoke("again"))
    assertEquals(busy.code, ErrorCode.Conflict)

    model.expectText("Refunded.")
    val outcome = agent("s-once").decide(ApprovalAgent.askOnce)(
      Decision.approved(request.head.id, "dana")
    )
    assertEquals(answered(outcome), "Refunded.")
    val history = historyOf("s-once")
    assertEquals(history.suspended, None)
    assertEquals(history.messages, Vector.empty)
  }

  test("a decision sent for another handler than the one whose turn waits is refused") {
    model.expectToolCall("issue_refund", refundArguments, "call-refund"): Unit
    val request = awaiting(agent("s-handle").ask(ApprovalAgent.askOnce).invoke("refund o-7")).head

    val refused = refusalOf(decide("s-handle", Decision.approved(request.id, "dana")))

    assertEquals(refused.code, ErrorCode.BadRequest)
    assert(refused.getMessage.contains("'ask-once'"), refused.getMessage)
    assertEquals(agent("s-handle").approvals().map(_.id), Vector(request.id), "nothing decided")
  }

  test("a tool with a time limit for approval is refused in a service with no TimerRuntime") {
    model.expectToolCall(
      "close_account",
      Json.obj("customer" -> Json.str("c-9")),
      "call-close"
    ): Unit

    val refused = refusalOf(ask("s-no-timers", "close my account"))

    assertEquals(refused.code, ErrorCode.Internal)
    assert(refused.getMessage.contains("TimerRuntime"), refused.getMessage)
    assertEquals(historyOf("s-no-timers").suspended, None, "nothing is recorded")
    assertEquals(ApprovalAgent.runsOf("close_account"), Vector.empty)
  }

  test("call throws ApprovalAwaited when the turn waits") {
    scriptRefund()
    val thrown = intercept[ApprovalAwaited](
      agent("s-call").call(ApprovalAgent.ask).invoke("refund order o-7")
    )
    assertEquals(thrown.requests.map(_.tool), Vector("issue_refund"))
  }
