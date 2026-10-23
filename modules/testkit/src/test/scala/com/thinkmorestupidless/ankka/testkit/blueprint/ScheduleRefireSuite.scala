package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, MovableClock}

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime, ZoneId}
import scala.concurrent.duration.*

/**
 * A firing of a schedule that started its run and stopped before moving the schedule on, as a
 * service stopped between the two does: the firing again after the outage starts the period where
 * that run's ended, so no two periods overlap (`schedules.feature`, "periods meet with no gap and
 * no overlap"). `ScheduleFeatures` met this by chance when its outage began inside the window.
 */
class ScheduleRefireSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val zone     = ZoneId.of("Europe/London")
  private val clock    = MovableClock.at(Instant.parse("2026-10-01T00:00:00Z"))
  private val echo     = BlueprintHandler("echo")((_, input) => input)
  private val schedule = Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.parse("20:00"), zone)
  private val digest = Blueprint("refire-digest")
    .step(Step("keep").call("echo").reads("input").result(Shape.any))
    .schedule(schedule)

  private val agents = AgentRuntime().withBlueprints(_ => BlueprintRegistry.empty.handlers(echo))
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      AgentRuntime.descriptors,
      Seq(agents, TimerRuntime(pollInterval = 200.millis, clock = clock), ProjectionRuntime())
    )
  override def afterAll(): Unit = if kit != null then kit.stop()

  private def sunday(date: String): Instant =
    LocalDate.parse(date).atTime(20, 0).atZone(zone).toInstant

  test("a firing repeated after its run was started covers only the periods after that run") {
    assertEquals(agents.blueprints.register(digest).version, 1)
    val first = sunday("2026-10-04")
    assertEquals(
      kit.componentClient
        .forEventSourcedEntity(EntityId(digest.name))
        .call(BlueprintEntity.get)
        .invoke()
        .nextDue,
      Some(first.toEpochMilli)
    )

    // The firing at 4 October started its run, and the service stopped before the schedule moved on.
    val interrupted = schedule.periods(schedule.previous(first), Vector(first)).head
    RunCalls.startHeld(
      kit.componentClient,
      digest,
      1,
      interrupted.json,
      ScheduleTimer.runId(digest.name, first),
      ScheduleTimer.StartedBy
    ): Unit

    // Back on 20 October: the 4 October timer fires again, with 11 and 18 October missed since.
    clock.moveTo(LocalDate.parse("2026-10-20").atStartOfDay(zone).toInstant)
    val caughtUp = ScheduleTimer.runId(digest.name, sunday("2026-10-18"))
    val run = kit.eventually("the run covering the outage is started", 30.seconds)(
      try Some(agents.runs.get(caughtUp))
      catch case e: CommandError if e.code == ErrorCode.NotFound => None
    )
    val period = Period.fromJson(run.input).getOrElse(fail(s"not a period: ${run.input.render}"))
    assertEquals(period.from, first, "the period covers the interrupted firing's again")
    assertEquals(period.to, sunday("2026-10-18"))
  }
