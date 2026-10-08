package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.agent.judgment.{Answers, TestJudgmentProvider}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.*

/** Runs the guide's blueprint once, every pattern in it, with a scripted model and judgment. */
class GuideSamplesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model = TestModelProvider()
  private val judge = TestJudgmentProvider()

  // docs:start runtime
  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withJudgments(judge)
    .withBlueprints(_ => GuideSamples.registry)
  // docs:end runtime

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents))

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def isTask(r: ModelRequest)    = r.tools.exists(_.name == "complete_task")
  private def isVerdict(r: ModelRequest) = Scripted.userText(r).exists(_.startsWith("Verdict on"))

  test("the guide's blueprint runs every pattern it shows") {
    model.respondWhen(r => Scripted.forStep(r, "outline"))(_ =>
      Scripted.answer("""{"points":["why","how"]}""")
    )
    model.respondWhen(r => Scripted.forStep(r, "draft") && isTask(r))(_ =>
      Scripted.completeTask(Json.obj("text" -> Json.str("a draft about ducks")))
    )
    model.respondWhen(r => Scripted.forStep(r, "polish") && !isVerdict(r))(_ =>
      Scripted.answer("""{"text":"ducks, polished"}""")
    )
    model.respondWhen(isVerdict)(_ => Scripted.answer("""{"passed":true,"reasons":[]}"""))
    judge.always(Answers.yesNo(GuideSamples.onTopic, 0.9)): Unit

    // docs:start run
    val started =
      agents.runs.start("brief", Json.obj("topic" -> Json.str("ducks")), runId = "brief-7")
    val run = agents.runs.await("brief-7", 60.seconds)
    assertEquals(run.status, RunStatus.Completed)
    assertEquals(
      run.stepNamed("polish").flatMap(_.result),
      Some(Json.obj("text" -> Json.str("ducks, polished")))
    )
    // docs:end run
    assertEquals(started.status, RunStatus.Running)
    assertEquals(run.steps.map(_.name), Vector("outline", "draft", "check", "polish", "file"))
    assertEquals(run.stepNamed("file").flatMap(_.result).flatMap(_("filed")), Some(Json.bool(true)))
    assertEquals(run.stepNamed("check").flatMap(_.result).flatMap(_("on-topic")).isDefined, true)
    assertEquals(run.stepNamed("polish").map(_.rounds.size), Some(1))
    assert(run.modelCalls <= 20)
  }
