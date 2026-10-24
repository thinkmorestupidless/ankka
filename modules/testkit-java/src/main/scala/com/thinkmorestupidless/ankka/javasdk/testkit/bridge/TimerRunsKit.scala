package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.javasdk.testkit.TimerRuns
import com.thinkmorestupidless.ankka.runtime.FiredTimer
import com.thinkmorestupidless.ankka.testkit as stk

import java.time.{Duration, Instant}
import java.util.{List as JList, Optional}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Scala timer probe, read as the Java test kit's records. */
final class TimerRunsKit private[bridge] (probe: stk.TimerProbe):

  def fired(name: String): JList[TimerRuns.Run] =
    probe
      .fired(name)
      .map(run =>
        TimerRuns.Run(
          run.name,
          run.dueTime,
          run.attempts,
          run.outcome match
            case FiredTimer.Outcome.Done     => TimerRuns.Outcome.DONE
            case FiredTimer.Outcome.Failed   => TimerRuns.Outcome.FAILED
            case FiredTimer.Outcome.Dropped  => TimerRuns.Outcome.DROPPED
            case FiredTimer.Outcome.Deferred => TimerRuns.Outcome.DEFERRED
          ,
          run.next.toJava,
          run.period.map(Duration.ofMillis).toJava
        )
      )
      .asJava

  def dueTimes(name: String): JList[Instant] = probe.dueTimes(name).asJava

  def scheduled(name: String): Optional[TimerRuns.Scheduled] =
    probe
      .scheduled(name)
      .map(t =>
        TimerRuns.Scheduled(
          t.name,
          s"${t.target.componentId}/${t.target.method}",
          t.dueTime,
          t.period.map(p => Duration.ofMillis(p.toMillis)).toJava,
          t.attempts
        )
      )
      .toJava
