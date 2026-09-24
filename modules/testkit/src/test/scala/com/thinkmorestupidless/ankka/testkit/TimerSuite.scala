package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.runtime.TimerRuntime

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/** Scheduled calls: durable, at-least-once, cancellable. */
class TimerSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val timers                = TimerRuntime(pollInterval = 200.millis)

  override def beforeAll(): Unit =
    OrderTimers.observed.clear()
    // docs:start register
    testKit = AnkkaTestKit.start(
      Seq(OrderEntity.descriptor, OrderTimers.descriptor),
      Seq(timers)
    )
    // docs:end register

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def order(id: String) = testKit.componentClient.forKeyValueEntity(EntityId(id))
  private def scheduler         = timers.timerScheduler

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def observed: Seq[String] = OrderTimers.observed.asScala.toSeq

  test("a scheduled call fires and then is forgotten") {
    assertEquals(order("o-1").call(OrderEntity.place).invoke("book"), Done)
    // docs:start schedule
    scheduler.createSingleTimer("expire-o-1", 300.millis, OrderTimers.expireOrder.deferred("o-1"))
    assert(scheduler.exists("expire-o-1"))
    // docs:end schedule

    val _ = eventually("the order is cancelled")(
      Option(order("o-1").call(OrderEntity.status).invoke()).filter(_ == "cancelled")
    )
    // A completed timer is removed, so it cannot fire twice.
    val _ = eventually("the timer is deleted")(Option.when(!scheduler.exists("expire-o-1"))(()))
  }

  test("deleting a timer stops it from firing") {
    assertEquals(order("o-2").call(OrderEntity.place).invoke("pen"), Done)
    scheduler.createSingleTimer("expire-o-2", 5.seconds, OrderTimers.expireOrder.deferred("o-2"))

    assertEquals(order("o-2").call(OrderEntity.confirm).invoke(), Done)
    scheduler.delete("expire-o-2")
    assert(!scheduler.exists("expire-o-2"))

    // Well past when it would have fired.
    Thread.sleep(1500)
    assertEquals(order("o-2").call(OrderEntity.status).invoke(), "confirmed")
    assert(!observed.exists(_.startsWith("o-2:")), "a deleted timer must not run")
  }

  test("rescheduling under the same name replaces the earlier schedule") {
    assertEquals(order("o-3").call(OrderEntity.place).invoke("lamp"), Done)
    scheduler.createSingleTimer("expire-o-3", 300.millis, OrderTimers.expireOrder.deferred("o-3"))
    // Extend the deadline before the first schedule comes due.
    scheduler.createSingleTimer("expire-o-3", 30.seconds, OrderTimers.expireOrder.deferred("o-3"))

    Thread.sleep(1500)
    assertEquals(
      order("o-3").call(OrderEntity.status).invoke(),
      "pending",
      "the replacement schedule should not have come due yet"
    )
    assert(scheduler.exists("expire-o-3"))
    scheduler.delete("expire-o-3")
  }

  test("a timer for work already done succeeds rather than retrying") {
    assertEquals(order("o-4").call(OrderEntity.place).invoke("desk"), Done)
    assertEquals(order("o-4").call(OrderEntity.confirm).invoke(), Done)

    scheduler.createSingleTimer("expire-o-4", 200.millis, OrderTimers.expireOrder.deferred("o-4"))

    val _ = eventually("the timer runs and is cleared")(
      Option.when(observed.exists(_.startsWith("o-4:")) && !scheduler.exists("expire-o-4"))(())
    )
    // The handler reported the order was already confirmed — and reported success.
    assert(observed.contains("o-4:already confirmed"), observed.toString)
    assertEquals(order("o-4").call(OrderEntity.status).invoke(), "confirmed")
  }

  test("a failing timer is retried with backoff, not dropped") {
    scheduler.createSingleTimer("retry-me", 200.millis, OrderTimers.alwaysFails.deferred("o-5"))

    val _ = eventually("it is attempted at least twice", 40.seconds) {
      Option.when(observed.count(_.startsWith("o-5:")) >= 2)(())
    }
    // Still scheduled, because it never reported success.
    assert(scheduler.exists("retry-me"))

    // The attempt counter is carried into the handler's context.
    assert(observed.contains("o-5:attempt-0"), observed.toString)
    assert(observed.contains("o-5:attempt-1"), observed.toString)
    scheduler.delete("retry-me")
  }

  test("an oversized payload is refused at scheduling time") {
    val failure = intercept[IllegalArgumentException] {
      scheduler.createSingleTimer(
        "too-big",
        1.second,
        OrderTimers.expireOrder.deferred("x" * 2000)
      )
    }
    assert(failure.getMessage.contains("1024"), failure.getMessage)
    assert(!scheduler.exists("too-big"))
  }

  test("scheduled calls survive a restart") {
    assertEquals(order("o-6").call(OrderEntity.place).invoke("chair"), Done)
    scheduler.createSingleTimer("expire-o-6", 10.seconds, OrderTimers.expireOrder.deferred("o-6"))

    testKit.restartService()

    // The timer is in Postgres, not in the memory of the node that scheduled it.
    assert(timers.timerScheduler.exists("expire-o-6"))
    timers.timerScheduler.delete("expire-o-6")
  }
