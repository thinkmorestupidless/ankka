package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.agent.judgment.{Question, TestJudgmentProvider}
import com.thinkmorestupidless.ankka.agent.judgment.Answers
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import Scripted.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `features/blueprints/patterns.feature`: every shape of step, against a real journal with a
 * scripted model and a scripted judgment provider. Each scenario builds the blueprint its Givens
 * describe, named for the scenario, and `a run reaches the step` registers it, starts a run and
 * waits for it to end.
 */
class PatternFeatures
    extends GherkinSuite("../../features/blueprints/patterns.feature")
    with LogCapturing:

  override val munitTimeout = 8.minutes

  // ── The fixture ───────────────────────────────────────────────────────────

  private val model = TestModelProvider()
  private val judge = TestJudgmentProvider()

  private val relevant = Question.yesNo("relevant", "Is the paper relevant?")
  private val novelty =
    Question.score("novelty", "How new is it?")("old hat", "fresh", "unheard of")
  private val namesPaper = Question.yesNo("names a paper", "Does every statement name a paper?")

  private val noted                              = ConcurrentLinkedQueue[String]()
  private val inFlight                           = AtomicInteger(0)
  private val maxFlight                          = AtomicInteger(0)
  @volatile private var listItems: Vector[Json]  = Vector.empty
  @volatile private var selected: Vector[String] = Vector.empty
  private val handed                             = ConcurrentLinkedQueue[(RunRef, Json)]()

  private val note = FunctionTool.named("note").describedAs("Notes something.").handle { () =>
    noted.add("note"): Unit; "noted"
  }
  private val count =
    FunctionTool.named("count").describedAs("Counts itself in and out.").handle { () =>
      val now = inFlight.incrementAndGet()
      maxFlight.accumulateAndGet(now, math.max): Unit
      Thread.sleep(300)
      inFlight.decrementAndGet(): Unit
      "counted"
    }
  private val list   = BlueprintHandler("list")((_, _) => Json.Arr(listItems))
  private val select = BlueprintHandler("select")((_, _) => Json.Arr(selected.map(Json.str)))
  private val keepPaper = BlueprintHandler("keep_paper") { (ref, input) =>
    handed.add(ref -> input): Unit
    Json.obj("kept" -> Json.num(input("papers").flatMap(_.asArray).map(_.size).getOrElse(0)))
  }

  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withJudgments(judge)
    .withBlueprints(_ =>
      BlueprintRegistry.empty
        .tools(note, count)
        .questions(relevant, novelty, namesPaper)
        .handlers(list, select, keepPaper)
    )

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents, ProjectionRuntime()))

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    judge.reset()
    noted.clear()
    handed.clear()
    inFlight.set(0)
    maxFlight.set(0)
    listItems = Vector.empty
    selected = Vector.empty
    // Named when registered: a scenario's id is not known before it runs.
    pending = Blueprint("pending").input(Shape.obj("topic" -> Shape.string))
    completeAt = None
    refuseItem = None
    verdicts = Vector.empty
    done = None

  private def blueprints = agents.blueprints
  private def runs       = agents.runs
  private def name       = s"$scenarioId-bp"
  private def runId      = s"$scenarioId-run"

  private val text  = Shape.obj("text" -> Shape.string)
  private val input = Json.obj("topic" -> Json.str("ducks"))

  private var pending: Blueprint         = Blueprint("x")
  private var completeAt: Option[Int]    = None
  private var refuseItem: Option[String] = None
  private var verdicts: Vector[Either[String, Unit]] =
    Vector.empty // Left(reason) returns, Right passes
  private var done: Option[RunSnapshot] = None

  private def worker(n: String, budget: Int = 4, tools: Seq[String] = Seq("note")): Worker =
    Worker(n).instructions(s"You are $n.").tools(tools*).budget(budget)

  private def items(n: Int): Vector[Json] = (1 to n).map(i => Json.str(s"i$i")).toVector

  /** The step the scenario is about is always called `s`; a list before it `list`. */
  private def withList(n: Int): Unit =
    listItems = items(n)
    pending = pending.step(Step("list").call("list").reads("input").result(Shape.arr(Shape.string)))

  private def reach(): RunSnapshot =
    blueprints.register(pending.copy(name = name)): Unit
    runs.start(name, input, runId): Unit
    val run = runs.await(runId, 120.seconds)
    done = Some(run)
    run

  private def result: Json =
    done.flatMap(_.stepNamed("s")).flatMap(_.result).getOrElse(fail(s"no result for 's': $done"))
  private def requestsFor(step: String) = model.requests.filter(forStep(_, step))

  // ── The scripted model: standing rules for every shape ─────────────────────

  private def script(): Unit =
    // An ask step: a tool, then an answer.
    model.respondWhen(r => forStep(r, "s") && isAsk(r) && !afterTool(r))(_ => call("note"))
    model.respondWhen(r => forStep(r, "s") && isAsk(r) && afterTool(r))(_ =>
      answer("""{"text":"answer"}""")
    )
    // A work step: iterations of a note, then completion, or never.
    model.respondWhen(r => forStep(r, "s") && isTask(r)) { r =>
      completeAt match
        case Some(n) if toolResults(r) >= n - 1 =>
          completeTask(Json.obj("text" -> Json.str("found")))
        case _ => call("note")
    }
    // A for-each item: the item's name back, or a refusal on the one the scenario names.
    model.respondWhen(r => forStep(r, "each") && !isTask(r)) { r =>
      val item = itemOf(r).getOrElse("?")
      if refuseItem.contains(item) then refusal("cannot read this one")
      else if r.tools.exists(_.name == "count") && !afterTool(r) then call("count")
      else answer(s"""{"text":"got $item"}""")
    }
    model.respondWhen(r => forStep(r, "dig") && isTask(r)) { r =>
      completeTask(Json.obj("text" -> Json.str(s"dug ${itemOf(r).getOrElse("?")}")))
    }
    // A gather: every worker answers with its own name.
    model.respondWhen(r => forStep(r, "g")) { r =>
      val who = r.systemMessage.flatMap(_.linesIterator.nextOption()).getOrElse("")
      answer(s"""{"text":"${who.stripPrefix("You are ").stripSuffix(".")}"}""")
    }
    // A critique: the writer drafts, numbered; the editor returns or passes as the scenario says.
    model.respondWhen(r => forStep(r, "c") && !isVerdict(r)) { _ =>
      val n = model.requests.count(x => forStep(x, "c") && !isVerdict(x))
      answer(s"""{"text":"draft $n"}""")
    }
    model.respondWhen(r => isVerdict(r)) { _ =>
      val n = model.requests.count(isVerdict)
      verdicts.lift(n - 1) match
        case Some(Left(reason)) => answer(s"""{"passed":false,"reasons":["$reason"]}""")
        case _                  => answer("""{"passed":true,"reasons":[]}""")
    }
    model.respondWhen(r => forStep(r, "after"))(_ => answer("""{"text":"after"}""")): Unit

  private def isTask(r: ModelRequest): Boolean    = r.tools.exists(_.name == "complete_task")
  private def isAsk(r: ModelRequest): Boolean     = !isTask(r)
  private def isVerdict(r: ModelRequest): Boolean = userText(r).exists(_.startsWith("Verdict on"))

  // ── Given ───────────────────────────────────────────────────────────────

  Given("a service with a scripted model and a scripted judgment provider")(() => script())

  Given("an ask step with the worker {string}") { (w: String) =>
    pending = pending.worker(worker(w)).step(Step("s").ask(w).reads("input").result(text))
  }

  Given("a work step with the worker {string} and a budget of {string}") {
    (w: String, budget: String) =>
      pending =
        pending.worker(worker(w, budget.toInt)).step(Step("s").work(w).reads("input").result(text))
  }

  Given("a for-each step with the worker {string} over a list of five items") { (w: String) =>
    withList(5)
    pending =
      pending.worker(worker(w)).step(Step("each").forEach(w, over = "list").result(Shape.arr(text)))
  }

  Given("a for-each step with the worker {string} over an empty list") { (w: String) =>
    withList(0)
    pending = pending
      .worker(worker(w))
      .step(Step("each").forEach(w, over = "list").result(Shape.arr(text)))
      .step(Step("after").ask(w).reads("input").result(text))
  }

  Given(
    "a for-each step with the worker {string} over a list of twelve items and a limit of {string}"
  ) { (w: String, limit: String) =>
    withList(12)
    pending = pending
      .worker(worker(w, tools = Seq("count")))
      .step(Step("each").forEach(w, over = "list", limit = limit.toInt).result(Shape.arr(text)))
  }

  private def fiveItems(w: String, keepGoing: Boolean): Unit =
    withList(5)
    pending = pending
      .worker(worker(w))
      .step(Step("each").forEach(w, over = "list", keepGoing = keepGoing).result(Shape.arr(text)))

  Given(
    "a for-each step with the worker {string} over a list of five items, which does not keep going"
  ) { (w: String) =>
    fiveItems(w, keepGoing = false)
  }
  Given("a for-each step with the worker {string} over a list of five items, which keeps going") {
    (w: String) =>
      fiveItems(w, keepGoing = true)
  }

  Given("a gather step with the workers {string}, {string} and {string}") {
    (a: String, b: String, c: String) =>
      pending = Vector(a, b, c)
        .foldLeft(pending)((bp, w) => bp.worker(worker(w)))
        .step(
          Step("g")
            .gather(a, b, c)
            .reads("input")
            .result(Shape.arr(Shape.obj("worker" -> Shape.string, "result" -> text)))
        )
  }

  Given(
    "a gather step with the workers {string}, {string} and {string}, chosen by the result of the step {string}"
  ) { (a: String, b: String, c: String, chooser: String) =>
    pending = Vector(a, b, c)
      .foldLeft(pending)((bp, w) => bp.worker(worker(w)))
      .step(Step(chooser).call("select").reads("input").result(Shape.arr(Shape.string)))
      .step(
        Step("g")
          .gather(Seq(a, b, c), chosenBy = chooser)
          .reads("input")
          .result(Shape.arr(Shape.obj("worker" -> Shape.string, "result" -> text)))
      )
  }

  Given("the step {string} has given the list {string} and {string}") {
    (_: String, a: String, b: String) => selected = Vector(a, b)
  }

  Given("a judge step asking the judgment questions {string} and {string}") {
    (a: String, b: String) =>
      judge.always(Answers.yesNo(relevant, 0.8), Answers.score(novelty, 2.0)): Unit
      pending =
        pending.step(Step("s").judge(a, b).reads("input").result(Shape.obj(Seq.empty, Seq.empty)))
  }

  Given(
    "a critique step with the drafting worker {string}, the critic {string} and {string} rounds"
  ) { (w: String, critic: String, rounds: String) =>
    pending = pending
      .worker(worker(w))
      .worker(worker(critic))
      .step(Step("c").critique(w, Verdict.critic(critic), rounds.toInt).reads("input").result(text))
  }

  Given(
    "a critique step with the drafting worker {string}, the judgment question {string} as its verdict and {string} rounds"
  ) { (w: String, q: String, rounds: String) =>
    pending = pending
      .worker(worker(w))
      .step(Step("c").critique(w, Verdict.judgment(q), rounds.toInt).reads("input").result(text))
  }

  Given(
    "a critique step with the drafting worker {string}, the critic {string}, {string} rounds and the last draft kept"
  ) { (w: String, critic: String, rounds: String) =>
    pending = pending
      .worker(worker(w))
      .worker(worker(critic))
      .step(
        Step("c")
          .critique(w, Verdict.critic(critic), rounds.toInt, keepLast = true)
          .reads("input")
          .result(text)
      )
  }

  Given("a call step calling the handler {string} and reading the step {string}") {
    (handler: String, papers: String) =>
      listItems = Vector(Json.str("p1"), Json.str("p2"))
      pending = pending
        .step(Step(papers).call("list").reads("input").result(Shape.arr(Shape.string)))
        .step(Step("s").call(handler).reads(papers).result(Shape.obj("kept" -> Shape.integer)))
  }

  Given(
    "a for-each step whose action is a work task for the worker {string}, over a list of three items"
  ) { (w: String) =>
    withList(3)
    pending =
      pending.worker(worker(w)).step(Step("dig").work(w).each("list").result(Shape.arr(text)))
  }

  Given("a gather step with the workers {string} and {string}") { (a: String, b: String) =>
    pending = pending
      .worker(worker(a))
      .worker(worker(b))
      .step(
        Step("g")
          .gather(a, b)
          .reads("input")
          .result(Shape.arr(Shape.obj("worker" -> Shape.string, "result" -> text)))
      )
  }

  // ── When ────────────────────────────────────────────────────────────────

  When("a run reaches the step")(() => reach(): Unit)
  When("a run reaches the gather step")(() => reach(): Unit)

  When("a run reaches the step and the model completes it on its third iteration") { () =>
    completeAt = Some(3)
    reach(): Unit
  }

  When("the model has not completed the step after three iterations")(() => reach(): Unit)

  When("the worker gives up on the third item") { () =>
    refuseItem = Some("i3")
    reach(): Unit
  }

  When("{string} returns the first draft with the reason {string} and passes the second") {
    (_: String, reason: String) =>
      verdicts = Vector(Left(reason), Right(()))
      reach(): Unit
  }

  When("the judgment answers {string} for the first draft and {string} for the second") {
    (first: String, second: String) =>
      def p(word: String) = if word == "yes" then 0.9 else 0.1
      judge
        .expect(Answers.yesNo(namesPaper, p(first)))
        .expect(Answers.yesNo(namesPaper, p(second))): Unit
      reach(): Unit
  }

  When("{string} returns both drafts") { (_: String) =>
    verdicts = Vector(Left("too vague"), Left("still vague"))
    reach(): Unit
  }

  // ── Then ────────────────────────────────────────────────────────────────

  Then("{string} answers the step's input once, running the tools its model asks for") {
    (_: String) =>
      assertEquals(noted.size, 1)
      assertEquals(requestsFor("s").size, 2)
  }
  Then("the step's result is the answer") { () =>
    assertEquals(result, Json.obj("text" -> Json.str("answer")))
  }

  Then("the step's result is the result the model completed it with") { () =>
    assertEquals(done.map(_.status), Some(RunStatus.Completed), done.flatMap(_.reason).toString)
    assertEquals(result, Json.obj("text" -> Json.str("found")))
  }
  Then("three iterations are recorded") { () =>
    assertEquals(done.flatMap(_.stepNamed("s")).map(_.modelCalls), Some(3))
  }

  Then("the step is failed, saying the budget is spent") { () =>
    assertEquals(done.map(_.status), Some(RunStatus.Failed))
    assert(
      done.flatMap(_.reason).exists(r => r.startsWith("s:") && r.contains("budget")),
      done.flatMap(_.reason).toString
    )
  }

  Then("{string} is given each of the five items") { (_: String) =>
    assertEquals(requestsFor("each").flatMap(itemOf).toSet, items(5).flatMap(_.asString).toSet)
  }
  Then("the step's result is the five results in the list's order") { () =>
    assertEquals(
      done.flatMap(_.stepNamed("each")).flatMap(_.result),
      Some(Json.Arr(items(5).map(i => Json.obj("text" -> Json.str(s"got ${i.asString.get}")))))
    )
  }
  Then("the step's result is an empty list") { () =>
    assertEquals(done.flatMap(_.stepNamed("each")).flatMap(_.result), Some(Json.Arr(Vector.empty)))
  }
  Then("the model is not called for the step")(() => assertEquals(requestsFor("each").size, 0))
  Then("the next step is carried out") { () =>
    assertEquals(requestsFor("after").size, 1)
    assertEquals(done.map(_.status), Some(RunStatus.Completed))
  }
  Then("no more than four items are worked at once") { () =>
    assert(maxFlight.get() <= 4 && maxFlight.get() >= 2, maxFlight.get().toString)
  }
  Then("every item is worked") { () =>
    assertEquals(
      done.flatMap(_.stepNamed("each")).flatMap(_.result).flatMap(_.asArray).map(_.size),
      Some(12)
    )
  }

  Then("the step is failed, naming the third item") { () =>
    assertEquals(done.map(_.status), Some(RunStatus.Failed))
    assert(done.flatMap(_.reason).exists(_.contains("item 3")), done.flatMap(_.reason).toString)
  }
  Then("the step's result holds four results and marks the third item failed") { () =>
    val step   = done.flatMap(_.stepNamed("each")).getOrElse(fail("no step"))
    val values = step.result.flatMap(_.asArray).getOrElse(fail("no result"))
    assertEquals(values.size, 5)
    assertEquals(values.count(_ != Json.Null), 4)
    assertEquals(values(2), Json.Null)
    assert(step.items.exists(i => i.index == 2 && i.failure.isDefined), step.items.toString)
  }

  Then("each of the three workers is given the step's input") { () =>
    val systems = requestsFor("g").flatMap(_.systemMessage).map(_.linesIterator.next())
    assertEquals(systems.toSet.size, 3)
    assert(requestsFor("g").flatMap(userText).forall(_.contains("ducks")))
  }
  Then("the step's result holds three results, each with the worker that gave it") { () =>
    val values = done
      .flatMap(_.stepNamed("g"))
      .flatMap(_.result)
      .flatMap(_.asArray)
      .getOrElse(fail("no result"))
    assertEquals(values.size, 3)
    values.foreach(v =>
      assertEquals(
        v("result").flatMap(_("text")).flatMap(_.asString),
        v("worker").flatMap(_.asString)
      )
    )
  }
  Then("{string} and {string} are given the step's input") { (a: String, b: String) =>
    val who = requestsFor("g")
      .flatMap(_.systemMessage)
      .map(_.linesIterator.next().stripPrefix("You are ").stripSuffix("."))
    assertEquals(who.toSet, Set(a, b))
  }
  Then("{string} is not called") { (w: String) =>
    assert(!requestsFor("g").flatMap(_.systemMessage).exists(_.startsWith(s"You are $w.")))
  }
  Then("the step's result holds two results, each with the worker that gave it") { () =>
    val values = done
      .flatMap(_.stepNamed("g"))
      .flatMap(_.result)
      .flatMap(_.asArray)
      .getOrElse(fail("no result"))
    assertEquals(values.flatMap(_("worker")).flatMap(_.asString), Vector("weather", "budget"))
  }

  Then("the step's result holds the answer to each question with the probabilities behind it") {
    () =>
      assertEquals(done.map(_.status), Some(RunStatus.Completed), done.flatMap(_.reason).toString)
      assertEquals(result("relevant").flatMap(_("answer")).flatMap(_.asString), Some("yes"))
      assertEquals(result("relevant").flatMap(_("probability")).flatMap(_.asDouble), Some(0.8))
      assertEquals(result("novelty").flatMap(_("score")).flatMap(_.asDouble), Some(2.0))
      assert(result("novelty").flatMap(_("probabilities")).flatMap(_.asArray).exists(_.size == 3))
  }

  Then("{string} is given the reason {string} before its second draft") {
    (_: String, reason: String) =>
      val drafts = model.requests.filter(r => forStep(r, "c") && !isVerdict(r))
      assertEquals(drafts.size, 2)
      assert(drafts(1).messages.exists {
        case ChatMessage.User(content) =>
          content.exists { case MessageContent.Text(t) => t.contains(reason); case _ => false }
        case _ => false
      })
  }
  Then("the step's result is the second draft") { () =>
    assertEquals(done.map(_.status), Some(RunStatus.Completed), done.flatMap(_.reason).toString)
    assertEquals(
      done.flatMap(_.stepNamed("c")).flatMap(_.result),
      Some(Json.obj("text" -> Json.str("draft 2")))
    )
  }
  Then("the step is failed, with the reasons {string} gave for the last draft") { (_: String) =>
    assertEquals(done.map(_.status), Some(RunStatus.Failed))
    assert(
      done.flatMap(_.reason).exists(_.contains("still vague")),
      done.flatMap(_.reason).toString
    )
  }
  Then(
    "the step's result is the second draft, marked as not passed, with the reasons {string} gave"
  ) { (_: String) =>
    assertEquals(
      done.flatMap(_.stepNamed("c")).flatMap(_.result),
      Some(
        Json.obj(
          "draft"   -> Json.obj("text" -> Json.str("draft 2")),
          "passed"  -> Json.bool(false),
          "reasons" -> Json.arr(Json.str("still vague"))
        )
      )
    )
  }

  Then(
    "the handler {string} is given the result of {string} and told the run, the step and the version"
  ) { (_: String, papers: String) =>
    val (ref, given0) = handed.asScala.headOption.getOrElse(fail("the handler was not called"))
    assertEquals(given0(papers).flatMap(_.asArray).map(_.size), Some(2))
    assertEquals(ref, RunRef(runId, "s", name, 1))
  }
  Then("the step's result is what the handler returned") { () =>
    assertEquals(result, Json.obj("kept" -> Json.num(2)))
  }

  Then("{string} works a task for each of the three items") { (_: String) =>
    assertEquals(requestsFor("dig").size, 3)
    assert(requestsFor("dig").forall(isTask))
  }
  Then("the step's result is the three results in the list's order") { () =>
    assertEquals(done.map(_.status), Some(RunStatus.Completed), done.flatMap(_.reason).toString)
    assertEquals(
      done.flatMap(_.stepNamed("dig")).flatMap(_.result),
      Some(Json.Arr(items(3).map(i => Json.obj("text" -> Json.str(s"dug ${i.asString.get}")))))
    )
  }

  Then("{string} and {string} each have a session of their own") { (a: String, b: String) =>
    assertEquals(
      done.flatMap(_.stepNamed("g")).map(_.sessions.toSet),
      Some(Set(s"run:$runId:g:$a", s"run:$runId:g:$b"))
    )
  }
  Then("neither is shown what the other said") { () =>
    assert(requestsFor("g").forall(_.messages.size == 1))
  }
