package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.bridge.JavaServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.client.CallerError
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken.BrokenTimers
import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import com.thinkmorestupidless.ankka.testkit as stk
import com.thinkmorestupidless.ankka.testkit.{LogCapturing, TimerProbe}

import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Java timed actions, run by the Scala timer runtime under the Scala `AnkkaTestKit`, and scheduled
 * through the Java timers over its scheduler.
 */
class TimersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val read = JavaServiceBuilder.read(Vector(classOf[CartTimers] -> None), Map.empty)

  private val probe                 = TimerProbe()
  private val runtime               = TimerRuntime(pollInterval = 100.millis, observer = probe)
  private var kit: stk.AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = stk.AnkkaTestKit.start(read.descriptors, extensions = Seq(runtime))

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def timers = read.timers(runtime.timerScheduler)

  private def runs(timer: String) = CartTimers.runs.asScala.filter(_.timer == timer).toVector

  private def eventually(what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + 20.seconds.toNanos
    while !check do
      if System.nanoTime() > deadline then fail(s"never: $what")
      Thread.sleep(50)

  test("a timer runs its action once, with the input it was scheduled with, and is gone") {
    timers.schedule("t-once", Duration.ofMillis(100), KindRefs.expireTimer, "cart-9")
    assert(timers.exists("t-once"))
    eventually("the run")(runs("t-once").nonEmpty)
    assertEquals(runs("t-once"), Vector(CartTimers.Run("t-once", "cart-9", 0)))
    eventually("the timer is gone")(!timers.exists("t-once"))
    assertEquals(probe.fired("t-once").size, 1)
  }

  test("a recurring timer runs every period until it is cancelled") {
    timers.scheduleRecurring("t-tick", Duration.ZERO, Duration.ofMillis(200), KindRefs.tick)
    eventually("three ticks")(runs("t-tick").size >= 3)
    timers.cancel("t-tick")
    assert(!timers.exists("t-tick"))
    val after = runs("t-tick").size
    Thread.sleep(800)
    assert(
      runs("t-tick").size <= after + 1,
      s"${runs("t-tick").size} runs after cancelling at $after"
    )
  }

  test("an action that errors is retried, told how many attempts failed before") {
    timers.schedule("t-flaky", Duration.ZERO, KindRefs.flaky)
    eventually("the retry")(runs("t-flaky").size >= 2)
    assertEquals(runs("t-flaky").take(2).map(_.previousAttempts), Vector(0, 1))
  }

  test("an action schedules what comes next through its own context") {
    timers.schedule("t-chain", Duration.ZERO, KindRefs.chain, "after")
    eventually("the next timer's run")(runs("t-chain-next").nonEmpty)
    assertEquals(runs("t-chain-next").head.input, "after")
  }

  test("a timer named by a lambda, or by a method that is no action, is refused") {
    val lambda = KindRefs.tickByLambda
    val e      = intercept[CallerError](timers.schedule("t-lambda", Duration.ZERO, lambda))
    assert(e.getMessage.contains("method reference"), e.getMessage)
    assert(!timers.exists("t-lambda"))
  }

  test("a timed action declared wrongly stops the start, saying what is wrong with each") {
    val e = intercept[ServiceProblems](
      JavaServiceBuilder.read(
        Vector(classOf[BrokenTimers.Misshapen], classOf[BrokenTimers.Empty]).map(_ -> None),
        Map.empty
      )
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("has the action name 'same', as one does"), messages)
    assert(messages.contains("takes 2 parameters; an action takes one or none"), messages)
    assert(messages.contains("returns a TimedActionEffect but has no @Action"), messages)
    assert(messages.contains("is a timed action with no @Action method"), messages)
  }

  test("a service with no timed action has no timers to schedule") {
    val none = JavaServiceBuilder.read(Vector(classOf[TreeNode] -> None), Map.empty)
    val e    = intercept[IllegalStateException](none.timers(runtime.timerScheduler).exists("x"))
    assert(e.getMessage.contains("registers no timed action"), e.getMessage)
    assertEquals(none.platformExtensions, Vector.empty)
    assertEquals(read.platformExtensions.map(_.name), Vector("timers"))
  }
