package com.thinkmorestupidless.ankka.testkit.timers

import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, TimerProbe}

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/**
 * `features/timers/recurring-timers.feature`, run as it is written against a real service, and the
 * spec's measure of drift over twenty runs.
 */
final class RecurringTimerFeatures
    extends TimerSteps("../../features/timers/recurring-timers.feature"):

  test("twenty runs at a two-second period with a half-second handler stay on cadence (40 s)") {
    val probe  = TimerProbe()
    val timers = TimerRuntime(pollInterval, probe)
    val action = ScriptedTimerKit("cleanup", () => timers.timerScheduler)
    action.script("sweep", Behaviour.Takes(500.millis))
    val kit = AnkkaTestKit.start(Seq(action.descriptor), Seq(timers))
    try
      probe.bind(kit)
      timers.timerScheduler.createRecurringTimer(
        "sweep-carts",
        Duration.Zero,
        2.seconds,
        action.handle("sweep").deferred("sweep-carts")
      )
      val deadline = 70.seconds.fromNow
      while probe.dueTimes("sweep-carts").size < 20 && !deadline.isOverdue() do Thread.sleep(100)
      val dues = probe.dueTimes("sweep-carts").take(20)
      assertEquals(dues.size, 20, "twenty runs did not happen")
      // The arithmetic: the twentieth due is exactly nineteen periods after the first.
      assertEquals(JDuration.between(dues.head, dues.last).toMillis, 19 * 2_000L)
      // The sweeper: the twentieth run started no earlier than its due, and within a poll of it.
      val twentieth = action.runs("sweep").find(_.dueTime == dues.last).get
      val late      = JDuration.between(dues.last, twentieth.startedAt).toMillis
      assert(
        late >= 0 && late <= pollInterval.toMillis + 250,
        s"the twentieth run was $late ms late"
      )
    finally kit.stop()
  }
