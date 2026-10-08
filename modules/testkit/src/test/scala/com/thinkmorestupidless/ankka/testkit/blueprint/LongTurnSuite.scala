package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * An ask turn whose tool takes longer than the platform's ask waits (ten seconds) outlives the call
 * to it. The worker treats the timed-out call as a turn still in progress and watches the session
 * for its answer; asking again would make a second turn and a second set of model calls.
 */
class LongTurnSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private val model    = TestModelProvider()
  private val slowRuns = AtomicInteger(0)
  private val slow = FunctionTool.named("slow").describedAs("Takes its time.").handle { () =>
    slowRuns.incrementAndGet()
    Thread.sleep(13.seconds.toMillis)
    "done at last"
  }
  private val agents =
    AgentRuntime.withDefaultModel(model).withBlueprints(_ => BlueprintRegistry.empty.tools(slow))
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents))
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def afterTool(request: ModelRequest): Boolean =
    request.messages.lastOption.exists {
      case _: ChatMessage.ToolResults => true
      case _                          => false
    }

  test("a turn that outlives the call to it is waited for, not asked again") {
    model.whenRequest(r => !afterTool(r))(
      ModelResponse("", Vector(ToolCall("c-slow", "slow", Json.obj())), StopReason.ToolUse)
    )
    model.whenRequest(r => afterTool(r))(ModelResponse("""{"text":"finished"}"""))

    val bp = Blueprint("patient")
      .worker(Worker("writer").instructions("Take your time.").tools("slow").budget(3))
      .step(Step("wait").ask("writer").reads("input").result(Shape.obj("text" -> Shape.string)))
    agents.blueprints.register(bp): Unit
    agents.runs.start("patient", Json.obj(), "patient-1"): Unit

    val run = agents.runs.await("patient-1", 90.seconds)
    assertEquals(run.status, RunStatus.Completed, run.reason.toString)
    assertEquals(
      run.stepNamed("wait").flatMap(_.result),
      Some(Json.obj("text" -> Json.str("finished")))
    )
    // One turn: the tool ran once and the model was called twice, however long the call waited.
    assertEquals(slowRuns.get(), 1)
    assertEquals(model.requests.size, 2)
    assertEquals(run.stepNamed("wait").map(_.modelCalls), Some(2))
  }
