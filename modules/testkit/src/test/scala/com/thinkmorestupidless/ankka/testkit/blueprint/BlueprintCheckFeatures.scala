package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, FunctionTool, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.agent.judgment.Question
import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.time.{DayOfWeek, LocalTime, ZoneId}
import scala.concurrent.duration.DurationInt

/**
 * `features/blueprints/checking.feature`, against a real service, so that "nothing is held" is read
 * from the journal and not assumed. The service has no timers: a schedule is refused here.
 */
class BlueprintCheckFeatures
    extends GherkinSuite("../../features/blueprints/checking.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private val model = TestModelProvider()
  private val search =
    FunctionTool.named("search").describedAs("Searches a source.").handle(() => "[]")
  private val keep =
    FunctionTool.named("keep_entry").describedAs("Keeps an entry.").handle(() => "kept")
  private val names = Question.yesNo("names-a-paper", "Does every statement name a paper?")

  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withBlueprints(_ => BlueprintRegistry.empty.tools(search, keep).questions(names))

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents))
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def blueprints          = agents.blueprints
  private def named(name: String) = s"$scenarioId-$name"

  /** What the last registration gave: the problems, or what was registered. */
  private var outcome: Either[Vector[Problem], Registered] = Left(Vector.empty)
  private var name                                         = ""

  private def register(bp: Blueprint): Unit =
    name = bp.name
    outcome =
      try Right(blueprints.register(bp))
      catch
        case e: CommandError =>
          Left(BlueprintRefusal.problemsOf(e).getOrElse(fail(s"not a refusal: ${e.getMessage}")))

  private def registerJson(json: String, bpName: String): Unit =
    name = bpName
    outcome =
      try Right(blueprints.register(json))
      catch
        case e: CommandError =>
          Left(BlueprintRefusal.problemsOf(e).getOrElse(fail(s"not a refusal: ${e.getMessage}")))

  private def problems: Vector[Problem] =
    outcome.left.getOrElse(fail(s"held, not refused: $outcome"))

  private def worker(name: String = "searcher") =
    Worker(name).instructions("Search the sources.").tools("search").budget(4)

  private def sound(name: String) =
    Blueprint(named(name)).worker(worker()).step(Step("search").ask("searcher").reads("input"))

  // ── Given ───────────────────────────────────────────────────────────────

  Given(
    "a service with the tools {string} and {string}, the model {string} and the judgment question {string}"
  ) { (a: String, b: String, m: String, q: String) =>
    assertEquals(Set(a, b), Set("search", "keep_entry"))
    assertEquals(m, Worker.DefaultModel)
    assertEquals(q, "names-a-paper")
  }

  Given("a service without timers")(() => assert(!blueprints.registry.hasTimers))

  // ── When ────────────────────────────────────────────────────────────────

  When("the service registers a blueprint with four problems") { () =>
    register(
      Blueprint(named("four"))
        .worker(
          Worker("a").instructions("Translate.").tools("translate").budget(2)
        )                                                                    // 1: no such tool
        .worker(Worker("b").instructions("Think.").model("large").budget(2)) // 2: no such model
        .worker(Worker("c").instructions("Try.").budget(0))                  // 3: no budget
        .step(Step("first").ask("a").reads("second")) // 4: reads a later step
        .step(Step("second").ask("b"))
        .step(Step("third").ask("c"))
    )
  }

  When("the service registers a blueprint with a worker whose tools include {string}") {
    (tool: String) =>
      register(
        Blueprint(named("tool")).worker(worker().tools(tool)).step(Step("search").ask("searcher"))
      )
  }

  When("the service registers a blueprint with a worker whose model is {string}") { (m: String) =>
    register(
      Blueprint(named("model")).worker(worker().model(m)).step(Step("search").ask("searcher"))
    )
  }

  When("the service registers a blueprint with a judge step asking the judgment question {string}") {
    (q: String) =>
      register(Blueprint(named("judge")).step(Step("funny-or-not").judge(q)))
  }

  When("the service registers a blueprint whose first step reads the result of its second step") {
    () =>
      register(
        Blueprint(named("order"))
          .worker(worker())
          .step(Step("first").ask("searcher").reads("second"))
          .step(Step("second").ask("searcher"))
      )
  }

  When("the service registers a blueprint with a step whose pattern is {string}") {
    (pattern: String) =>
      registerJson(
        s"""{"name":"${named("vote")}","input":{"type":"object","properties":{},"required":[]},
         |"workers":[{"name":"searcher","instructions":"Search.","model":"default","tools":["search"],"guardrails":[],"budget":4}],
         |"steps":[{"name":"decide","pattern":{"type":"$pattern","workers":["searcher"]},"reads":[],"result":{"type":"string"}}]}""".stripMargin,
        named("vote")
      )
  }

  When(
    "the service registers a blueprint whose for-each step reads a step whose result is not a list"
  ) { () =>
    register(
      Blueprint(named("list"))
        .worker(worker())
        .step(Step("summary").ask("searcher").result(Shape.string))
        .step(Step("each").forEach("searcher", over = "summary"))
    )
  }

  When("the service registers a blueprint with a worker that has no budget") { () =>
    register(
      Blueprint(named("budget")).worker(worker().budget(0)).step(Step("search").ask("searcher"))
    )
  }

  When("the service registers a blueprint with a schedule") { () =>
    register(
      sound("scheduled").schedule(
        Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), ZoneId.of("Europe/London"))
      )
    )
  }

  When("the service registers a blueprint with a worker that no step uses") { () =>
    register(sound("spare").worker(worker("idle")))
  }

  // ── Then ────────────────────────────────────────────────────────────────

  private def refusedNaming(words: String*): Unit =
    words.foreach(w =>
      assert(
        problems.exists(_.message.contains(w)),
        s"no problem names '$w':\n${problems.mkString("\n")}"
      )
    )

  Then("the service is refused, with all four problems named") { () =>
    assertEquals(problems.size, 4, problems.mkString("\n"))
    refusedNaming(
      "'translate'",
      "'large'",
      "'c' needs a budget",
      "'first' reads the result of 'second'"
    )
  }

  Then("no blueprint version is held")(() => assertEquals(blueprints.versions(name), Vector.empty))

  Then("the service is refused, naming the worker and the tool {string}") { (tool: String) =>
    refusedNaming("worker 'searcher'", s"tool '$tool'")
  }

  Then("the service is refused, naming the worker and the model {string}") { (m: String) =>
    refusedNaming("worker 'searcher'", s"model '$m'")
  }

  Then("the service is refused, naming the step and the judgment question {string}") { (q: String) =>
    refusedNaming("step 'funny-or-not'", s"judgment question '$q'")
  }

  Then("the service is refused, naming both steps")(() => refusedNaming("step 'first'", "'second'"))

  Then("the service is refused, naming the step and the pattern {string}") { (pattern: String) =>
    refusedNaming("step 'decide'", s"pattern '$pattern'")
  }

  Then("the service is refused, naming the for-each step and the step it reads") { () =>
    refusedNaming("for-each step 'each'", "'summary'")
  }

  Then("the service is refused, naming the worker")(() => refusedNaming("worker 'searcher'"))

  Then("the service is refused, naming the schedule and the missing timers") { () =>
    assert(
      problems.exists(p => p.path == "schedule" && p.message.contains("timers")),
      problems.mkString("\n")
    )
  }

  Then("the blueprint is held")(() => assert(outcome.isRight, outcome.toString))

  Then("the service is told that no step uses the worker") { () =>
    assert(outcome.exists(_.notes.exists(_.message.contains("'idle'"))), outcome.toString)
  }
