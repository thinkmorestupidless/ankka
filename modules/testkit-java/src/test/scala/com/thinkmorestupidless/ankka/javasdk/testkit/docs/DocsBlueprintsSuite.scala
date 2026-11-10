package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge
import com.thinkmorestupidless.ankka.agent.judgment.{Answers, TestJudgmentProvider, YesNoQuestion}
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt

/** The Java samples of `docs/build/blueprints.md`: the guide's blueprint, run once. */
class DocsBlueprintsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model             = TestModelProvider()
  private val judge             = TestJudgmentProvider()
  private var kit: AnkkaTestKit = null
  private def client            = kit.componentClient

  private val onTopic =
    AgentBridge.scalaQuestion(GuideBlueprints.ON_TOPIC).asInstanceOf[YesNoQuestion]

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(BlueprintService.service(model, AgentBridge.judgmentProvider(judge)))
  override def afterAll(): Unit = if kit != null then kit.close()

  test("the guide's blueprint runs every pattern it shows") {
    model
      .expectText("""{"points":["why","how"]}""")
      .expectCompleteTask("""{"text":"a draft about ducks"}""")
      .expectText("""{"text":"ducks, polished"}""")
      .expectText("""{"passed":true,"reasons":[]}""")
    judge.always(Answers.yesNo(onTopic, 0.9)): Unit

    val run = BlueprintCalls.brief(client, "ducks")
    assertEquals(run.status, "completed", run.toString)
    assertEquals(run.stepNamed("polish").get.result.get, """{"text":"ducks, polished"}""")
    val filed = run.stepNamed("file").get.result.get
    assert(filed.contains("\"filed\":true"), filed)
    assert(filed.contains("brief-7"), filed)
    assert(run.stepNamed("check").get.result.isPresent, run.toString)
  }

  test("a carried blueprint is already registered; registering it again is not a new version") {
    val registered = BlueprintCalls.register(client, GuideBlueprints.BRIEF.json())
    assertEquals(registered.name, "brief")
    assertEquals(registered.isNew, false)
    assertEquals(AgentClients.blueprints(client).versions("brief").size, 1)
    assertEquals(AgentClients.blueprints(client).current("brief").get.name, "brief")
  }

  test(
    "one version of a blueprint is read back as it was registered; one never registered is not found"
  ) {
    val blueprints = AgentClients.blueprints(client)
    assertEquals(blueprints.version("brief", 1).digest, GuideBlueprints.BRIEF.digest)
    val missing = intercept[CommandException](blueprints.version("brief", 9))
    assertEquals(missing.problem.status, 404, missing.problem.toString)
    assertEquals(intercept[CommandException](blueprints.version("never", 1)).problem.status, 404)
  }

  test("a scheduled blueprint registers, its input admitting a period") {
    val registered = AgentClients.blueprints(client).register(DigestBlueprint.DIGEST)
    assertEquals(registered.name, "digest")
    assert(registered.isNew, registered.toString)
  }

  test("the repeating steps are built from the step, not a new pattern") {
    assertEquals(GuideBlueprints.DIG.name, "dig")
    assertEquals(GuideBlueprints.VIEWS.name, "views")
  }
