package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.FunctionTool
import com.thinkmorestupidless.ankka.agent.judgment.Question

import java.time.{DayOfWeek, LocalTime, ZoneId}

/**
 * SC-003: a blueprint with N problems is refused with exactly N, for every N up to the number of
 * checks. Each problem below is independent of the others, so adding one adds one.
 */
class BlueprintCheckSuite extends munit.FunSuite:

  private val search = FunctionTool.named("search").describedAs("Searches.").handle(() => "[]")
  private val registry = BlueprintRegistry.empty
    .tools(search)
    .questions(
      Question.yesNo("ok", "Is it fine?"),
      Question.score("mood", "How is it?")("low", "high")
    )
    .withTimers(false)

  private def ok(name: String) =
    Worker(name).instructions("Do the thing.").tools("search").budget(3)

  private val sound =
    Blueprint("sound").worker(ok("w")).step(Step("one").ask("w").reads("input"))

  /** Each adds exactly one problem to a sound blueprint, and nothing that would add another. */
  private val problems: Vector[(String, Blueprint => Blueprint)] = Vector(
    "tool"         -> (_.worker(ok("t").tools("translate")).step(Step("st").ask("t"))),
    "model"        -> (_.worker(ok("m").model("large")).step(Step("sm").ask("m"))),
    "guardrail"    -> (_.worker(ok("g").guardrails("polite")).step(Step("sg").ask("g"))),
    "instructions" -> (_.worker(ok("i").instructions(" ")).step(Step("si").ask("i"))),
    "budget"       -> (_.worker(ok("b").budget(0)).step(Step("sb").ask("b"))),
    "read"         -> (_.step(Step("sr").ask("w").reads("nowhere"))),
    "read-order"   -> (_.step(Step("so").ask("w").reads("later")).step(Step("later").ask("w"))),
    "list"         -> (_.step(Step("sl").forEach("w", over = "one"))),
    "chosen-by"    -> (_.step(Step("sc").gather(Seq("w", "w"), chosenBy = "one"))),
    "judgment-question" -> (_.step(Step("sj").judge("funny"))),
    "rounds"            -> (_.step(Step("sk").critique("w", Verdict.judgment("ok"), rounds = 0))),
    "verdict"           -> (_.step(Step("sv").critique("w", Verdict.judgment("mood"), rounds = 1))),
    "worker"            -> (_.step(Step("sw").ask("nobody"))),
    "reserved-name"     -> (_.step(Step("input").ask("w"))),
    "zone"              -> (_.schedule(Schedule(Cadence.EveryDays(1), "Mars/Olympus"))),
    "run-budget"        -> (_.runBudget(0))
  )

  test("SC-003 a blueprint with N problems is refused with exactly N, for every N") {
    problems.indices.foreach { n =>
      val blueprint  = problems.take(n + 1).foldLeft(sound)((bp, add) => add._2(bp))
      val (found, _) = BlueprintCheck.check(blueprint, registry)
      val expected   = problems.take(n + 1).map(_._1)
      // The zone problem brings the timers problem with it: a schedule in a service without timers.
      val withTimers = if expected.contains("zone") then expected :+ "timers" else expected
      assertEquals(
        found.map(_.rule).sorted,
        withTimers.sorted,
        s"N=${n + 1}:\n${found.mkString("\n")}"
      )
    }
  }

  test("a sound blueprint has no problems and notes the worker no step uses") {
    val (found, notes) = BlueprintCheck.check(sound.worker(ok("spare")), registry)
    assertEquals(found, Vector.empty)
    assertEquals(notes.map(_.message), Vector("no step uses the worker 'spare'"))
  }

  test("a schedule in a service with timers is held") {
    val scheduled = sound.schedule(
      Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), ZoneId.of("Europe/London"))
    )
    assertEquals(BlueprintCheck.check(scheduled, registry.withTimers(true))._1, Vector.empty)
    assertEquals(BlueprintCheck.check(scheduled, registry)._1.map(_.rule), Vector("timers"))
  }

  test("a pattern the platform does not have is found by the step that names it") {
    val json =
      s"""{"name":"vote","input":{"type":"object","properties":{},"required":[]},"workers":[],
         |"steps":[{"name":"decide","pattern":{"type":"Vote","workers":["w"]},"reads":[],"result":{"type":"string"}}]}""".stripMargin
    assertEquals(
      BlueprintCheck.fromJson(json, registry).left.map(_.map(p => (p.path, p.rule))),
      Left(Vector(("steps[0].pattern", "pattern")))
    )
    assert(
      BlueprintCheck
        .fromJson(json, registry)
        .left
        .exists(_.head.message.contains("step 'decide' uses the pattern 'Vote'"))
    )
  }
