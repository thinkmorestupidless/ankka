package com.thinkmorestupidless.ankka.testkit.timers

import com.thinkmorestupidless.ankka.core.{ComponentId, MethodName}
import com.thinkmorestupidless.ankka.core.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.runtime.{Database, FiredTimer, Observability, Sweep}
import com.thinkmorestupidless.ankka.sdk.{
  SimpleTimedActionContext,
  TimedAction,
  TimedActionDescriptor
}
import org.apache.pekko.actor.typed.ActorSystem

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * `features/timers/upgrading.feature`, run against a real service and the timer table.
 *
 * Two things stand in for an instance from before. An instance whose *runtime* has no recurring
 * timers is [[LegacyTimers]]: the previous release's statements, run on a loop as its sweeper ran
 * them. An instance of an older *service*, whose registry lacks a handler, is this branch's own
 * `Sweep` built over the service's database with a descriptor that does not declare it. Neither is
 * the previous binary: that it starts against the new schema is a run by hand, of the previous
 * release's own timer suite (the quickstart says how).
 *
 * The service is started without a timer runtime, so nothing sweeps but the stand-in until a
 * scenario says an instance with recurring timers takes over, and then one is started in it.
 *
 * Beside the scenarios: the first of the two retries that are told a best-effort due time during an
 * upgrade.
 */
final class TimerUpgradeFeatures extends TimerSteps("../../features/timers/upgrading.feature"):

  @volatile private var sweeping: Option[Thread]        = None
  @volatile private var batches                         = 0
  @volatile private var batchFailure: Option[Throwable] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    withTimerRuntime = false
    batches = 0
    batchFailure = None

  override def afterEach(context: AfterEach): Unit =
    try stopSweeping()
    finally super.afterEach(context)

  private def database: Database =
    given ActorSystem[?] = started.service.system
    Database()

  /** Every service here has both timed actions the feature's timers name. */
  private def describe(service: String): Unit =
    declareAction(service, "reminders")
    declareHandler("nudge", "reminders", Behaviour.Done)
    declareAction(service, "cleanup")
    declareHandler("sweep", "cleanup", Behaviour.Done)

  /** Runs `batch` every poll interval, on its own thread, until another sweeper takes over. */
  private def sweepWith(batch: () => Unit): Unit =
    stopSweeping()
    val thread = Thread.ofVirtual().start { () =>
      try
        while !Thread.currentThread().isInterrupted do
          try
            batch()
            batches += 1
          catch
            case _: InterruptedException => Thread.currentThread().interrupt()
            case failure: Throwable      => batchFailure = Some(failure)
          Thread.sleep(pollInterval.toMillis)
      catch case _: InterruptedException => ()
    }
    sweeping = Some(thread)

  private def stopSweeping(): Unit =
    sweeping.foreach { thread =>
      thread.interrupt()
      thread.join(10_000)
    }
    sweeping = None

  /** The previous release's sweeper, running the handlers this service has. */
  private def legacySweeper(): Unit =
    val _                                   = started
    given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    sweepWith { () =>
      val _ = LegacyTimers.sweep(database) { due =>
        val kit        = action(due.componentId)
        val descriptor = kit.descriptor.asInstanceOf[TimedActionDescriptor[TimedAction]]
        runHandler(descriptor, due.name, due.method, due.payload, due.attempts, Instant.now())
      }
    }

  /** This branch's sweeper, in an instance whose registry lacks `handler`. */
  private def sweeperWithout(handler: String): Unit =
    val service = started.service
    val kit     = ofHandler(handler)
    val lacking = kit.descriptor.copy(handlers = kit.descriptor.handlers - MethodName(handler))
    val sweep = Sweep(
      database,
      Map(ComponentId(kit.componentId.toString) -> lacking),
      service.componentClient,
      service.secrets,
      service.services,
      None,
      service.system.executionContext,
      Observability(service.system),
      probe
    )
    sweepWith(() => Await.result(sweep.runBatch(), 10.seconds): Unit)

  private def runHandler(
      descriptor: TimedActionDescriptor[TimedAction],
      name: String,
      method: String,
      payload: Array[Byte],
      attempts: Int,
      due: Instant
  ): Boolean =
    descriptor.handler(MethodName(method)) match
      case None => false
      case Some(handler) =>
        val context = SimpleTimedActionContext(
          descriptor.componentId,
          started.service.componentClient,
          name,
          attempts,
          due,
          started.service.secrets,
          started.service.services
        )
        val instance = descriptor.create(context)
        instance._setContext(Some(context))
        try handler(instance, payload) == TimedActionEffect.Done
        finally instance._setContext(None)

  private def legacySet(timer: String, handler: String, delay: FiniteDuration): Unit =
    val kit = ofHandler(handler)
    current = Some(timer)
    val _ = Await.result(
      database.execute(
        LegacyTimers.Store.upsert(
          timer,
          kit.handle(handler).deferred(timer),
          Instant.now().plusMillis(delay.toMillis)
        )
      ),
      10.seconds
    )

  private def setRecurringHere(timer: String, handler: String, period: String): Unit =
    val _ = started
    setRecurring(timer, handler, TimerSteps.duration(period))

  // ── an older runtime ──────────────────────────────────────────────────────────

  Given("a service {string} made with a runtime version that has no recurring timers") {
    (service: String) =>
      describe(service)
      legacySweeper()
  }

  Given("a handler of {string} has set the timer {string}") { (_: String, timer: String) =>
    // Far enough off that the upgrade, not the old sweeper, is what runs it.
    legacySet(timer, "nudge", 3.seconds)
  }

  When("{string} is upgraded to a runtime version with recurring timers") { (_: String) =>
    stopSweeping()
    startTimerRuntime()
  }

  Then("the timer {string} fires once") { (timer: String) =>
    val first = awaitRuns(timer, 1, within = 45.seconds).head
    // However many due times had passed, one run was for them all.
    val forThem = runsOf(timer).filterNot(_.dueTime.isAfter(first.startedAt))
    assertEquals(
      forThem.size,
      1,
      s"'$timer' ran ${forThem.size} times for due times that had passed"
    )
  }

  Given("a service {string} whose database holds the recurring timer {string}") {
    (service: String, timer: String) =>
      describe(service)
      setRecurringHere(timer, "sweep", "2 seconds")
  }

  When("{string} starts from an image made with a runtime version that has no recurring timers") {
    (_: String) => legacySweeper()
  }

  Then("{string} is ready") { (_: String) =>
    val _ = waitFor("the old sweeper completes a batch", 10.seconds)(Option.when(batches >= 3)(()))
    batchFailure.foreach(failure =>
      fail("the old sweeper's batch failed on the new schema", failure)
    )
  }

  Then("a timer with no period that a handler of {string} sets fires once") { (_: String) =>
    legacySet("nudge-c1", "nudge", Duration.Zero)
    val _ = awaitRunsByHandler("nudge-c1")
    Thread.sleep(1000)
    assertEquals(runsOf("nudge-c1").size, 1)
    assertEquals(probe.scheduled("nudge-c1"), None)
  }

  Then("the timer {string} does not fire") { (timer: String) =>
    Thread.sleep(3000)
    assertEquals(runsOf(timer).size, 0, s"'$timer' fired")
  }

  Then("the database of {string} still holds the recurring timer {string}") {
    (_: String, timer: String) =>
      assert(scheduled(timer).period.isDefined, s"'$timer' is no longer recurring")
  }

  Given(
    "a service {string} with one instance made with a runtime version that has no recurring timers and one made with a runtime version that has them"
  )((service: String) => describe(service))

  Given("the instance with no recurring timers is the one that fires the timers of {string}") {
    (_: String) => legacySweeper()
  }

  Given(
    "a handler on the other instance has set the recurring timer {string} with a period of {string}"
  ) { (timer: String, period: String) =>
    setRecurringHere(timer, "sweep", period)
  }

  When("{string} pass")((passed: String) => Thread.sleep(TimerSteps.duration(passed).toMillis))

  Then("the timer {string} has not fired") { (timer: String) =>
    assertEquals(runsOf(timer).size, 0, s"'$timer' fired")
  }

  Then("{string} has the recurring timer {string}") { (_: String, timer: String) =>
    assert(scheduled(timer).period.isDefined, s"'$timer' is no longer recurring")
  }

  Given("a service {string} whose recurring timer {string} has a period of {string}") {
    (service: String, timer: String, period: String) =>
      describe(service)
      setRecurringHere(timer, "sweep", period)
  }

  Given(
    "several due times of {string} have passed while an instance with no recurring timers fired the timers of {string}"
  ) { (_: String, _: String) =>
    legacySweeper()
    Thread.sleep(7000)
    assertEquals(runsAll.size, 0, "the old sweeper ran a recurring timer")
  }

  When("an instance with recurring timers becomes the one that fires the timers of {string}") {
    (_: String) =>
      stopSweeping()
      startTimerRuntime()
  }

  // ── an older service ──────────────────────────────────────────────────────────

  Given(
    "a service {string} with two instances, of which only one has the handler {string} of the timed action {string}"
  )((service: String, _: String, _: String) => describe(service))

  Given("the instance without the handler {string} is the one that fires the timers of {string}") {
    (handler: String, _: String) => sweeperWithout(handler)
  }

  Given(
    "a handler on the other instance has set the recurring timer {string} for {string} with a period of {string}"
  )((timer: String, handler: String, period: String) => setRecurringHere(timer, handler, period))

  Given("a service {string} whose recurring timer {string} for {string} has a period of {string}") {
    (service: String, timer: String, handler: String, period: String) =>
      describe(service)
      setRecurringHere(timer, handler, period)
  }

  Given(
    "several due times of {string} have passed while an instance without the handler {string} fired the timers of {string}"
  ) { (timer: String, handler: String, _: String) =>
    sweeperWithout(handler)
    Thread.sleep(5000)
    assertEquals(runsOf(timer).size, 0, "a sweeper without the handler ran it")
    assert(
      probe.fired(timer).exists(_.outcome == FiredTimer.Outcome.Deferred),
      "the sweeper without the handler did not defer it"
    )
  }

  When("an instance with the handler {string} becomes the one that fires the timers of {string}") {
    (_: String, _: String) =>
      stopSweeping()
      startTimerRuntime()
  }

  /** The first run of a timer, by its handler's record alone: an older runtime tells no probe. */
  private def awaitRunsByHandler(timer: String): Run =
    waitFor(s"'$timer' runs", 20.seconds)(runsOf(timer).headOption)

  // ── beside the scenarios ──────────────────────────────────────────────────────

  test(
    "a timer that fires once and failed under the previous release is told the due its backoff ended at, then that again"
  ) {
    describe("orders")
    action("reminders").script("nudge", Behaviour.FailsThenDone(2))
    // Set and failed once under the previous release: no due_for, one attempt, due_at moved on.
    legacySet("nudge-c1", "nudge", Duration.Zero)
    val _ = Await.result(
      database.execute(LegacyTimers.Store.reschedule("nudge-c1", 0)),
      10.seconds
    )
    val backedOffTo = scheduled("nudge-c1").dueTime
    assertEquals(scheduled("nudge-c1").attempts, 1)
    // Now this release sweeps: the first retry is told the due_at it read, and its backoff keeps
    // that, so the retry after is told the same.
    startTimerRuntime()
    val runs = waitFor("two retries", 30.seconds)(Option(runsOf("nudge-c1")).filter(_.size >= 2))
    assertEquals(runs.take(2).map(_.dueTime), Vector(backedOffTo, backedOffTo))
    assertEquals(runs.take(2).map(_.previousAttempts), Vector(1, 2))
  }
