package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, FunctionTool, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import scala.concurrent.duration.DurationInt

/**
 * `features/blueprints/registering.feature`, against a real journal. The service of the background
 * is one kit; the scenarios about a blueprint a service carries start a service of their own, since
 * carrying is a matter of how the service is built.
 */
class BlueprintFeatures
    extends GherkinSuite("../../features/blueprints/registering.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private val model = TestModelProvider()
  private val search =
    FunctionTool.named("search").describedAs("Searches a source.").handle(() => "[]")
  private val keep =
    FunctionTool.named("keep_entry").describedAs("Keeps an entry.").handle(() => "kept")

  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withBlueprints(_ => BlueprintRegistry.empty.tools(search, keep))

  private var kit: AnkkaTestKit = null

  /** The service a scenario about a carried blueprint starts, and stops after. */
  private var carried: Option[(AnkkaTestKit, AgentRuntime)] = None
  private var carriedName                                   = ""

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents))

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def afterEach(context: AfterEach): Unit =
    carried.foreach(_._1.stop())
    carried = None

  /** The blueprint calls of whichever service the scenario is about. */
  private def blueprints: BlueprintCalls = carried.fold(agents.blueprints)(_._2.blueprints)

  /** Names are per scenario, since every scenario of this suite shares one journal. */
  private def named(name: String): String = s"$scenarioId-$name"

  private def watch(
      name: String,
      instructions: String = "Search the sources and keep what is new."
  ): Blueprint =
    Blueprint(named(name))
      .worker(Worker("searcher").instructions(instructions).tools("search", "keep_entry").budget(4))
      .step(Step("search").ask("searcher").reads("input"))

  private val registered               = collection.mutable.Map.empty[String, Blueprint]
  private var last: Option[Registered] = None

  // ── Given ───────────────────────────────────────────────────────────────

  Given("a service with the tools {string} and {string} and the model {string}") {
    (a: String, b: String, m: String) =>
      assertEquals(Set(a, b), Set("search", "keep_entry"))
      assertEquals(m, Worker.DefaultModel)
  }

  Given("the blueprint {string} held at blueprint version {int}") { (name: String, version: Int) =>
    val bp = watch(name)
    registered(name) = bp
    last = Some(blueprints.register(bp))
    assertEquals(last.map(_.version), Some(version))
  }

  Given("the blueprint {string} held at blueprint versions 1, 2 and 3") { (name: String) =>
    Vector("Search widely.", "Search narrowly.", "Search twice.").zipWithIndex.foreach { (text, i) =>
      assertEquals(blueprints.register(watch(name, text)).version, i + 1)
    }
  }

  Given("a service whose code carries the blueprint {string}") { (name: String) =>
    carriedName = name
  }

  Given("a service whose code carries the blueprint {string}, held at blueprint version 1") {
    (name: String) =>
      carriedName = name
      startCarried()
      assertEquals(blueprints.versions(named(name)).map(_.number), Vector(1))
  }

  private def startCarried(): Unit =
    val runtime = AgentRuntime
      .withDefaultModel(model)
      .withBlueprints(_ => BlueprintRegistry.empty.tools(search, keep).carrying(watch(carriedName)))
    carried = Some(AnkkaTestKit.start(AgentRuntime.descriptors, Seq(runtime)) -> runtime)

  // ── When ────────────────────────────────────────────────────────────────

  When("the service registers the blueprint {string}") { (name: String) =>
    val bp = watch(name)
    registered(name) = bp
    last = Some(blueprints.register(bp))
  }

  When("the service registers the same blueprint {string} again") { (name: String) =>
    last = Some(blueprints.register(registered(name)))
  }

  When("the service registers {string} with different instructions for one worker") {
    (name: String) =>
      last = Some(blueprints.register(watch(name, "Search, and keep only what is peer reviewed.")))
  }

  When("a caller asks to change a worker's instructions in blueprint version {int}") { (_: Int) =>
    // There is no call that changes a version: the blueprint's handlers register, advance or stop
    // a schedule, and read. Refusal is the absence of the call, checked by name.
    val handlers = BlueprintEntity.descriptor.declaredHandlers.map(_.name.toString).toSet
    assertEquals(handlers, Set("register", "advance-schedule", "stop-schedule", "get"))
    refused = true
  }
  private var refused = false

  When("a reader lists the blueprint versions of {string}") { (name: String) =>
    listed = blueprints.versions(named(name))
  }
  private var listed = Vector.empty[VersionInfo]

  When("the service starts")(() => startCarried())

  When("the service restarts with the same blueprint") { () =>
    carried.get._1.restartService()
  }

  // ── Then ────────────────────────────────────────────────────────────────

  Then("the blueprint {string} is held at blueprint version {int}") { (name: String, version: Int) =>
    assertEquals(blueprints.versions(named(name)).lastOption.map(_.number), Some(version))
  }

  Then("a reader reads blueprint version {int} of {string} as it was registered") {
    (version: Int, name: String) =>
      assertEquals(blueprints.version(named(name), version), registered(name))
  }

  Then("blueprint version {int} of {string} reads as it was registered") {
    (version: Int, name: String) =>
      assertEquals(blueprints.version(named(name), version), registered(name))
  }

  Then("the blueprint {string} has one blueprint version") { (name: String) =>
    assertEquals(blueprints.versions(named(name)).map(_.number), Vector(1))
  }

  Then("the service is given blueprint version {int}") { (version: Int) =>
    assertEquals(last.map(_.version), Some(version))
    assertEquals(last.map(_.isNew), Some(false))
  }

  Then("the caller is refused")(() => assert(refused))

  Then(
    "the reader is given blueprint versions 1, 2 and 3 in that order, each with when it was registered"
  ) { () =>
    assertEquals(listed.map(_.number), Vector(1, 2, 3))
    assert(listed.forall(_.registeredAt > 0L))
    assertEquals(listed.map(_.registeredAt), listed.map(_.registeredAt).sorted)
    assertEquals(listed.map(_.digest).distinct.size, 3)
  }
