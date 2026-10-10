package com.thinkmorestupidless.ankka.testkit.awaiting

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, JsonValueCodec}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.{Heartbeat, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{CallCounts, Observability}
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient, WorkflowLifecycle}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, Executors}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A caller's transport that counts what it is asked, by method: what "not asked again and again" is
 * read from. A client that polled the lifecycle would be seen here; the engine's own hold and
 * re-ask are the runtime's, below this.
 */
final class CountingTransport(underlying: CallTransport) extends CallTransport:
  val asked = ConcurrentHashMap[String, Integer]()

  private def count(method: String): Unit = asked.merge(method, 1, (a, b) => a + b): Unit
  def times(method: String): Int          = Option(asked.get(method)).fold(0)(_.intValue)

  def askTimeout: FiniteDuration = underlying.askTimeout

  def ask(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) =
    count(m)
    underlying.ask(c, e, m, p, md)

  override def askQuery(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) =
    count(m)
    underlying.askQuery(c, e, m, p, md)

  override def askWithMetadata(
      c: ComponentId,
      e: EntityId,
      m: MethodName,
      p: Array[Byte],
      md: Metadata
  ) =
    count(m)
    underlying.askWithMetadata(c, e, m, p, md)

  override def awaitEnd(c: ComponentId, e: EntityId, timeout: FiniteDuration, md: Metadata) =
    count(WorkflowLifecycle.AwaitEnd)
    underlying.awaitEnd(c, e, timeout, md)

  def tell(c: ComponentId, e: EntityId, message: Any): Unit = underlying.tell(c, e, message)

/** Every log line written while it is attached, formatted when written. */
final class LinesSeen extends AppenderBase[ILoggingEvent]:
  val lines = CopyOnWriteArrayList[String]()
  override def append(event: ILoggingEvent): Unit =
    lines.add(s"${event.getLevel} ${event.getFormattedMessage}"): Unit

/**
 * The steps of `features/awaiting-workflows`, shared by one suite per feature file. Workflow names
 * in a scenario are made unique to it (`q1` in two scenarios is two workflows), and a caller waits
 * through a client of its own whose transport counts what it asks.
 */
abstract class AwaitSteps(features: String) extends GherkinSuite(features) with LogCapturing:

  override val munitTimeout = 3.minutes

  /** Configuration the service starts with: a short idle timeout for the serving feature. */
  protected def settings: Config = ConfigFactory.empty()

  /**
   * Whether each scenario has a cluster of its own, started by its background, stopped after it.
   */
  protected def clusterPerScenario: Boolean = false

  private given ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())
  private given JsonValueCodec[Quote] = Codecs.make[Quote]

  protected var kit: AnkkaTestKit                = scala.compiletime.uninitialized
  protected var server: HttpServer               = scala.compiletime.uninitialized
  protected var peers: Vector[AnkkaTestKit.Peer] = Vector.empty
  private var mainStopped                        = false
  private val http                               = HttpClient.newHttpClient()

  private def startCluster(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(clients => QuoteEndpoint(clients))
    kit = AnkkaTestKit.start(
      Seq(QuoteWorkflow.descriptor, KycWorkflow.descriptor, OnboardingWorkflow.descriptor),
      extensions = Seq(server),
      settings = settings
    )
    mainStopped = false

  private def stopCluster(): Unit =
    peers.foreach(_.stop())
    peers = Vector.empty
    if kit != null then kit.stop()
    kit = null

  override def beforeAll(): Unit = if !clusterPerScenario then startCluster()
  override def afterAll(): Unit  = stopCluster()

  override def afterEach(context: AfterEach): Unit =
    if clusterPerScenario then stopCluster()
    pending = StepScript()
    callers = Vector.empty
    told = None
    super.afterEach(context)

  // ── what a scenario holds ─────────────────────────────────────────────────

  /** A caller: its own client, and its wait's outcome once it has one. */
  final case class Caller(transport: CountingTransport, outcome: Future[Answer])
  final case class Answer(result: Try[Quote], askedAt: Long, answeredAt: Long):
    def took: FiniteDuration = (answeredAt - askedAt).nanos

  protected var pending: StepScript      = StepScript()
  protected var callers: Vector[Caller]  = Vector.empty
  protected var told: Option[Answer]     = None
  protected var lines: Option[LinesSeen] = None

  protected def id(name: String): String = s"$scenarioId-$name"

  protected def nodes: Vector[ComponentClient] =
    (if mainStopped then Vector.empty else Vector(kit.componentClient)) ++ peers.map(
      _.componentClient
    )

  /** A client on a node that is running, for reads a caller does not make. */
  protected def anyClient: ComponentClient = nodes.head

  protected def clientOn(client: ComponentClient): (ComponentClient, CountingTransport) =
    val counting = CountingTransport(client.transportRef)
    (ComponentClient(counting), counting)

  protected def lifecycle(name: String) =
    anyClient.forWorkflow(EntityId(id(name))).lifecycle(QuoteWorkflow).invoke()

  protected def stateOf(name: String): Quote =
    anyClient.forWorkflow(EntityId(id(name))).call(QuoteWorkflow.quote).invoke()

  protected def until(description: String, within: FiniteDuration = 60.seconds)(
      check: => Boolean
  ): Unit =
    val deadline = within.fromNow
    while !Try(check).getOrElse(false) && deadline.hasTimeLeft() do Thread.sleep(50)
    assert(Try(check).getOrElse(false), s"$description within $within")

  protected def start(name: String, amount: Int = 100): Unit =
    StepScript.set(id(name), pending)
    anyClient
      .forWorkflow(EntityId(id(name)))
      .call(QuoteWorkflow.start)
      .invoke(QuoteRequest(amount)): Unit

  /** A caller on `on` waiting for `name` within `within`, in the background. */
  protected def waitInBackground(
      name: String,
      within: FiniteDuration,
      on: ComponentClient = anyClient
  ): Caller =
    val (client, counting) = clientOn(on)
    val askedAt            = System.nanoTime()
    val outcome = Future {
      val result = Try(client.forWorkflow(EntityId(id(name))).awaitEnd(QuoteWorkflow, within))
      Answer(result, askedAt, System.nanoTime())
    }
    val caller = Caller(counting, outcome)
    callers = callers :+ caller
    caller

  protected def waitNow(name: String, within: FiniteDuration): Answer =
    val caller = waitInBackground(name, within)
    val answer = Await.result(caller.outcome, within + 30.seconds)
    told = Some(answer)
    answer

  protected def answered(caller: Caller): Answer =
    val answer = Await.result(caller.outcome, 90.seconds)
    told = Some(answer)
    answer

  protected def last: Answer =
    told.orElse(callers.lastOption.map(answered)).getOrElse(fail("no caller has waited"))

  protected def endedState(name: String, answer: Answer): Quote =
    val quote = answer.result.fold(e => fail(s"expected the state, got $e"), identity)
    assertEquals(quote, stateOf(name), "the state the workflow ended with")
    assertEquals(quote.steps.takeRight(1), Vector("offer"))
    quote

  protected def failureOf(answer: Answer): CommandError =
    answer.result.failed.toOption match
      case Some(e: CommandError) => e
      case other                 => fail(s"expected a CommandError, got $other")

  protected def duration(text: String): FiniteDuration = Duration(text).asInstanceOf[FiniteDuration]

  // ── backgrounds ───────────────────────────────────────────────────────────

  Given(
    "a service {string} with a workflow {string} of the steps {string}, {string} and {string}"
  ) { (_: String, workflow: String, a: String, b: String, c: String) =>
    if clusterPerScenario then startCluster()
    assertEquals(workflow, "quote")
    assertEquals(Seq(a, b, c), Seq("rates", "margin", "offer"))
  }

  Given("a workflow {string} of the steps {string}, {string} and {string}") {
    (workflow: String, a: String, b: String, c: String) =>
      assertEquals(workflow, "quote")
      assertEquals(Seq(a, b, c), Seq("rates", "margin", "offer"))
  }

  // ── scripting a workflow ──────────────────────────────────────────────────

  Given("the step {string} of {string} fails after its retries") {
    (step: String, workflow: String) =>
      assertEquals(workflow, "quote")
      pending = pending.copy(failing = Some(step))
  }

  Given("the command {string} of {string} refuses a bad request") {
    (command: String, workflow: String) =>
      assertEquals((command, workflow), ("start", "quote"))
  }

  Given("the workflow {string} of {string} has ended as completed") { (name: String, _: String) =>
    start(name)
    until(s"$name ends")(lifecycle(name).isCompleted)
  }

  Given("the workflow {string} of {string} is running a step that takes {string}") {
    (name: String, _: String, takes: String) =>
      pending = pending.copy(durations = Map("rates" -> duration(takes)))
      start(name)
  }

  Given("the workflow {string} of {string} is paused after its step {string}") {
    (name: String, _: String, step: String) =>
      assertEquals(step, "rates")
      pending = pending.copy(pauseAfterRates = true)
      start(name)
      until(s"$name pauses")(lifecycle(name).isPaused)
  }

  Given("the workflow {string} of {string} has recorded nothing")((_: String, _: String) => ())

  // ── callers ───────────────────────────────────────────────────────────────

  When(
    "a caller sends the command {string} to the workflow {string} of {string} and waits for its end within {string}"
  ) { (command: String, name: String, _: String, within: String) =>
    assertEquals(command, "start")
    StepScript.set(
      id(name),
      pending.copy(durations = pending.durations ++ Map("offer" -> 300.millis))
    )
    val (client, counting) = clientOn(anyClient)
    val askedAt            = System.nanoTime()
    val result = Try(
      client
        .forWorkflow(EntityId(id(name)))
        .call(QuoteWorkflow.start)
        .thenAwaitEnd(duration(within))
        .invoke(QuoteRequest(100))
    )
    val answer = Answer(result, askedAt, System.nanoTime())
    callers = callers :+ Caller(counting, Future.successful(answer))
    told = Some(answer)
  }

  When(
    "a caller sends the command {string} to the workflow {string} of {string} with a bad request and waits for its end"
  ) { (command: String, name: String, _: String) =>
    assertEquals(command, "start")
    val (client, counting) = clientOn(anyClient)
    val askedAt            = System.nanoTime()
    val result = Try(
      client
        .forWorkflow(EntityId(id(name)))
        .call(QuoteWorkflow.start)
        .thenAwaitEnd(30.seconds)
        .invoke(QuoteRequest(0))
    )
    val answer = Answer(result, askedAt, System.nanoTime())
    callers = callers :+ Caller(counting, Future.successful(answer))
    told = Some(answer)
  }

  When("a caller waits for the end of {string} within {string}") { (name: String, within: String) =>
    waitNow(name, duration(within)): Unit
  }

  When("the caller waits for the end of {string} again within {string}") {
    (name: String, within: String) =>
      waitNow(name, duration(within)): Unit
  }

  Given("a caller waiting for the end of the workflow {string} of {string}") {
    (name: String, _: String) =>
      if !clusterPerScenario then
        pending = pending.copy(durations =
          pending.durations ++ Map("offer" -> 3.seconds, "rates" -> 1.second)
        )
        start(name)
        waitInBackground(name, 60.seconds): Unit
      else waitOnAnotherInstance(name)
  }

  Given("two callers waiting for the end of the workflow {string} of {string}") {
    (name: String, _: String) =>
      pending = pending.copy(durations = Map("offer" -> 3.seconds))
      start(name)
      waitInBackground(name, 60.seconds): Unit
      waitInBackground(name, 60.seconds): Unit
  }

  Given("a caller that was told its wait for the workflow {string} of {string} timed out") {
    (name: String, _: String) =>
      pending = pending.copy(durations = Map("rates" -> 4.seconds))
      start(name)
      val answer = waitNow(name, 1.second)
      assertEquals(failureOf(answer).code, ErrorCode.Timeout)
  }

  When("{string} ends") { (name: String) =>
    until(s"$name ends")(lifecycle(name).isTerminal)
  }

  When("{string} is deleted") { (name: String) =>
    until(s"$name is running")(lifecycle(name).isRunning)
    anyClient.forWorkflow(EntityId(id(name))).call(QuoteWorkflow.cancel).invoke(): Unit
  }

  // ── what a caller is told ─────────────────────────────────────────────────

  Then("the caller is answered with the state {string} ended with") { (name: String) =>
    endedState(name, last): Unit
  }

  Then("the caller is answered with the state {string} ended with at once") { (name: String) =>
    val answer = last
    endedState(name, answer)
    assert(answer.took < 1.second, s"answered after ${answer.took}")
  }

  Then("each caller is answered with the state {string} ended with") { (name: String) =>
    assertEquals(callers.size, 2)
    callers.foreach(caller => endedState(name, answered(caller)))
  }

  Then("the caller is answered after the step {string} has run") { (step: String) =>
    val finished = StepScript.finishedAt(id("q1"), step).getOrElse(fail(s"$step never ran"))
    assert(last.answeredAt > finished, "answered before the last step had run")
  }

  Then("the caller is answered with a failure that names the step {string} and the reason") {
    (step: String) =>
      val error = failureOf(last)
      assertEquals(error.code, ErrorCode.WorkflowFailed, error.message)
      val failure = WorkflowEnd.failure(error).get
      assertEquals(failure.step, Some(step))
      assert(failure.reason.contains(s"no $step"), failure.reason)
  }

  Then("the caller is not answered with a refusal of the command") { () =>
    val code = failureOf(last).code
    assert(code == ErrorCode.WorkflowFailed, s"answered $code")
  }

  Then("the caller is answered with the refusal at once") { () =>
    val answer = last
    assertEquals(failureOf(answer).code, ErrorCode.BadRequest)
    assert(answer.took < 1.second, s"answered after ${answer.took}")
  }

  Then("no wait begins") { () =>
    assertEquals(callers.last.transport.times(WorkflowLifecycle.AwaitEnd), 0)
    assertEquals(callers.last.transport.times("start"), 1)
  }

  Then("the caller is answered within {string} of the end being recorded") { (within: String) =>
    val answer = answered(callers.last)
    endedState("q1", answer)
    val finished = StepScript.finishedAt(id("q1"), "offer").get
    assert(
      (answer.answeredAt - finished).nanos < duration(within),
      s"${(answer.answeredAt - finished).nanos}"
    )
  }

  Then("the standing of {string} was not asked for again and again while the caller waited") {
    (_: String) =>
      val transport = callers.last.transport
      assertEquals(transport.times(WorkflowLifecycle.Method), 0, "the lifecycle was asked")
      assertEquals(
        transport.times(WorkflowLifecycle.AwaitEnd),
        1,
        "the caller asked to wait more than once"
      )
      assertEquals(transport.times("quote"), 0, "the workflow's own query was asked")
  }

  Then("the caller is told the wait timed out") { () =>
    assertEquals(failureOf(last).code, ErrorCode.Timeout)
  }

  Then("{string} runs on to its end") { (name: String) =>
    until(s"$name completes", 90.seconds)(lifecycle(name).isCompleted)
  }

  Then("{string} is still paused") { (name: String) =>
    assert(lifecycle(name).isPaused, lifecycle(name).toString)
  }

  Then("the caller is answered with a failure that says the workflow was deleted") { () =>
    val answer = answered(callers.last)
    val error  = failureOf(answer)
    assertEquals(error.code, ErrorCode.WorkflowFailed)
    assert(WorkflowEnd.failure(error).exists(_.deleted), error.toString)
    assert(answer.took < 10.seconds, s"answered after ${answer.took}")
  }

  // ── the topology ──────────────────────────────────────────────────────────

  private val WaitRoute = "GET /quotes/{id}/wait"

  private def waitCalls: Vector[CallCounts.Pair] =
    val observability  = Observability(kit.service.system)
    def name(ref: Int) = observability.names.nameOf(ref).getOrElse("")
    observability.calls
      .snapshot(System.currentTimeMillis())
      .pairs
      .filter(p => name(p.callerHandler) == WaitRoute && name(p.calleeComponent) == "quote")

  private var callsBefore: (Long, Long, Long) = (0L, 0L, 0L)

  Given("a caller that waited for the end of the workflow {string} of {string} and was answered") {
    (name: String, _: String) =>
      // Longer than one hold, so the wait is asked again once: still one call.
      pending = pending.copy(durations = Map("rates" -> 6.seconds))
      start(name)
      val before = waitCalls
      callsBefore = (before.map(_.ok).sum, before.map(_.handled).sum, before.map(_.unanswered).sum)
      val response = http.send(
        HttpRequest
          .newBuilder(
            URI.create(s"http://127.0.0.1:${server.boundPort.get}/quotes/${id(name)}/wait")
          )
          .build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
      assertEquals(response.statusCode(), 200)
      assertEquals(readFromArray[Quote](response.body()), stateOf(name))
  }

  When("a developer reads the service's topology")(() => ())

  Then("the topology shows one observed call from the caller to {string}, handled as ok") {
    (workflow: String) =>
      assertEquals(workflow, "quote")
      val after = waitCalls
      assert(
        after.forall(p =>
          Observability(kit.service.system).names
            .nameOf(p.calleeHandler)
            .contains(WorkflowLifecycle.AwaitEnd)
        )
      )
      assertEquals(after.map(_.ok).sum - callsBefore._1, 1L, "ok")
      assertEquals(after.map(_.handled).sum - callsBefore._2, 1L, "handled")
      assertEquals(after.map(_.unanswered).sum - callsBefore._3, 0L, "unanswered")
  }

  // ── instances ─────────────────────────────────────────────────────────────

  Given("a service {string} written in {string} running as {int} instances") {
    (_: String, language: String, instances: Int) =>
      assertEquals(language, "Scala")
      startCluster()
      peers = Vector.fill(instances - 1)(kit.startPeer(Seq.empty))
      val seen = LinesSeen()
      seen.start()
      LoggerFactory
        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        .asInstanceOf[ch.qos.logback.classic.Logger]
        .addAppender(seen)
      lines = Some(seen)
  }

  private var host: Option[ComponentClient] = None

  /** Starts `name` with a long `margin`, finds the instance it runs on, and waits from another. */
  private def waitOnAnotherInstance(name: String): Unit =
    pending = pending.copy(durations = Map("margin" -> 10.seconds))
    start(name)
    until(s"$name has run rates")(StepScript.finishedAt(id(name), "rates").isDefined)
    until(s"$name recorded where rates ran")(stateOf(name).instances.nonEmpty)
    val ranOn = stateOf(name).instances.head
    val hosting = nodes
      .find(n => System.identityHashCode(n) == ranOn)
      .getOrElse(fail("rates ran on no known instance"))
    host = Some(hosting)
    waitInBackground(name, 90.seconds, on = nodes.find(_ ne hosting).get): Unit

  Given(
    "a caller on one instance waiting for the end of the workflow {string} of {string} running on another instance"
  ) { (name: String, _: String) =>
    waitOnAnotherInstance(name)
  }

  private def stopHost(): Unit =
    val hosting = host.get
    if hosting eq kit.componentClient then
      kit.stopService()
      mainStopped = true
    else
      val peer = peers.find(_.componentClient eq hosting).get
      peer.stop()
      peers = peers.filterNot(_ eq peer)

  When("the instance running {string} stops during the step {string}") {
    (name: String, step: String) =>
      assertEquals(step, "margin")
      assert(StepScript.finishedAt(id(name), "margin").isEmpty, "margin had already finished")
      stopHost()
  }

  When("the instance running {string} stops and {string} ends elsewhere") {
    (name: String, _: String) =>
      stopHost()
      until(s"$name ends elsewhere", 90.seconds)(lifecycle(name).isCompleted)
  }

  Then("{string} goes on on another instance to its end") { (name: String) =>
    val answer = answered(callers.last)
    val quote  = endedState(name, answer)
    assertEquals(quote.steps, Vector("rates", "margin", "offer"))
    assertNotEquals(quote.instances(1), quote.instances(0), "margin ran where rates did")
  }

  Then("the caller is answered") { () =>
    assert(answered(callers.last).result.isSuccess, callers.last.toString)
  }

  Then("the service's log has no line saying a waiting caller was lost") { () =>
    val seen = lines.get.lines.asScala.toVector
    val lost = seen.filter(l =>
      l.toLowerCase.matches(".*(wait|waiter|caller).*lost.*|.*lost.*(wait|waiter|caller).*")
    )
    assertEquals(lost, Vector.empty)
    LoggerFactory
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[ch.qos.logback.classic.Logger]
      .detachAppender(lines.get)
    lines = None
  }

  // ── composition ───────────────────────────────────────────────────────────

  Given("a service {string} with a workflow {string} of the steps {string} and {string}") {
    (_: String, workflow: String, a: String, b: String) =>
      assertEquals((workflow, a, b), ("kyc", "documents", "decision"))
  }

  Given(
    "a workflow {string} whose step {string} starts the workflow {string} and waits for its end within the step's timeout"
  ) { (parent: String, step: String, child: String) =>
    assertEquals((parent, step, child), ("onboarding", "verify", "kyc"))
  }

  Given("the step {string} of {string} has a timeout of {string}") {
    (step: String, workflow: String, timeout: String) =>
      assertEquals((step, workflow), ("verify", "onboarding"))
      assertEquals(OnboardingWorkflow.VerifyTimeout, duration(timeout))
  }

  private var kycScript = StepScript()

  Given("the step {string} of {string} takes {string}") {
    (step: String, workflow: String, takes: String) =>
      assertEquals(workflow, "kyc")
      kycScript = kycScript.copy(durations = Map(step -> duration(takes)))
  }

  When("the workflow {string} of {string} runs the step {string}") {
    (name: String, workflow: String, step: String) =>
      assertEquals((workflow, step), ("onboarding", "verify"))
      StepScript.set(s"kyc-${id(name)}", kycScript)
      kycScript = StepScript()
      kit.componentClient
        .forWorkflow(EntityId(id(name)))
        .call(OnboardingWorkflow.start)
        .invoke("ann"): Unit
  }

  private def onboardingLifecycle(name: String) =
    kit.componentClient.forWorkflow(EntityId(id(name))).lifecycle(OnboardingWorkflow).invoke()

  Then("{string} moves to the step its handler chooses from the state {string} ended with") {
    (workflow: String, child: String) =>
      assertEquals((workflow, child), ("onboarding", "kyc"))
      val name = "a1"
      until("onboarding ends")(onboardingLifecycle(name).isTerminal)
      val onboarding =
        kit.componentClient.forWorkflow(EntityId(id(name))).call(OnboardingWorkflow.status).invoke()
      val kyc = kit.componentClient
        .forWorkflow(EntityId(s"kyc-${id(name)}"))
        .awaitEnd(KycWorkflow, 1.second)
      assertEquals(kyc.decision, Some("approved"))
      assertEquals(onboarding.status, "welcomed")
  }

  Then("the step {string} fails as timed out") { (step: String) =>
    until("onboarding fails")(onboardingLifecycle("a2").isFailed)
    val failure = onboardingLifecycle("a2").failure.getOrElse("")
    assert(failure.contains(s"step '$step'") && failure.contains("timed out"), failure)
  }

  Then("the workflow {string} started for {string} runs on to its end") {
    (workflow: String, name: String) =>
      assertEquals(workflow, "kyc")
      val kyc = kit.componentClient
        .forWorkflow(EntityId(s"kyc-${id(name)}"))
        .awaitEnd(KycWorkflow, 60.seconds)
      assertEquals(kyc.steps, Vector("documents", "decision"))
  }

  // ── serving ───────────────────────────────────────────────────────────────

  /** What the browser read: the lines, whether the connection was cut, and when it ended. */
  final case class Read(lines: Vector[String], cut: Option[Throwable], endedAt: Long)

  private var route: Option[String] = None
  private var read: Option[Read]    = None

  Given(
    "a service {string} with a workflow {string} whose steps take longer than the service's idle timeout"
  ) { (_: String, workflow: String) =>
    assertEquals(workflow, "report")
    // Eight seconds of steps, under an idle timeout of three.
    pending =
      StepScript(durations = Map("rates" -> 2.seconds, "margin" -> 2.seconds, "offer" -> 4.seconds))
    assert(Heartbeat.interval(kit.service.system.settings.config) < 3.seconds)
  }

  Given(
    "an HTTP endpoint of {string} that serves the end of the workflow {string} of {string} as server-sent events"
  ) { (_: String, name: String, _: String) =>
    route = Some(s"/quotes/${id(name)}/events")
  }

  Given(
    "an HTTP endpoint of {string} that answers the end of the workflow {string} of {string} as one whole answer"
  ) { (_: String, name: String, _: String) =>
    route = Some(s"/quotes/${id(name)}/whole")
  }

  When("a browser reads the route while {string} runs") { (name: String) =>
    start(name)
    val request = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}${route.get}"))
      .header("Accept", "text/event-stream")
      .build()
    val lines = Vector.newBuilder[String]
    val cut =
      try
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        if response.statusCode() != 200 then
          Some(RuntimeException(s"status ${response.statusCode()}"))
        else
          response.body().forEach(line => lines += line)
          None
      catch case e: java.io.IOException => Some(e)
    read = Some(Read(lines.result(), cut, System.nanoTime()))
  }

  /**
   * The events read, as (name, data): one per block of lines, in whatever order its fields came.
   */
  private def events: Vector[(String, String)] =
    val blocks = read.get.lines.foldLeft(Vector(Vector.empty[String])) { (acc, line) =>
      if line.isEmpty then acc :+ Vector.empty else acc.init :+ (acc.last :+ line)
    }
    blocks.filter(_.nonEmpty).map { block =>
      def field(name: String) =
        block.find(_.startsWith(s"$name:")).map(_.stripPrefix(s"$name:").trim).getOrElse("")
      field("event") -> field("data")
    }

  Then("the browser receives a heartbeat while it waits") { () =>
    assert(events.count(_._1 == "heartbeat") >= 2, read.get.lines.toString)
  }

  Then("the browser then receives the state {string} ended with") { (name: String) =>
    val ended = events.filter(_._1 == "ended")
    assertEquals(ended.size, 1, read.get.lines.toString)
    assertEquals(events.last._1, "ended", "nothing after the end")
    assertEquals(readFromArray[Quote](ended.head._2.getBytes), stateOf(name))
  }

  Then("the connection was not cut") { () =>
    assertEquals(read.get.cut, None)
  }

  Then("the connection is cut before {string} ends") { (name: String) =>
    val cut = read.get.cut.getOrElse(fail(s"the whole answer arrived: ${read.get.lines}"))
    assert(cut.isInstanceOf[java.io.IOException], cut.toString)
    val ended = StepScript.finishedAt(id(name), "offer")
    assert(ended.forall(_ > read.get.endedAt), "the connection lasted until the end")
    until(s"$name runs on to its end")(lifecycle(name).isCompleted)
  }

  // ── every language ────────────────────────────────────────────────────────

  private def languageRow(language: String): Unit =
    assume(
      language == "Scala",
      s"the $language row is run by the $language SDK's tests (sdks/${language.toLowerCase}/examples)"
    )
    if kit == null then startCluster()

  Given(
    "a service {string} written in {string} with a workflow {string} of the steps {string} and {string}"
  ) { (_: String, language: String, workflow: String, a: String, b: String) =>
    languageRow(language)
    assertEquals((workflow, a, b), ("quote", "rates", "offer"))
  }

  Given(
    "a service {string} written in {string} with a workflow {string} whose step {string} fails after its retries"
  ) { (_: String, language: String, workflow: String, step: String) =>
    languageRow(language)
    assertEquals(workflow, "quote")
    pending = pending.copy(failing = Some(step))
  }

  private var handlerAnswer: Option[HttpResponse[Array[Byte]]] = None

  When(
    "a handler of {string} sends the command {string} to the workflow {string} of {string} and waits for its end within {string}"
  ) { (_: String, command: String, name: String, _: String, within: String) =>
    assertEquals((command, duration(within)), ("start", 30.seconds))
    StepScript.set(id(name), pending)
    handlerAnswer = Some(
      http.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}/quotes/${id(name)}"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""{"amount":100}"""))
          .build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
    )
  }

  Then("the handler is answered with the state {string} ended with") { (name: String) =>
    val response = handlerAnswer.get
    assertEquals(response.statusCode(), 200, String(response.body()))
    assertEquals(readFromArray[Quote](response.body()), stateOf(name))
  }

  Then("the handler is answered with a failure that names the step {string} and the reason") {
    (step: String) =>
      val response = handlerAnswer.get
      val body     = String(response.body())
      assertEquals(response.statusCode(), 424, body)
      assert(body.contains(s""""step":"$step""""), body)
      assert(body.contains(s"no $step"), body)
  }

final class AwaitingFeatures
    extends AwaitSteps("../../features/awaiting-workflows/awaiting.feature")
final class CompositionFeatures
    extends AwaitSteps("../../features/awaiting-workflows/composition.feature")

final class ServingFeatures extends AwaitSteps("../../features/awaiting-workflows/serving.feature"):
  override protected def settings: Config =
    ConfigFactory.parseString(
      "pekko.http.server.idle-timeout = 3s\nankka.http.socket.keep-alive = 1s"
    )

final class InstancesFeatures
    extends AwaitSteps("../../features/awaiting-workflows/instances.feature"):
  override protected def clusterPerScenario: Boolean = true

final class LanguagesFeatures
    extends AwaitSteps("../../features/awaiting-workflows/languages.feature"):
  override protected def ranElsewhere: Map[String, String] = Map(
    "a runtime from before waiting refuses a handler's wait, naming the protocol version" ->
      "the Python SDK's tests (sdks/python/tests/test_await_old_runtime.py)"
  )
