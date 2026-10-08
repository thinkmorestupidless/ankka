package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{Database, TimerStore}

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * V2 for blueprints (research R14): a settable clock for timers is only possible if the store's
 * query for due timers reads the `now` it is given and nothing else. A timer due at `t` must be
 * returned by `due(t)` and not by `due(t - 1s)`, whatever the wall clock says.
 */
class TimerStoreClockSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Seq(OrderEntity.descriptor, OrderTimers.descriptor), Seq.empty)

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def database = Database()(using kit.service.system)

  private def dueNames(now: Instant): Vector[String] =
    Await.result(database.query(TimerStore.due(now, 10))(_.get(0, classOf[String])), 10.seconds)

  test("V2 the due query reads the time it is given, not the wall clock") {
    // A year ahead, so the wall clock could never make it due on its own.
    val t = Instant.now().plusSeconds(365L * 24 * 3600)
    Await.result(
      database.execute(
        TimerStore.upsert("probe-due", OrderTimers.expireOrder.deferred("o-probe"), t)
      ),
      10.seconds
    )
    assertEquals(dueNames(t.minusSeconds(1)).filter(_ == "probe-due"), Vector.empty)
    assertEquals(dueNames(t).filter(_ == "probe-due"), Vector("probe-due"))
    assertEquals(dueNames(t.plusSeconds(1)).filter(_ == "probe-due"), Vector("probe-due"))
    Await.result(database.execute(TimerStore.delete("probe-due")), 10.seconds)
  }
