package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

/**
 * V4 for blueprints (research R5, R16): the ask agent builds its effect from the turn's payload
 * alone, so a turn suspended on an approval is rebuilt from the recorded payload when the decision
 * comes, with the same tools, and the approved tool runs exactly once.
 */
class AskApprovalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model = TestModelProvider()
  private val ran   = AtomicInteger(0)
  private val send = FunctionTool
    .named("send_letter")
    .describedAs("Sends the letter.")
    .handle { () =>
      ran.incrementAndGet(); "sent"
    }
    .requiresApproval

  private val agents =
    AgentRuntime.withDefaultModel(model).withBlueprints(_ => BlueprintRegistry.empty.tools(send))
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents))
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private val worker =
    Worker("clerk").instructions("Send the letter when asked.").tools("send_letter").budget(3)
  private val turn = WorkerTurn(
    worker,
    "Step 'send'.\n{}",
    Shape.obj("done" -> Shape.boolean),
    RunRef("r-1", "send", "post", 1)
  )

  test("V4 a turn suspended on an approval is resumed from its payload and the tool runs once") {
    model.expectToolCall("send_letter", Json.obj()).expectText("""{"done":true}""")
    val session = kit.componentClient.forAgent(SessionId("run:r-1:send:clerk"))

    val requests = session.ask(AskAgent.turn).invoke(turn) match
      case AgentOutcome.AwaitingApproval(requests) => requests
      case other => fail(s"expected to wait for approval, got $other")
    assertEquals(requests.map(_.tool), Vector("send_letter"))
    assertEquals(ran.get(), 0)

    val outcome = session.decide(AskAgent.turn)(Decision.approved(requests.head.id, "dana"))
    assertEquals(outcome, AgentOutcome.Answered("""{"done":true}"""))
    assertEquals(ran.get(), 1)
    // The handler was run again from the recorded payload: the same worker, hence the same tool.
    assertEquals(model.lastRequest.tools.map(_.name), Vector("send_letter"))
    assertEquals(
      model.lastRequest.systemMessage.exists(_.startsWith("Send the letter when asked.")),
      true
    )
  }
