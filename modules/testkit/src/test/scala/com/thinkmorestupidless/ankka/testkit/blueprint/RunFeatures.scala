package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, SessionId}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * `features/blueprints/runs.feature`, against a real journal with a scripted model. The blueprint
 * of the background, `brief`, has three ask steps; what a scenario's Givens add (a tool that waits
 * for approval, a budget, a shape) shapes the blueprint that scenario registers.
 */
class RunFeatures extends GherkinSuite("../../features/blueprints/runs.feature") with LogCapturing:

  override val munitTimeout = 6.minutes

  // ── The fixture ───────────────────────────────────────────────────────────

  private val model                 = TestModelProvider()
  private val noted                 = ConcurrentLinkedQueue[String]()
  @volatile private var gate        = CountDownLatch(0)
  @volatile private var gateReached = CountDownLatch(1)
  private val approved              = AtomicInteger(0)

  private val note = FunctionTool
    .named("note")
    .describedAs("Notes something.")
    .param[String]("text", "What to note.")
    .handle { (text: String) =>
      noted.add(text): Unit; "noted"
    }
  private val gateTool =
    FunctionTool.named("gate").describedAs("Waits for the test to let it through.").handle { () =>
      gateReached.countDown()
      gate.await(60, TimeUnit.SECONDS): Unit
      "through"
    }
  private val approveMe = FunctionTool
    .named("approve_me")
    .describedAs("Does something a person must approve.")
    .handle { () =>
      approved.incrementAndGet(); "done"
    }
    .requiresApproval

  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withBlueprints(_ => BlueprintRegistry.empty.tools(note, gateTool, approveMe))

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(AgentRuntime.descriptors, Seq(agents, ProjectionRuntime()))

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    noted.clear()
    approved.set(0)
    gate = CountDownLatch(0)
    gateReached = CountDownLatch(1)
    outlineShape = Shape.obj("text" -> Shape.string)
    polishApproves = false
    draftTwoCalls = false
    gateDraft = false
    draftRefusesAfterGate = false
    runBudget = None
    timeLimit = None
    registered = false
    failed = None
    waiter = None
    script()

  private def blueprints       = agents.blueprints
  private def runs             = agents.runs
  private def named(s: String) = s"$scenarioId-$s"
  private def name             = named("brief")
  private def runId            = named("run")

  // What a scenario's Givens may change before the blueprint is registered.
  private var outlineShape: Shape                   = Shape.obj("text" -> Shape.string)
  private var polishApproves                        = false
  private var draftTwoCalls                         = false
  private var gateDraft                             = false
  private var draftRefusesAfterGate                 = false
  private var runBudget: Option[Int]                = None
  private var timeLimit: Option[FiniteDuration]     = None
  private var registered                            = false
  private var failed: Option[CommandError]          = None
  private var waiter: Option[Thread]                = None
  @volatile private var waited: Option[RunSnapshot] = None

  private val text = Shape.obj("text" -> Shape.string)

  private def brief(instructions: String = "Write briefly."): Blueprint =
    val writer = Worker("writer").instructions(instructions).tools("note", "gate").budget(4)
    val polisher =
      Worker("polisher").instructions("Polish, asking first.").tools("approve_me").budget(4)
    val base = Blueprint(name)
      .input(Shape.obj("topic" -> Shape.string))
      .worker(writer)
      .step(Step("outline").ask("writer").reads("input").result(outlineShape))
      .step(Step("draft").ask("writer").reads("outline").result(text))
    val withPolish =
      if polishApproves then
        base
          .worker(polisher)
          .step(Step("polish").ask("polisher").reads("input", "draft").result(text))
      else base.step(Step("polish").ask("writer").reads("input", "draft").result(text))
    val budgeted = runBudget.fold(withPolish)(withPolish.runBudget)
    timeLimit.fold(budgeted)(budgeted.timeLimit)

  private def ensureRegistered(): Unit =
    if !registered then
      blueprints.register(brief()): Unit
      registered = true

  private val input = Json.obj("topic" -> Json.str("ducks"))

  private def startRun(): RunSnapshot =
    ensureRegistered()
    try runs.start(name, input, runId)
    catch
      case e: CommandError =>
        failed = Some(e)
        throw e

  // ── The scripted model ────────────────────────────────────────────────────

  private def userText(request: ModelRequest): Option[String] =
    request.messages.reverse.collectFirst { case ChatMessage.User(content) =>
      content.collect { case MessageContent.Text(t) => t }.mkString
    }

  private def lastIsToolResult(request: ModelRequest): Boolean =
    request.messages.lastOption.exists {
      case _: ChatMessage.ToolResults => true
      case _                          => false
    }

  private def toolResultsOf(request: ModelRequest): Vector[String] =
    request.messages.collect { case ChatMessage.ToolResults(results) =>
      results.map(_.content)
    }.flatten

  private def answer(json: String) = ModelResponse(json, usage = TokenUsage(10, 5, 0, 0))
  private def call(tool: String, args: Json = Json.obj()) =
    ModelResponse(
      "",
      Vector(ToolCall("c-" + tool + "-" + System.nanoTime(), tool, args)),
      StopReason.ToolUse,
      TokenUsage(10, 5, 0, 0)
    )
  private def refusal(reason: String) =
    ModelResponse("", Vector.empty, StopReason.Refusal, TokenUsage(10, 5, 0, 0), Some(reason))

  private def step(request: ModelRequest, name: String): Boolean =
    userText(request).exists(_.contains(s"Step '$name'"))

  private def script(): Unit =
    model.respondWhen(r => step(r, "outline") && !lastIsToolResult(r)) { _ =>
      if outlineShape.isArray then answer("""["a","b"]""")
      else answer("""{"text":"outline-of-ducks"}""")
    }
    // The draft asks for its gate, and for a note when the scenario wants two calls, then answers.
    model.respondWhen(r => step(r, "draft") && !lastIsToolResult(r)) { _ =>
      if draftTwoCalls then call("note", Json.obj("text" -> Json.str("first")))
      else if gateDraft then call("gate")
      else answer("""{"text":"draft-of-ducks"}""")
    }
    model.respondWhen(r =>
      step(r, "draft") && lastIsToolResult(r) && toolResultsOf(r).size == 1 && draftTwoCalls
    )(_ => call("gate"))
    model.respondWhen(r => step(r, "draft") && lastIsToolResult(r)) { _ =>
      if draftRefusesAfterGate then refusal("nothing to draft from")
      else answer("""{"text":"draft-of-ducks"}""")
    }
    model.respondWhen(r => step(r, "polish") && !lastIsToolResult(r)) { _ =>
      if polishApproves then call("approve_me") else answer("""{"text":"polished-ducks"}""")
    }
    model.whenRequest(r => step(r, "polish") && lastIsToolResult(r))(
      answer("""{"text":"polished-ducks"}""")
    ): Unit

  private def requestsFor(stepName: String): Seq[ModelRequest] =
    model.requests.filter(step(_, stepName))

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def awaitGate(): Unit =
    assert(gateReached.await(60, TimeUnit.SECONDS), "the worker did not reach the gate")

  private def inProgress(): Unit =
    gateDraft = true
    gate = CountDownLatch(1)
    startRun(): Unit
    awaitGate()

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a service with a scripted model")(() => ())

  Given(
    "the blueprint {string} held at blueprint version 1, with the steps {string}, {string} and {string}"
  ) { (_: String, a: String, b: String, c: String) =>
    assertEquals(Vector(a, b, c), Vector("outline", "draft", "polish"))
  }

  Given("the blueprint {string} whose steps {string} and {string} both read only the run's input") {
    (_: String, a: String, b: String) =>
      // Both steps call the gate, so both can be seen in flight before either ends.
      val writer = Worker("writer").instructions("Write briefly.").tools("gate").budget(4)
      val pair = Blueprint(named("pair"))
        .input(Shape.obj("topic" -> Shape.string))
        .worker(writer)
        .step(Step(a).ask("writer").reads("input").result(text))
        .step(Step(b).ask("writer").reads("input").result(text))
      blueprints.register(pair): Unit
      gate = CountDownLatch(1)
      gateReached = CountDownLatch(2)
      model.respondWhen(r => (step(r, a) || step(r, b)) && !lastIsToolResult(r))(_ => call("gate"))
      model.respondWhen(r => (step(r, a) || step(r, b)) && lastIsToolResult(r))(_ =>
        answer("""{"text":"done"}""")
      )
  }

  When("a run of {string} is started") { (name: String) =>
    runs.start(named(name), input, runId): Unit
  }

  Then("the steps {string} and {string} are both in progress at once") { (a: String, b: String) =>
    assert(gateReached.await(60, TimeUnit.SECONDS), "both steps did not reach the gate")
    val run = runs.get(runId)
    assert(run.steps.map(_.name).toSet == Set(a, b) && run.steps.forall(!_.ended), run.toString)
  }

  Given("a run of {string} in progress at blueprint version 1")((_: String) => inProgress())
  Given("a run of {string} in progress")((_: String) => inProgress())

  Given("a run of {string} under the run id {string}")((_: String, _: String) => startRun(): Unit)

  Given("the step {string} reads the run's input and the result of {string}") {
    (s: String, r: String) =>
      assertEquals((s, r), ("polish", "draft")) // the background's blueprint reads so
  }

  Given("a run of {string} whose step {string} has ended and whose step {string} is in progress") {
    (_: String, _: String, _: String) =>
      inProgress()
      eventually("outline has ended")(
        Option.when(runs.get(runId).stepNamed("outline").exists(_.ended))(())
      )
  }

  Given("a completed run of {string}") { (_: String) =>
    startRun(): Unit
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
  }

  Given("a completed run of {string} whose three steps each made model calls") { (_: String) =>
    startRun(): Unit
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
  }

  Given("a caller waiting for the run to end") { () =>
    val t = Thread.ofVirtual().start(() => waited = Some(runs.await(runId, 60.seconds)))
    waiter = Some(t)
  }

  Given("a run of {string} whose worker in {string} has had a model call recorded") {
    (_: String, _: String) =>
      // The outline's turn ended, and is recorded; the draft is in progress at its gate.
      inProgress()
      eventually("outline has ended")(
        Option.when(runs.get(runId).stepNamed("outline").exists(_.ended))(())
      )
  }

  Given(
    "a run of {string} whose worker in {string} has made two model calls in a turn that has not ended"
  ) { (_: String, _: String) =>
    draftTwoCalls = true
    inProgress()
  }

  Given("a failed run of {string}") { (_: String) =>
    draftRefusesAfterGate = true
    inProgress()
    gate.countDown()
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Failed)
  }

  Given("the blueprint {string} with a run budget of {string} model calls") {
    (_: String, n: String) =>
      runBudget = Some(n.toInt)
      draftTwoCalls = true
      gateDraft = true // the gate is open: two calls for the draft, one for the outline
  }

  Given("a run of {string} whose worker in {string} has a turn in progress") {
    (_: String, _: String) => inProgress()
  }

  Given("a run of {string} whose step {string} declares a result holding a list of titles") {
    (_: String, _: String) =>
      outlineShape = Shape.arr(Shape.string)
      model.expect(
        answer("""{"oops":1}""")
      ) // the first answer, before the standing rule gives the right one
  }

  Given("a worker in {string} with a tool that requires approval") { (_: String) =>
    polishApproves = true
  }

  Given("a run of {string} waiting for a decision on a tool call in {string}") {
    (_: String, _: String) =>
      polishApproves = true
      timeLimit = Some(4.seconds)
      model.whenToolResult("refused")(answer("""{"text":"never mind"}"""))
      startRun(): Unit
      eventually("the run waits")(
        Option.when(runs.get(runId).status == RunStatus.WaitingForDecision)(())
      )
  }

  Given("two runs of {string} at blueprint version 1 and one at blueprint version 2") {
    (_: String) =>
      ensureRegistered()
      runs.start(name, input, named("run-1")): Unit
      runs.start(name, input, named("run-2")): Unit
      blueprints.register(brief("Write very briefly.")): Unit
      runs.start(name, input, named("run-3")): Unit
      Vector("run-1", "run-2", "run-3").foreach(r => runs.await(named(r), 60.seconds): Unit)
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a caller starts a run of {string} with an input of its input shape") { (_: String) =>
    startRun(): Unit
  }

  When("the service registers blueprint version 2 of {string}") { (_: String) =>
    assertEquals(blueprints.register(brief("Write at length.")).version, 2)
    gate.countDown()
  }

  When("a caller starts a run of {string} under the run id {string} again") {
    (_: String, _: String) => startRun(): Unit
  }

  When("a caller starts a run of {string} under the run id {string} with a different input") {
    (_: String, _: String) =>
      try runs.start(name, Json.obj("topic" -> Json.str("geese")), runId): Unit
      catch case e: CommandError => failed = Some(e)
  }

  When("a caller starts a run of {string} with an input that does not have its input shape") {
    (_: String) =>
      ensureRegistered()
      try runs.start(name, Json.obj("subject" -> Json.num(3)), runId): Unit
      catch case e: CommandError => failed = Some(e)
  }

  When("a run of {string} reaches the step {string}") { (_: String, _: String) =>
    startRun(): Unit
    runs.await(runId, 60.seconds): Unit
  }

  When("a reader reads the run") { () => read = Some(runs.get(runId)) }
  private var read: Option[RunSnapshot] = None

  /**
   * As a `When`, with a turn still at its gate: makes the run end that way. As a `Then`: asserts it
   * did. One definition, because the runner matches a step's text whatever its keyword.
   */
  private def runEnds(status: RunStatus): Unit =
    if gate.getCount > 0 then
      status match
        case RunStatus.Failed    => draftRefusesAfterGate = true
        case RunStatus.Cancelled => runs.cancel(runId): Unit
        case _                   => ()
      gate.countDown()
    assertEquals(runs.await(runId, 60.seconds).status, status)

  When("the run is completed")(() => runEnds(RunStatus.Completed))
  When("the run is failed")(() => runEnds(RunStatus.Failed))
  When("the run is cancelled")(() => runEnds(RunStatus.Cancelled))

  When("the service restarts") { () =>
    kit.restartService()
    gate = CountDownLatch(0) // the next incarnation's turn passes the gate
    gateReached = CountDownLatch(1)
  }

  When("the worker in {string} gives up with the reason {string}") { (_: String, reason: String) =>
    assertEquals(reason, "nothing to draft from")
    draftRefusesAfterGate = true
    gate.countDown()
    runs.await(runId, 60.seconds): Unit
  }

  When("a run of {string} makes its fourth model call and has steps left") { (_: String) =>
    startRun(): Unit
    runs.await(runId, 60.seconds): Unit
  }

  When("a caller cancels the run") { () =>
    runs.cancel(runId): Unit
    gate.countDown()
    runs.await(runId, 60.seconds): Unit
  }

  When("the worker in {string} answers with a result that does not have that shape") {
    (_: String) =>
      startRun(): Unit
      runs.await(runId, 60.seconds): Unit
  }

  When("the worker calls the tool in a run of {string}") { (_: String) =>
    startRun(): Unit
    eventually("the run waits")(
      Option.when(runs.get(runId).status == RunStatus.WaitingForDecision)(())
    )
  }

  When("the run passes its time limit")(() => runs.await(runId, 60.seconds): Unit)

  When("a reader lists the runs of {string}") { (_: String) =>
    listed = eventually("the view holds three runs") {
      val rows = runs.list(name)
      Option.when(rows.size == 3 && rows.forall(_.status.ended))(rows)
    }
  }
  private var listed = Vector.empty[RunSummary]

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the steps {string}, {string} and {string} are carried out in that order") {
    (a: String, b: String, c: String) =>
      runs.await(runId, 60.seconds): Unit
      val order = model.requests.flatMap(userText).map(_.takeWhile(_ != '\n')).distinct
      assertEquals(order, Vector(s"Step '$a'.", s"Step '$b'.", s"Step '$c'."))
  }

  Then("each step's result is held when the step ends") { () =>
    val run = runs.get(runId)
    assert(run.steps.forall(s => s.ended && s.result.isDefined), run.toString)
  }

  Then("the run carries out its remaining steps from blueprint version 1") { () =>
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
    assert(requestsFor("polish").forall(_.systemMessage.exists(_.startsWith("Write briefly."))))
  }

  Then("the run names blueprint version 1 of {string}") { (_: String) =>
    assertEquals(runs.get(runId).version, 1)
  }

  Then("there is one run under the run id {string}") { (_: String) =>
    assertEquals(runs.get(runId).runId, runId)
    runs.await(runId, 60.seconds): Unit
    assertEquals(requestsFor("outline").size, 1)
  }
  Then("the caller is given that run")(() => assertEquals(runs.get(runId).input, input))

  Then("the caller is refused, naming the run id") { () =>
    assertEquals(failed.map(_.code), Some(ErrorCode.Conflict))
    assert(failed.exists(_.getMessage.contains(runId)), failed.toString)
  }
  Then("there is one run under the run id {string}, as it was started") { (_: String) =>
    assertEquals(runs.get(runId).input, input)
  }

  Then("the caller is refused, with the reason the input could not be read") { () =>
    assertEquals(failed.map(_.code), Some(ErrorCode.BadRequest))
    assert(failed.exists(_.getMessage.contains("input shape")), failed.toString)
  }
  Then("no run is held") { () =>
    val error = intercept[CommandError](runs.get(runId))
    assertEquals(error.code, ErrorCode.NotFound)
  }

  Then("the worker in {string} is given the run's input and the result of {string}") {
    (s: String, r: String) =>
      val shown = requestsFor(s).flatMap(userText).mkString
      assert(shown.contains("ducks") && shown.contains(s""""$r""""), shown)
      assert(shown.contains("draft-of-ducks"), shown)
  }
  Then("the worker in {string} is not given the result of {string}") { (s: String, r: String) =>
    val shown = requestsFor(s).flatMap(userText).mkString
    assert(!shown.contains(s"$r-of-ducks") && !shown.contains(s""""$r""""), shown)
  }

  Then("the reader is shown the result of {string}") { (s: String) =>
    assert(read.flatMap(_.stepNamed(s)).flatMap(_.result).isDefined, read.toString)
    gate.countDown()
  }
  Then("the reader is shown that the run is on the step {string}") { (s: String) =>
    assertEquals(read.flatMap(_.step), Some(s))
  }

  Then("the reader is shown the session of each worker in each step") { () =>
    assertEquals(
      read.map(_.steps.map(_.sessions)),
      Some(
        Vector(
          Vector(s"run:$runId:outline:writer"),
          Vector(s"run:$runId:draft:writer"),
          Vector(s"run:$runId:polish:writer")
        )
      )
    )
  }

  Then("the reader is shown the model usage of each step") { () =>
    assert(read.exists(_.steps.forall(_.usage == TokenUsage(10, 5, 0, 0))), read.toString)
  }
  Then("the reader is shown the model usage of the run, which is its steps' usage added together") {
    () =>
      assertEquals(read.map(_.usage), Some(TokenUsage(30, 15, 0, 0)))
  }

  Then("the caller is given the run as it ended") { () =>
    waiter.foreach(_.join(60_000))
    assertEquals(waited.map(_.status), Some(runs.get(runId).status))
    assert(waited.exists(_.ended))
  }

  Then("the run carries on from the step {string}") { (s: String) =>
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
    assert(requestsFor(s).size >= 2, s"$s was not run again: ${requestsFor(s).size}")
  }
  Then("the step {string} is not carried out again") { (s: String) =>
    runs.await(runId, 60.seconds): Unit
    assertEquals(requestsFor(s).count(r => !lastIsToolResult(r)), 1)
  }
  Then("that model call is not made again") { () =>
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
    assertEquals(requestsFor("outline").size, 1)
  }
  Then("the turn in {string} is run again from its start") { (s: String) =>
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
    // Before the restart: two calls (a note, then the gate). After: the whole turn again, three calls.
    assertEquals(requestsFor(s).count(r => !lastIsToolResult(r)), 2)
    assertEquals(requestsFor(s).size, 5)
  }

  Then("the run is failed, naming the step {string} and the reason {string}") {
    (s: String, reason: String) =>
      val run = runs.get(runId)
      assertEquals(run.status, RunStatus.Failed)
      assert(
        run.reason.exists(r => r.startsWith(s"$s:") && r.contains(reason)),
        run.reason.toString
      )
  }
  Then("the step {string} is not carried out")((s: String) => assertEquals(requestsFor(s).size, 0))

  Then("the run is still failed")(() => assertEquals(runs.get(runId).status, RunStatus.Failed))
  Then("no step of the run is carried out again") { () =>
    Thread.sleep(1500)
    assertEquals(model.requests.size, 3) // outline, the draft's gate call, the draft's refusal
  }

  Then("the run is failed, saying its run budget is spent") { () =>
    val run = runs.get(runId)
    assertEquals(run.status, RunStatus.Failed)
    assert(run.reason.exists(_.contains("run budget")), run.reason.toString)
  }

  Then("the turn in progress runs to its end")(() => assertEquals(requestsFor("draft").size, 2))
  Then("the run makes no further model call")(() => assertEquals(requestsFor("polish").size, 0))

  Then("the step does not end") { () =>
    assert(runs.get(runId).stepNamed("outline").exists(_.ended))
  } // it ended on the second try, not the first
  Then("the worker is told why its result could not be read") { () =>
    val second = requestsFor("outline").drop(1).headOption.flatMap(userText).getOrElse("")
    assert(second.contains("did not have the shape") && second.contains("must be an array"), second)
  }
  Then("the worker answers again within its budget") { () =>
    assertEquals(requestsFor("outline").size, 2)
    assertEquals(
      runs.get(runId).stepNamed("outline").flatMap(_.result),
      Some(Json.arr(Json.str("a"), Json.str("b")))
    )
  }

  Then("the run is waiting for a decision") { () =>
    assertEquals(runs.get(runId).status, RunStatus.WaitingForDecision)
  }
  Then("the run shows the approval request") { () =>
    assertEquals(
      runs.get(runId).stepNamed("polish").map(_.waiting.map(_.tool)),
      Some(Vector("approve_me"))
    )
  }
  Then("the worker's budget is not spent while the run waits") { () =>
    val before = model.requests.size
    Thread.sleep(1500)
    assertEquals(model.requests.size, before)
    // Let the run finish, so the host passivates.
    val waiting = runs.get(runId).stepNamed("polish").map(_.waiting).getOrElse(Vector.empty)
    waiting.foreach(w =>
      kit.componentClient
        .forAgent(SessionId(w.session))
        .decide(AskAgent.turn)(Decision.approved(w.approvalId, "dana")): Unit
    )
    assertEquals(runs.await(runId, 60.seconds).status, RunStatus.Completed)
  }

  Then("the run is failed, saying its time limit passed") { () =>
    val run = runs.get(runId)
    assertEquals(run.status, RunStatus.Failed)
    assert(run.reason.exists(_.contains("time limit")), run.reason.toString)
  }
  Then("the approval request is refused, with a note that the run ended") { () =>
    val history = kit.componentClient
      .forEventSourcedEntity(
        com.thinkmorestupidless.ankka.core.EntityId(s"run:$runId:polish:polisher")
      )
      .call(SessionMemoryEntity.history)
      .invoke()
    assertEquals(history.awaiting, Vector.empty)
    val decision = history.messages.collectFirst {
      case SessionMessage.ToolResultMessage(_, _, "approve_me", _, _, _, Some(d)) => d
    }
    assert(
      decision.exists(d =>
        !d.approved && d.by == Decision.Platform && d.note.exists(_.contains("time limit"))
      ),
      history.messages.toString
    )
  }
  Then("the tool is not run")(() => assertEquals(approved.get(), 0))

  Then(
    "the reader is given three runs, each with its run status and the blueprint version it ran"
  ) { () =>
    assertEquals(listed.map(_.version), Vector(1, 1, 2))
    assert(listed.forall(_.status == RunStatus.Completed), listed.toString)
    assertEquals(listed.map(_.runId), Vector("run-1", "run-2", "run-3").map(named))
  }
