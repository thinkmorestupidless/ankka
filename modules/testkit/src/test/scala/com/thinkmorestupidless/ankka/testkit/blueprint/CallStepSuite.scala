package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A call step: the one node from which anything the patterns do not cover can be built in code,
 * with the run's durability. The handler is given what the step reads and told which run it serves;
 * what it returns, checked against the step's shape, is the step's result.
 */
class CallStepSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model = TestModelProvider()
  private val seen  = ConcurrentLinkedQueue[(Option[RunRef], Json)]()
  private val keep = BlueprintHandler("keep_paper") { (ref, input) =>
    seen.add(Some(ref) -> input): Unit
    Json.obj("kept" -> Json.num(input("papers").flatMap(_.asArray).map(_.size).getOrElse(0)))
  }
  private val broken =
    BlueprintHandler("broken")((_, _) => throw IllegalStateException("the archive is down"))
  private val agents =
    AgentRuntime
      .withDefaultModel(model)
      .withBlueprints(_ => BlueprintRegistry.empty.handlers(keep, broken))
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents))
  override def afterAll(): Unit  = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    seen.clear()
    model.whenUserAsks("Step 'papers'")(ModelResponse("""["p1","p2","p3"]""")): Unit

  private val writer = Worker("writer").instructions("List papers.").budget(2)

  test("a call step runs a handler with what it reads and keeps what it returns") {
    val bp = Blueprint("keeper")
      .worker(writer)
      .step(Step("papers").ask("writer").reads("input").result(Shape.arr(Shape.string)))
      .step(
        Step("keep").call("keep_paper").reads("papers").result(Shape.obj("kept" -> Shape.integer))
      )
    agents.blueprints.register(bp): Unit
    agents.runs.start("keeper", Json.obj(), "keeper-1"): Unit
    val run = agents.runs.await("keeper-1", 60.seconds)
    assertEquals(run.status, RunStatus.Completed, run.reason.toString)
    assertEquals(run.stepNamed("keep").flatMap(_.result), Some(Json.obj("kept" -> Json.num(3))))
    assertEquals(run.stepNamed("keep").map(_.modelCalls), Some(0))
    val (ref, input) = seen.asScala.head
    assertEquals(ref, Some(RunRef("keeper-1", "keep", "keeper", 1)))
    assertEquals(input("papers").flatMap(_.asArray).map(_.size), Some(3))
  }

  test("a handler that fails fails the run, naming the step and the handler") {
    val bp = Blueprint("archiver").step(Step("archive").call("broken").reads("input"))
    agents.blueprints.register(bp): Unit
    agents.runs.start("archiver", Json.obj(), "archiver-1"): Unit
    val run = agents.runs.await("archiver-1", 60.seconds)
    assertEquals(run.status, RunStatus.Failed)
    assert(
      run.reason.exists(r =>
        r.startsWith("archive:") && r.contains("'broken'") && r.contains("archive is down")
      ),
      run.reason.toString
    )
  }
