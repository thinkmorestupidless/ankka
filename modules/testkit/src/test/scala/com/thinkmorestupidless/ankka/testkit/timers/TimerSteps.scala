package com.thinkmorestupidless.ankka.testkit.timers

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.{Database, FiredTimer, RuntimeExtension, TimerRuntime}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  LogCapturing,
  ScheduledTimer,
  TimerProbe
}

import java.time.temporal.ChronoUnit
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The steps the timer features share, run against a real service.
 *
 * A `Given` about a timed action or a handler only describes the service; the service is started,
 * on `AnkkaTestKit` with a `TimerRuntime` polling every 100 ms, the first time a timer is set. Each
 * scenario has a service, and a database, of its own, so no timer of one scenario can fire in
 * another.
 *
 * Two records are read. A handler's run says what the handler saw and when, by the wall clock; the
 * [[TimerProbe]] says what the runtime did after it — the due the run was for, and the next. A step
 * about due times reads the probe and asserts exactly: due times are whole milliseconds and the
 * cadence is arithmetic, so a tolerance there would hide a drift. Only wall-clock gaps — a backoff,
 * "within three seconds" — have one. A step about a fire waits for the run it is about; a step that
 * something does *not* happen watches for long enough that it would have.
 *
 * "A handler of the service sets the timer" is the test setting it through the same scheduler a
 * handler is given.
 *
 * @param feature
 *   one feature file, relative to `modules/testkit`, where a forked test runs: the repository's
 *   features are two levels up.
 */
abstract class TimerSteps(feature: String) extends GherkinSuite(feature) with LogCapturing:

  import TimerSteps.*

  override val munitTimeout: Duration = 3.minutes

  /** How often the sweeper looks. Every wait below is a few of these, or a backoff. */
  protected val pollInterval: FiniteDuration = 100.millis

  // What the scenario has said about the service, until it is started.
  private var service: Option[String]       = None
  private var actions                       = Map.empty[String, ScriptedTimerKit]
  private var handlers                      = Map.empty[String, String] // handler -> action
  protected var extraExtensions             = Vector.empty[RuntimeExtension]
  private var kit: Option[AnkkaTestKit]     = None
  private var runtime: Option[TimerRuntime] = None
  protected var probe: TimerProbe           = TimerProbe()

  /** Whether the service starts with a timer runtime; one about an older runtime starts without. */
  protected var withTimerRuntime = true

  // What the scenario has done and seen.
  protected var current: Option[String]  = None // the timer most recently named
  private var setAt                      = Map.empty[String, (Instant, Instant)]
  private var setDue                     = Map.empty[String, Instant]
  private var beforeSetAgain             = Map.empty[String, Instant]
  private var answered: Option[Boolean]  = None
  private var refusal: Option[String]    = None
  private var largeValue: Option[String] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    service = None
    actions = Map.empty
    handlers = Map.empty
    extraExtensions = Vector.empty
    probe = TimerProbe()
    withTimerRuntime = true
    current = None
    setAt = Map.empty
    setDue = Map.empty
    beforeSetAgain = Map.empty
    answered = None
    refusal = None
    largeValue = None

  override def afterEach(context: AfterEach): Unit =
    try kit.foreach(_.stop())
    finally
      kit = None
      runtime = None
      super.afterEach(context)

  // ── the service a scenario describes ────────────────────────────────────────

  protected def action(name: String): ScriptedTimerKit =
    actions.getOrElse(name, fail(s"the scenario names no timed action '$name'"))

  /** The timed action a handler belongs to, by the handler's name. */
  protected def ofHandler(handler: String): ScriptedTimerKit =
    handlers.get(handler).map(action).getOrElse(fail(s"the scenario names no handler '$handler'"))

  protected def declareAction(serviceName: String, name: String): Unit =
    assert(kit.isEmpty, "a scenario describes its service before a timer is set")
    service.foreach(s => assertEquals(serviceName, s, "a scenario is about one service"))
    service = Some(serviceName)
    actions += name -> ScriptedTimerKit(name, () => scheduler)

  protected def declareHandler(handler: String, actionName: String, behaviour: Behaviour): Unit =
    val kitFor = action(actionName)
    assert(kitFor.declares(handler), s"'$actionName' declares no handler '$handler'")
    kitFor.script(handler, behaviour)
    handlers += handler -> actionName

  protected def descriptors: Seq[ComponentDescriptor] = actions.values.map(_.descriptor).toSeq

  /** The timer runtime a started service has. */
  protected def newTimerRuntime(): TimerRuntime = TimerRuntime(pollInterval, probe)

  protected def isStarted: Boolean = kit.isDefined

  protected def started: AnkkaTestKit =
    kit.getOrElse {
      val timers  = Option.when(withTimerRuntime)(newTimerRuntime())
      val started = AnkkaTestKit.start(descriptors, timers.toVector ++ extraExtensions)
      probe.bind(started)
      runtime = timers
      kit = Some(started)
      started
    }

  /**
   * Starts a timer runtime in a service started without one: what an instance with recurring timers
   * taking over the sweeping is, seen from the table.
   */
  protected def startTimerRuntime(): Unit =
    val timers = newTimerRuntime()
    timers.start(started.service)
    runtime = Some(timers)

  /**
   * The scheduler handlers are given: the timer runtime's, or — in a service started without one,
   * standing in for an instance whose runtime has recurring timers while another instance sweeps —
   * the same scheduler over the same database.
   */
  protected def scheduler: TimerScheduler =
    runtime.map(_.timerScheduler).getOrElse {
      given org.apache.pekko.actor.typed.ActorSystem[?] = started.service.system
      com.thinkmorestupidless.ankka.runtime.DatabaseTimerScheduler(Database())
    }

  protected def runsOf(timer: String): Vector[Run] =
    actions.values.flatMap(_.runsOf(timer)).toVector

  protected def runsOfHandler(handler: String): Vector[Run] = ofHandler(handler).runs(handler)

  /**
   * Waits until `timer` has run `count` times and the runtime has done what it does after each, and
   * answers the runs.
   */
  protected def awaitRuns(
      timer: String,
      count: Int,
      within: FiniteDuration = 20.seconds
  ): Vector[Run] =
    waitFor(s"the timer '$timer' has fired $count time(s)", within)(
      Option(runsOf(timer)).filter(_.size >= count && probe.fired(timer).size >= count)
    )

  protected def waitFor[A](description: String, within: FiniteDuration = 20.seconds)(
      check: => Option[A]
  ): A =
    val deadline = within.fromNow
    var last     = check
    while last.isEmpty && !deadline.isOverdue() do
      Thread.sleep(25)
      last = check
    last.getOrElse(fail(s"$description did not happen within $within"))

  /** A timer's `due_at` as the database holds it, or `None` when there is no row. */
  protected def storedDueAt(timer: String): Option[Instant] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = started.service.system
    Await.result(
      Database().queryOne(sql"SELECT due_at FROM ankka_timers WHERE timer_name = $timer")(
        _.get("due_at", classOf[Instant])
      ),
      10.seconds
    )

  protected def scheduled(timer: String): ScheduledTimer =
    probe.scheduled(timer).getOrElse(fail(s"there is no timer '$timer'"))

  private def setting[A](timer: String)(set: => A): A =
    val _ = started
    current = Some(timer)
    val before = Instant.now()
    val result = set
    setAt += timer -> (before -> Instant.now())
    result

  protected def setTimer(timer: String, handler: String, delay: FiniteDuration = 300.millis): Unit =
    val kitFor = ofHandler(handler)
    setting(timer)(
      scheduler.createSingleTimer(timer, delay, kitFor.handle(handler).deferred(timer))
    )
    setDue += timer -> scheduled(timer).dueTime

  protected def setRecurring(
      timer: String,
      handler: String,
      period: FiniteDuration,
      delay: FiniteDuration = Duration.Zero,
      value: Option[String] = None
  ): Unit =
    val kitFor = ofHandler(handler)
    setting(timer)(
      scheduler.createRecurringTimer(
        timer,
        delay,
        period,
        kitFor.handle(handler).deferred(value.getOrElse(timer))
      )
    )

  /**
   * Waits, for a timer whose next run is due now or within a second, until it has run for that due:
   * its next due is then at least a period away, so what a step reads of it before and after
   * setting it again cannot be moved by a run in between.
   */
  private def quietMoment(timer: String): Unit =
    val due = scheduled(timer).dueTime
    if !due.isAfter(Instant.now().plusSeconds(1)) then
      val _ = waitFor(s"the timer '$timer' runs for $due", 20.seconds)(
        probe.fired(timer).find(_.dueTime == due)
      )

  /** The due times `timer` ran for and finished, with the period it has. */
  protected def cadence(timer: String): (Vector[Instant], Long) =
    val runs   = probe.fired(timer).filter(_.outcome == FiredTimer.Outcome.Done)
    val period = runs.flatMap(_.period).headOption.getOrElse(fail(s"'$timer' has no period"))
    (runs.map(_.dueTime), period)

  protected def assertSpaced(dues: Vector[Instant], periodMillis: Long, what: String): Unit =
    assert(dues.size >= 2, s"$what has ${dues.size} due time(s); spacing needs two")
    dues.zip(dues.tail).foreach { (a, b) =>
      assertEquals(
        JDuration.between(a, b).toMillis,
        periodMillis,
        s"$what: $b is not one period after $a (${dues.mkString(", ")})"
      )
    }

  // ── the service ─────────────────────────────────────────────────────────────

  Given("a service {string} with a timed action {string}") { (service: String, name: String) =>
    declareAction(service, name)
  }

  Given("a test that starts the service {string} with the test kit") { (service: String) =>
    this.service = Some(service)
  }

  Given("the service {string} has a timed action {string} with a handler {string}") {
    (service: String, name: String, handler: String) =>
      declareAction(service, name)
      declareHandler(handler, name, Behaviour.Done)
  }

  Given("a handler {string} of {string}") { (handler: String, name: String) =>
    declareHandler(handler, name, Behaviour.Done)
  }

  Given("a handler {string} of {string} that fails") { (handler: String, name: String) =>
    declareHandler(handler, name, Behaviour.Fails)
  }

  Given("a handler {string} of {string} that fails {int} time(s) and then runs without a failure") {
    (handler: String, name: String, times: Int) =>
      declareHandler(handler, name, Behaviour.FailsThenDone(times))
  }

  Given("a handler {string} of {string} that takes {string}") {
    (handler: String, name: String, takes: String) =>
      declareHandler(handler, name, Behaviour.Takes(duration(takes)))
  }

  Given("the handler {string} takes {string}") { (handler: String, takes: String) =>
    ofHandler(handler).script(handler, Behaviour.Takes(duration(takes)))
  }

  Given("a handler {string} of {string} that cancels the timer {string}") {
    (handler: String, name: String, timer: String) =>
      declareHandler(handler, name, Behaviour.Cancels(timer))
  }

  Given(
    "a handler {string} of {string} that sets the timer {string} for {string} again, due {string} later"
  ) { (handler: String, name: String, timer: String, target: String, delay: String) =>
    assertEquals(target, handler, "a handler sets its own timer again")
    declareHandler(handler, name, Behaviour.SetsAgain(timer, duration(delay)))
  }

  Given(
    "a handler {string} of {string} that, the first time it runs, sets the recurring timer {string} for {string} with a period of {string}"
  ) { (handler: String, name: String, timer: String, target: String, period: String) =>
    declareHandler(handler, name, Behaviour.SetsRecurringOnce(timer, target, duration(period)))
  }

  // ── setting, cancelling, asking ─────────────────────────────────────────────

  Given("the timer {string} set for {string}") { (timer: String, handler: String) =>
    setTimer(timer, handler)
  }

  Given("the timer {string} set for {string} with no period") { (timer: String, handler: String) =>
    setTimer(timer, handler)
  }

  Given("the recurring timer {string} set for {string} with a period of {string}") {
    (timer: String, handler: String, period: String) =>
      setRecurring(timer, handler, duration(period))
  }

  Given(
    "the recurring timer {string} set for {string}, due {string} later, with a period of {string}"
  ) { (timer: String, handler: String, delay: String, period: String) =>
    setRecurring(timer, handler, duration(period), duration(delay))
  }

  Given(
    "the recurring timer {string} set for {string} with a period of {string} and the value {string}"
  ) { (timer: String, handler: String, period: String, value: String) =>
    setRecurring(timer, handler, duration(period), value = Some(value))
  }

  Given(
    "the recurring timer {string} set for {string} with a period of {string} and a value as large as the limit for a timer"
  ) { (timer: String, handler: String, period: String) =>
    val value = "x" * TimerRuntime.MaxPayloadBytes
    largeValue = Some(value)
    setRecurring(timer, handler, duration(period), value = Some(value))
  }

  When("a handler of {string} sets the timer {string} for {string} with no period") {
    (_: String, timer: String, handler: String) =>
      quietMoment(timer)
      setTimer(timer, handler)
  }

  When(
    "a handler of {string} sets the recurring timer {string} for {string} with a period of {string}"
  ) { (_: String, timer: String, handler: String, period: String) =>
    if isStarted && probe.scheduled(timer).exists(_.period.isDefined) then quietMoment(timer)
    try setRecurring(timer, handler, duration(period))
    catch case refused: IllegalArgumentException => refusal = Some(refused.getMessage)
  }

  When(
    "a handler of {string} sets the recurring timer {string} for {string} with a period of {string} again"
  ) { (_: String, timer: String, handler: String, period: String) =>
    quietMoment(timer)
    beforeSetAgain += timer -> scheduled(timer).dueTime
    setRecurring(timer, handler, duration(period), delay = 5.seconds)
  }

  When(
    "a handler of {string} sets the recurring timer {string} for {string} with a period of {string} and the value {string}"
  ) { (_: String, timer: String, handler: String, period: String, value: String) =>
    quietMoment(timer)
    beforeSetAgain += timer -> scheduled(timer).dueTime
    setRecurring(timer, handler, duration(period), delay = 5.seconds, value = Some(value))
  }

  When("a handler of {string} asks whether the timer {string} exists") {
    (_: String, timer: String) =>
      answered = Some(scheduler.exists(timer))
  }

  When("a handler of {string} cancels the timer {string}") { (_: String, timer: String) =>
    scheduler.delete(timer)
  }

  // ── firing ──────────────────────────────────────────────────────────────────

  When("the timer {string} fires") { (timer: String) =>
    val _ = awaitRuns(timer, 1)
  }

  When("the timer {string} fires and its handler finishes") { (timer: String) =>
    val _ = awaitRuns(timer, 1)
  }

  When("the timer {string} has fired {int} time(s)") { (timer: String, count: Int) =>
    val _ = awaitRuns(timer, count, within = (count * 15).seconds)
  }

  When("the timers {string} and {string} have each fired {int} times") {
    (first: String, second: String, count: Int) =>
      val _ = awaitRuns(first, count, within = (count * 15).seconds)
      val _ = awaitRuns(second, count, within = (count * 15).seconds)
  }

  // ── what is there ───────────────────────────────────────────────────────────

  Then("{string} has no timer {string}") { (_: String, timer: String) =>
    val _ = waitFor(s"the timer '$timer' is gone", 5.seconds)(
      Option.when(!scheduler.exists(timer))(())
    )
  }

  Then("{string} then has no timer {string}") { (_: String, timer: String) =>
    val _ = waitFor(s"the timer '$timer' is gone", 5.seconds)(
      Option.when(!scheduler.exists(timer))(())
    )
  }

  Then("{string} has the timer {string}") { (_: String, timer: String) =>
    assert(scheduler.exists(timer), s"there is no timer '$timer'")
  }

  Then("the handler is told that the timer {string} exists") { (timer: String) =>
    assertEquals(answered, Some(true), s"the handler was not told that '$timer' exists")
  }

  Then("the handler is refused") { () =>
    assert(refusal.isDefined, "the handler was not refused")
  }

  Then("the refusal names the timer {string}") { (timer: String) =>
    val message = refusal.getOrElse(fail("the handler was not refused"))
    assert(message.contains(timer), s"'$message' does not name '$timer'")
  }

  Then("the timer {string} does not fire again") { (timer: String) =>
    val before = runsOf(timer).size
    Thread.sleep(3000)
    assertEquals(runsOf(timer).size, before, s"the timer '$timer' fired again")
  }

  // ── timers that fire once ───────────────────────────────────────────────────

  Then("the timer {string} fires a second time within {string}") {
    (timer: String, within: String) =>
      val first    = awaitRuns(timer, 1).head
      val deadline = first.finishedAt.plusMillis(duration(within).toMillis)
      val left     = JDuration.between(Instant.now(), deadline).toMillis.max(0L)
      val second   = awaitRuns(timer, 2, within = left.millis)(1)
      assert(!second.startedAt.isAfter(deadline), s"the second fire was at ${second.startedAt}")
  }

  Then("the due time of {string} is {string} after the handler set it") {
    (timer: String, after: String) =>
      val run = runsOf(timer).find(_.setBefore.isDefined).getOrElse(fail("the handler set nothing"))
      val due = storedDueAt(timer).getOrElse(fail(s"there is no timer '$timer'"))
      val gap = duration(after).toMillis
      // Truncated to the millisecond where it is made, so a due may be a millisecond earlier.
      val earliest = run.setBefore.get.plusMillis(gap - 1)
      val latest   = run.setAfter.get.plusMillis(gap)
      assert(
        !due.isBefore(earliest) && !due.isAfter(latest),
        s"the due time $due is not $after after the handler set it ($earliest to $latest)"
      )
  }

  Then("the timer {string} fires again after a backoff of {string}") {
    (timer: String, backoff: String) =>
      val runs = awaitRuns(timer, 2, within = duration(backoff) + 10.seconds)
      assertBackoff(runs(0), runs(1), duration(backoff))
  }

  Then("the handler is then told that the timer has failed {int} time(s)") { (times: Int) =>
    val run = runsAll.lastOption.getOrElse(fail("no handler has run"))
    assertEquals(run.previousAttempts, times)
  }

  Then("the handler is told the due time the timer {string} was set with") { (timer: String) =>
    val run = awaitRuns(timer, 1).head
    assertEquals(run.dueTime, setDue(timer))
    val (before, after) = setAt(timer)
    // 300 ms after it was set, in whole milliseconds.
    assert(
      !run.dueTime.isBefore(before.truncatedTo(ChronoUnit.MILLIS).plusMillis(300)) &&
        !run.dueTime.isAfter(after.plusMillis(300)),
      s"the due time ${run.dueTime} is not the one the timer was set with"
    )
  }

  Then("the handler was told the same due time each time") { () =>
    val timer = current.getOrElse(fail("no timer has been named"))
    val dues  = runsOf(timer).map(_.dueTime).distinct
    assert(runsOf(timer).size >= 2, "the handler ran once")
    assertEquals(dues.size, 1, s"the handler was told ${dues.mkString(", ")}")
  }

  // ── cadence ─────────────────────────────────────────────────────────────────

  Then("the first due time it fired for is {string} after it was set") { (after: String) =>
    val timer           = current.getOrElse(fail("no timer has been named"))
    val first           = cadence(timer)._1.head
    val (before, until) = setAt(timer)
    val delay           = duration(after).toMillis
    assert(
      !first.isBefore(before.truncatedTo(ChronoUnit.MILLIS).plusMillis(delay)) &&
        !first.isAfter(until.plusMillis(delay)),
      s"the first due time $first is not $after after the timer was set ($before)"
    )
  }

  Then("the first due time it fired for is the time it was set") { () =>
    val timer           = current.getOrElse(fail("no timer has been named"))
    val first           = cadence(timer)._1.head
    val (before, until) = setAt(timer)
    assert(
      !first.isBefore(before.truncatedTo(ChronoUnit.MILLIS)) && !first.isAfter(until),
      s"the first due time $first is not when the timer was set ($before)"
    )
  }

  Then("each due time after the first is {string} after the due time before it") { (gap: String) =>
    val timer = current.getOrElse(fail("no timer has been named"))
    assertSpaced(cadence(timer)._1, duration(gap).toMillis, timer)
  }

  Then("the second due time is {string} after the first") { (gap: String) =>
    val timer = current.getOrElse(fail("no timer has been named"))
    assertSpaced(cadence(timer)._1.take(2), duration(gap).toMillis, timer)
  }

  Then("each due time it fired for is one period after the due time before it") { () =>
    val timer          = current.getOrElse(fail("no timer has been named"))
    val (dues, period) = cadence(timer)
    assertSpaced(dues, period, timer)
  }

  Then("each due time {string} fired for is {string} after the due time before it") {
    (timer: String, gap: String) =>
      assertSpaced(cadence(timer)._1, duration(gap).toMillis, timer)
  }

  Then("the next due time of {string} is {string} after the due time it fired for") {
    (timer: String, gap: String) =>
      val run = probe.fired(timer).headOption.getOrElse(fail(s"'$timer' has not fired"))
      assertEquals(run.next, Some(run.dueTime.plusMillis(duration(gap).toMillis)))
      assertEquals(scheduled(timer).dueTime, run.next.get)
  }

  Then("the handler {string} was told {int} due times") { (handler: String, count: Int) =>
    assert(
      runsOfHandler(handler).size >= count,
      s"'$handler' ran ${runsOfHandler(handler).size} time(s)"
    )
  }

  Then("each due time it was told is one period after the due time before it") { () =>
    val timer  = current.getOrElse(fail("no timer has been named"))
    val period = scheduled(timer).period.getOrElse(fail(s"'$timer' has no period")).toMillis
    val told   = runsOf(timer).map(_.dueTime)
    assertSpaced(told, period, s"what the handler of '$timer' was told")
    // And it is what the runtime ran it for.
    assertEquals(told.take(cadence(timer)._1.size), cadence(timer)._1.take(told.size))
  }

  Then("the test reads {int} due times for the timer {string}") { (count: Int, timer: String) =>
    assert(probe.dueTimes(timer).size >= count, s"the test read ${probe.dueTimes(timer).size}")
  }

  Then("each due time is one period after the due time before it") { () =>
    val timer          = current.getOrElse(fail("no timer has been named"))
    val (dues, period) = cadence(timer)
    assertSpaced(dues, period, timer)
  }

  // ── replacing and setting again ─────────────────────────────────────────────

  Then("the timer {string} fires once more, at the due time the handler gave it") {
    (timer: String) =>
      val expected = setDue(timer)
      val _ = waitFor(s"'$timer' fires for $expected", 10.seconds)(
        probe.fired(timer).find(f => f.dueTime == expected && f.outcome == FiredTimer.Outcome.Done)
      )
  }

  Then("the timer {string} fires once for each period") { (timer: String) =>
    val after = probe.fired(timer).size
    val _ = waitFor(s"'$timer' runs three times as a recurring timer", 20.seconds)(
      Option(probe.fired(timer).drop(after).filter(_.period.isDefined)).filter(_.size >= 3)
    )
    val runs = probe.fired(timer).drop(after).filter(_.period.isDefined)
    assertSpaced(runs.map(_.dueTime), runs.head.period.get, timer)
  }

  Then("the next due time of {string} is the one it had before it was set again") { (timer: String) =>
    assertEquals(scheduled(timer).dueTime, beforeSetAgain(timer))
  }

  Then("the handler {string} is given {string} the next time the timer {string} fires") {
    (handler: String, value: String, timer: String) =>
      val ran  = runsOf(timer).size
      val next = waitFor(s"'$timer' fires again", 10.seconds)(runsOf(timer).drop(ran).headOption)
      assertEquals(next.handler, handler)
      assertEquals(next.input, value)
  }

  Then("the next due time of {string} is the one the handler that set it again gave it") {
    (timer: String) =>
      val (before, until) = setAt(timer)
      // Set again with no delay, so its next due is when it was set: it may already have run for it.
      val _ = waitFor(s"'$timer' has a due from when it was set again", 10.seconds)(
        (probe.fired(timer).map(_.dueTime) ++ probe.scheduled(timer).map(_.dueTime))
          .find(d => !d.isBefore(before.truncatedTo(ChronoUnit.MILLIS)) && !d.isAfter(until))
      )
  }

  Then("the timer {string} runs {string} once for each period of {string}") {
    (timer: String, handler: String, period: String) =>
      val (before, _) = setAt(timer)
      val run = waitFor(s"'$handler' runs for '$timer'", 10.seconds)(
        runsOfHandler(handler).find(r => r.timerName == timer && !r.startedAt.isBefore(before))
      )
      val fired = waitFor(s"the runtime records the run of '$handler'", 5.seconds)(
        probe.fired(timer).find(_.dueTime == run.dueTime)
      )
      val periodMillis = duration(period).toMillis
      assertEquals(fired.period, Some(periodMillis))
      assertEquals(fired.next, Some(run.dueTime.plusMillis(periodMillis)))
      assertEquals(scheduled(timer).target.method.toString, handler)
  }

  Then("the handler {string} was given that value each time") { (handler: String) =>
    val value = largeValue.getOrElse(fail("no value was given"))
    val runs  = runsOfHandler(handler)
    assert(runs.size >= 2, s"'$handler' ran ${runs.size} time(s)")
    runs.foreach(run => assertEquals(run.input.length, value.length))
    assert(runs.forall(_.input == value), "a run was given another value")
  }

  // ── restarts and failures ───────────────────────────────────────────────────

  private var dueBeforeRestart: Option[Instant] = None

  When("{string} restarts before the next due time of {string}") { (_: String, timer: String) =>
    dueBeforeRestart = Some(scheduled(timer).dueTime)
    started.restartService()
  }

  When("{string} stops, and starts again {string} later") { (_: String, later: String) =>
    val timer = current.getOrElse(fail("no timer has been named"))
    dueBeforeRestart = Some(scheduled(timer).dueTime)
    started.restartService(downFor = duration(later))
  }

  Then("the timer {string} fires for the due time it had before the restart") { (timer: String) =>
    val due = dueBeforeRestart.getOrElse(fail("nothing restarted"))
    val _ = waitFor(s"'$timer' fires for $due", 20.seconds)(
      probe.fired(timer).find(f => f.dueTime == due && f.outcome == FiredTimer.Outcome.Done)
    )
  }

  Then("each due time it fires for afterwards is one period after the due time before it") { () =>
    val timer = current.getOrElse(fail("no timer has been named"))
    val due   = dueBeforeRestart.getOrElse(fail("nothing restarted"))
    val after = waitFor(s"'$timer' fires twice more after $due", 30.seconds)(
      Option(cadence(timer)._1.filterNot(_.isBefore(due))).filter(_.size >= 3)
    )
    assertSpaced(after, cadence(timer)._2, timer)
  }

  Then("the timer {string} fires once for the due time it had when {string} stopped") {
    (timer: String, _: String) =>
      val due = dueBeforeRestart.getOrElse(fail("nothing stopped"))
      val _ = waitFor(s"'$timer' fires for $due", 20.seconds)(
        probe.fired(timer).find(f => f.dueTime == due && f.outcome == FiredTimer.Outcome.Done)
      )
      assertEquals(probe.fired(timer).count(_.dueTime == due), 1, s"'$timer' fired twice for $due")
  }

  Then(
    "its next due time is the first still to come that is a whole number of periods after that due time"
  ) { () =>
    assertFirstToCome(current.get, dueBeforeRestart.getOrElse(fail("nothing stopped")))
  }

  Then(
    "its next due time is the first still to come that is a whole number of periods after the due time it failed for"
  ) { () =>
    val timer = current.get
    assertFirstToCome(timer, runsOf(timer).head.dueTime)
  }

  Then(
    "its next due time is the first still to come that is a whole number of periods after the due time it fired for"
  ) { () =>
    val timer = current.get
    assertFirstToCome(timer, runsOf(timer).head.dueTime)
  }

  Then(
    "the timer {string} does not fire for the due times that passed while {string} was not running"
  ) { (timer: String, _: String) =>
    assertSkipped(timer, dueBeforeRestart.getOrElse(fail("nothing stopped")))
  }

  Then("the timer {string} does not fire for the due times that passed while its handler failed") {
    (timer: String) => assertSkipped(timer, runsOf(timer).head.dueTime)
  }

  Then("the timer {string} does not fire for the due times that passed while its handler ran") {
    (timer: String) => assertSkipped(timer, runsOf(timer).head.dueTime)
  }

  Then("the timer fired again after a backoff of {string}, and then of {string}") {
    (first: String, second: String) =>
      val runs = runsOf(current.get)
      assert(runs.size >= 3, s"the timer ran ${runs.size} time(s)")
      assertBackoff(runs(0), runs(1), duration(first))
      assertBackoff(runs(1), runs(2), duration(second))
  }

  Then("the handler was told that the timer had failed {int} time, and then {int} times") {
    (first: Int, second: Int) =>
      val runs = runsOf(current.get)
      assertEquals(runs.take(3).map(_.previousAttempts), Vector(0, first, second))
      // A recurring timer's retry is for the due that failed: all three runs were told one due time.
      assertEquals(
        runs.take(3).map(_.dueTime).distinct.size,
        1,
        runs.take(3).map(_.dueTime).toString
      )
  }

  Then("the next due time of {string} is {string} after the due time it failed for") {
    (timer: String, gap: String) =>
      val failedFor = runsOf(timer).head.dueTime
      val done = probe
        .fired(timer)
        .find(_.outcome == FiredTimer.Outcome.Done)
        .getOrElse(fail("it never succeeded"))
      assertEquals(done.dueTime, failedFor)
      assertEquals(done.next, Some(failedFor.plusMillis(duration(gap).toMillis)))
  }

  When("{string} have passed since the timer {string} first fired") {
    (passed: String, timer: String) =>
      val first = awaitRuns(timer, 1).head
      val until = first.startedAt.plusMillis(duration(passed).toMillis)
      val wait  = JDuration.between(Instant.now(), until).toMillis
      if wait > 0 then Thread.sleep(wait)
  }

  Then("the timer {string} has fired no more than {int} times") { (timer: String, most: Int) =>
    assert(runsOf(timer).size <= most, s"the timer fired ${runsOf(timer).size} times")
  }

  /**
   * The run for `from` gave the timer the first cadence point after it still to come: a whole
   * number of periods after `from`, after the run, and no more than a period after it.
   */
  protected def assertFirstToCome(timer: String, from: Instant): Unit =
    val run = waitFor(s"'$timer' succeeds for $from", 30.seconds)(
      probe.fired(timer).find(f => f.dueTime == from && f.outcome == FiredTimer.Outcome.Done)
    )
    val handled = runsOf(timer).filter(_.dueTime == from).last
    val period  = run.period.getOrElse(fail(s"'$timer' has no period"))
    val next    = run.next.getOrElse(fail(s"'$timer' was given no next due"))
    val gap     = JDuration.between(from, next).toMillis
    assertEquals(gap % period, 0L, s"$next is not a whole number of periods after $from")
    assert(next.isAfter(handled.finishedAt), s"$next had passed when the handler finished")
    assert(
      !next.minusMillis(period).isAfter(Instant.now()),
      s"$next is more than a period after the run; an earlier cadence point was still to come"
    )

  /**
   * No run was for a due time between `from` and the next due its run gave — and there were some.
   */
  protected def assertSkipped(timer: String, from: Instant): Unit =
    val run = probe
      .fired(timer)
      .find(f => f.dueTime == from && f.outcome == FiredTimer.Outcome.Done)
      .getOrElse(fail(s"'$timer' did not succeed for $from"))
    val next   = run.next.get
    val period = run.period.get
    assert(
      JDuration.between(from, next).toMillis > period,
      s"no due time passed between $from and $next, so nothing was skipped"
    )
    val between = probe.fired(timer).map(_.dueTime).filter(d => d.isAfter(from) && d.isBefore(next))
    assertEquals(between, Vector.empty, s"'$timer' fired for due times that had passed")

  /** A retry starts no earlier than its backoff after the failure, and not long after it. */
  protected def assertBackoff(failed: Run, retry: Run, backoff: FiniteDuration): Unit =
    val gap = JDuration.between(failed.finishedAt, retry.startedAt).toMillis
    // The backoff is counted from when the sweeper wrote it, a moment after the handler returned,
    // so the gap from the handler's return is the backoff and a little more, never less than the
    // backoff less that moment.
    assert(
      gap >= backoff.toMillis - 250 && gap <= backoff.toMillis + 2000,
      s"the retry came ${gap} ms after the failure; the backoff is $backoff"
    )

  protected def runsAll: Vector[Run] =
    actions.values.flatMap(_.runs).toVector.sortBy(_.startedAt)

object TimerSteps:

  private val Quantity = """(-?\d+) (millisecond|second|minute|hour|day)s?""".r

  /** A duration as a scenario writes it: "1 second", "1500 milliseconds", "-1 second". */
  def duration(text: String): FiniteDuration = text.trim match
    case Quantity(amount, unit) =>
      val n = amount.toLong
      unit match
        case "millisecond" => n.millis
        case "second"      => n.seconds
        case "minute"      => n.minutes
        case "hour"        => n.hours
        case "day"         => n.days
    case other => throw IllegalArgumentException(s"'$other' is not a duration")
