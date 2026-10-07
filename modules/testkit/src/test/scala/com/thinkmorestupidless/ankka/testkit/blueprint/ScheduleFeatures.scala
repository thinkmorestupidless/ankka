package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, RuntimeExtension, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  LogCapturing,
  MovableClock
}

import java.time.{
  DayOfWeek,
  Duration,
  Instant,
  LocalDate,
  LocalDateTime,
  LocalTime,
  ZoneId,
  ZonedDateTime
}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * `features/blueprints/schedules.feature`, against a real journal and the service's timers on a
 * clock the suite moves. The blueprint of the background, `digest`, is one call step that keeps its
 * input, so a run needs no model; what matters is when runs start and what period each has.
 *
 * The clock moves forward only, and the scenarios' dates do not, so every scenario after the first
 * starts the service again with a fresh clock at 1 October 2026.
 */
class ScheduleFeatures
    extends GherkinSuite("../../features/blueprints/schedules.feature")
    with LogCapturing:

  override val munitTimeout = 10.minutes

  // ── The fixture ───────────────────────────────────────────────────────────

  private val start = Instant.parse("2026-10-01T00:00:00Z")
  private val echo  = BlueprintHandler("echo")((_, input) => input)

  private var clock: MovableClock              = MovableClock.at(start)
  private var agents: AgentRuntime             = null
  private var timers: TimerRuntime             = null
  private var kit: AnkkaTestKit                = null
  private var peers: Vector[AnkkaTestKit.Peer] = Vector.empty
  private var unused                           = true

  /** A fresh set of extensions on the current clock; the main service's are kept for the suite. */
  private def extensions(carrying: Blueprint*): Seq[RuntimeExtension] =
    val (a, t) = fresh(carrying*)
    agents = a
    timers = t
    Seq(a, t, ProjectionRuntime())

  private def fresh(carrying: Blueprint*): (AgentRuntime, TimerRuntime) =
    val a =
      AgentRuntime().withBlueprints(_ => BlueprintRegistry.empty.handlers(echo).carrying(carrying*))
    val t = TimerRuntime(pollInterval = 200.millis, clock = clock)
    (a, t)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(AgentRuntime.descriptors, extensions())

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    pending = null
    schedule = null
    lastReached = None
    reached = 0
    listed = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    peers.foreach(_.stop())
    peers = Vector.empty
    // The next scenario's clock starts again from 1 October: this one's pending timer must not
    // fire into it.
    try
      val record =
        kit.componentClient.forEventSourcedEntity(EntityId(name)).call(BlueprintEntity.get).invoke()
      record.nextDue.foreach(due =>
        timers.timerScheduler.delete(ScheduleTimer.timerName(name, Instant.ofEpochMilli(due)))
      )
    catch case NonFatal(_) => ()

  private def name = s"$scenarioId-digest"
  private def runs = agents.runs
  private def zone = ZoneId.of(schedule.zone)
  private def local(date: String, time: String): Instant =
    LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant
  private def dayStart(date: String): Instant = LocalDate.parse(date).atStartOfDay(zone).toInstant
  private def dueOn(date: String): Instant =
    val due = schedule.next(dayStart(date))
    assertEquals(
      ZonedDateTime.ofInstant(due, zone).toLocalDate,
      LocalDate.parse(date),
      s"no due time on $date"
    )
    due

  private var pending: Blueprint          = null
  private var schedule: Schedule          = null
  private var lastReached: Option[String] = None
  private var reached                     = 0
  private var listed: Vector[RunSnapshot] = Vector.empty

  /** Moves the clock to a due time and waits for its run to be held. */
  private def reach(due: Instant, counted: Boolean = true): Unit =
    clock.moveTo(due)
    val id = ScheduleTimer.runId(name, due)
    kit.eventually(s"the run at $due is started", 30.seconds)(
      try Some(runs.get(id))
      catch case e: CommandError if e.code == ErrorCode.NotFound => None
    ): Unit
    lastReached = Some(id)
    if counted then reached += 1

  private def period(run: RunSnapshot): Period =
    Period
      .fromJson(run.input)
      .getOrElse(fail(s"the run's input is not a period: ${run.input.render}"))

  private def allRuns(atLeast: Int): Vector[RunSnapshot] =
    kit.eventually(s"$atLeast runs of '$name' are listed", 30.seconds) {
      val summaries = runs.list(name)
      Option.when(summaries.sizeIs >= atLeast)(summaries.map(s => runs.get(s.runId)))
    }

  /**
   * Runs beyond the ones reached so far, once exactly `n` more have appeared and no other follows.
   */
  private def newRuns(n: Int): Vector[RunSnapshot] =
    val all = allRuns(reached + n)
    Thread.sleep(1000) // five polls: a run that was going to start twice has had its chance
    assertEquals(runs.list(name).size, reached + n, "more runs started than the schedule asked for")
    all.drop(reached)

  private def newest: RunSnapshot = allRuns(1).maxBy(r => period(r).to)

  // ── Background ────────────────────────────────────────────────────────────

  Given(
    "the blueprint {string} with a schedule of weekly on {string} at {string} in the time zone {string}"
  ) { (_: String, day: String, time: String, zone: String) =>
    schedule =
      Schedule.weekly(DayOfWeek.valueOf(day.toUpperCase), LocalTime.parse(time), ZoneId.of(zone))
    pending = Blueprint(name)
      .step(Step("keep").call("echo").reads("input").result(Shape.any))
      .schedule(schedule)
  }

  Given("a service whose clock a test moves") { () =>
    if unused then unused = false
    else
      clock = MovableClock.at(start)
      kit.restartService(extensions = extensions())
    assertEquals(agents.blueprints.register(pending).version, 1)
  }

  // ── Due times and periods ─────────────────────────────────────────────────

  When("the clock is moved through three Sundays at {string}") { (_: String) =>
    (1 to 3).foreach(_ => reach(schedule.next(clock.instant())))
  }

  Then("three runs of {string} are started, one at each due time") { (_: String) =>
    val three = allRuns(3)
    assertEquals(three.size, 3)
    val dues = three.map(r => period(r).to)
    assertEquals(three.map(_.runId), dues.map(d => ScheduleTimer.runId(name, d)))
    dues.zip(dues.tail).foreach((a, b) => assertEquals(schedule.next(a), b))
    three.foreach(r => assertEquals(r.startedBy, ScheduleTimer.StartedBy))
  }

  Given("a run of {string} started at the due time on {string}") { (_: String, date: String) =>
    reach(dueOn(date))
  }

  When("the clock reaches the due time on {string}") { (date: String) =>
    reach(dueOn(date))
  }

  Then("a run of {string} is started whose period is from {string} to {string}") {
    (_: String, from: String, to: String) =>
      val run           = runs.get(lastReached.getOrElse(fail("no run reached")))
      val Array(fd, ft) = from.split(" "): @unchecked
      val Array(td, tt) = to.split(" "): @unchecked
      assertEquals(period(run).from, local(fd, ft))
      assertEquals(period(run).to, local(td, tt))
  }

  Given("three runs of {string} started at three due times in a row") { (_: String) =>
    (1 to 3).foreach(_ => reach(schedule.next(clock.instant())))
  }

  When("a reader lists the runs of {string}") { (_: String) =>
    listed = allRuns(reached)
  }

  Then("each run's period starts where the previous run's period ended") { () =>
    assertEquals(listed.size, 3)
    listed.zip(listed.tail).foreach((a, b) => assertEquals(period(b).from, period(a).to))
  }

  // ── Outages ───────────────────────────────────────────────────────────────

  Given("the service stopped from {string} to {string}") { (from: String, to: String) =>
    clock.moveTo(dayStart(from))
    kit.stopService()
    clock.moveTo(dayStart(to))
  }

  When("the service starts on {string}") { (date: String) =>
    assertEquals(ZonedDateTime.ofInstant(clock.instant(), zone).toLocalDate, LocalDate.parse(date))
    kit.startService(extensions())
  }

  Then("one run of {string} is started") { (_: String) =>
    assertEquals(newRuns(1).size, 1)
  }

  Then("its period is from {string} to {string}") { (from: String, to: String) =>
    val Array(fd, ft) = from.split(" "): @unchecked
    val Array(td, tt) = to.split(" "): @unchecked
    assertEquals(period(newest).from, local(fd, ft))
    assertEquals(period(newest).to, local(td, tt))
  }

  Given("the schedule of {string} asks for one run per missed period") { (_: String) =>
    schedule = schedule.perMissedPeriod
    pending = pending.schedule(schedule)
    assertEquals(agents.blueprints.register(pending).version, 2)
  }

  Then("two runs of {string} are started, the older first") { (_: String) =>
    listed = newRuns(2)
    assertEquals(listed.size, 2)
    assert(period(listed(0)).to.isBefore(period(listed(1)).to), "the older run is not first")
    assert(listed(0).startedAt <= listed(1).startedAt, "the older run was not started first")
  }

  Then("their periods are from {string} to {string} and from {string} to {string}") {
    (from1: String, to1: String, from2: String, to2: String) =>
      def at(s: String) = { val Array(d, t) = s.split(" "): @unchecked; local(d, t) }
      assertEquals(
        listed.map(period).map(p => (p.from, p.to)),
        Vector((at(from1), at(to1)), (at(from2), at(to2)))
      )
  }

  // ── The clocks change ─────────────────────────────────────────────────────

  Given("the clocks in {string} go back on {string}") { (zoneName: String, date: String) =>
    val rules      = ZoneId.of(zoneName).getRules
    val transition = rules.getTransition(LocalDate.parse(date).atTime(1, 30))
    assert(
      transition != null && transition.isOverlap,
      s"the clocks in $zoneName do not go back on $date"
    )
    // Up to the week before: the run on the day they change covers the change.
    val day = dayStart(date)
    Iterator
      .iterate(schedule.next(clock.instant()))(schedule.next)
      .takeWhile(_.isBefore(day))
      .foreach(reach(_))
  }

  Then("the run of {string} is started at {string} in {string}") {
    (_: String, time: String, zoneName: String) =>
      val run = runs.get(lastReached.getOrElse(fail("no run reached")))
      assertEquals(
        ZonedDateTime.ofInstant(period(run).to, ZoneId.of(zoneName)).toLocalTime,
        LocalTime.parse(time)
      )
  }

  Then("its period is one hour longer than a week") { () =>
    val run = runs.get(lastReached.getOrElse(fail("no run reached")))
    assertEquals(period(run).length, Duration.ofDays(7).plusHours(1))
  }

  // ── Versions ──────────────────────────────────────────────────────────────

  Given("the blueprint {string} held at blueprint version 1") { (_: String) =>
    assertEquals(agents.blueprints.versions(name).map(_.number), Vector(1))
  }

  When("the service registers blueprint version 2 of {string} before the next due time") {
    (_: String) =>
      assertEquals(agents.blueprints.register(pending.runBudget(5)).version, 2)
  }

  Then("the run started at the next due time names blueprint version 2") { () =>
    reach(schedule.next(clock.instant()))
    assertEquals(runs.get(lastReached.get).version, 2)
  }

  Given("a run of {string} started at the due time on {string} at blueprint version 1") {
    (_: String, date: String) =>
      reach(dueOn(date))
      assertEquals(runs.get(lastReached.get).version, 1)
  }

  When("the service starts on {string} carrying blueprint version 2 of {string}") {
    (date: String, _: String) =>
      assertEquals(
        ZonedDateTime.ofInstant(clock.instant(), zone).toLocalDate,
        LocalDate.parse(date)
      )
      kit.startService(extensions(pending.runBudget(5)))
      assertEquals(agents.blueprints.versions(name).map(_.number), Vector(1, 2))
  }

  Then("the run started when the service is back names blueprint version 2") { () =>
    assertEquals(newRuns(1).map(_.version), Vector(2))
  }

  // ── Stopping ──────────────────────────────────────────────────────────────

  When("the service registers a blueprint version of {string} without a schedule") { (_: String) =>
    assertEquals(agents.blueprints.register(pending.copy(schedule = None)).version, 2)
  }

  When("the clock is moved past the next Sunday at {string}") { (_: String) =>
    clock.moveTo(schedule.next(clock.instant()).plusSeconds(1))
    Thread.sleep(1500) // several polls
  }

  Then("no run of {string} is started") { (_: String) =>
    assertEquals(runs.list(name), Vector.empty)
  }

  // ── Instances ─────────────────────────────────────────────────────────────

  Given("the service running as three instances") { () =>
    peers = Vector.fill(2) {
      val (a, t) = fresh()
      kit.startPeer(Seq(a, t))
    }
  }

  When("the clock reaches the next due time") { () =>
    reach(schedule.next(clock.instant()), counted = false)
  }
