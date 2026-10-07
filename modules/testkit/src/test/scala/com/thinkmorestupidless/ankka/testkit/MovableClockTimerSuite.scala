package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.runtime.TimerRuntime

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A timer runtime on a clock a test moves: a timer an hour away fires when the test says so. */
class MovableClockTimerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var testKit: AnkkaTestKit = null
  // docs:start clock
  private val clock  = MovableClock.at(Instant.parse("2026-10-11T18:00:00Z"))
  private val timers = TimerRuntime(pollInterval = 200.millis, clock = clock)
  // docs:end clock

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq(OrderEntity.descriptor, OrderTimers.descriptor), Seq(timers))

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def order(id: String) = testKit.componentClient.forKeyValueEntity(EntityId(id))

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("a timer an hour ahead fires when the clock is moved past it, and not before") {
    assertEquals(order("mc-1").call(OrderEntity.place).invoke("book"), Done)
    timers.timerScheduler.createSingleTimer(
      "expire-mc-1",
      1.hour,
      OrderTimers.expireOrder.deferred("mc-1")
    )

    // Five polls' worth of real time, and the clock has not moved: nothing fires.
    Thread.sleep(1000)
    assertNotEquals(order("mc-1").call(OrderEntity.status).invoke(), "cancelled")
    assert(timers.timerScheduler.exists("expire-mc-1"))

    clock.moveBy(2.hours)
    val _ = eventually("the order is cancelled")(
      Option(order("mc-1").call(OrderEntity.status).invoke()).filter(_ == "cancelled")
    )
    val _ = eventually("the timer is deleted")(
      Option.when(!timers.timerScheduler.exists("expire-mc-1"))(())
    )
  }
