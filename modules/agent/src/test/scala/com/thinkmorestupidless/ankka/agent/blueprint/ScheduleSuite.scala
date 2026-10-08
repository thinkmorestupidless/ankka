package com.thinkmorestupidless.ankka.agent.blueprint

import java.time.{DayOfWeek, Duration, Instant, LocalTime, ZoneId, ZonedDateTime}

/** Due times and periods, pure: the arithmetic the schedule timer runs on (R12, R13). */
class ScheduleSuite extends munit.FunSuite:

  private val london        = ZoneId.of("Europe/London")
  private val weekly        = Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), london)
  private def at(s: String) = Instant.parse(s)

  test("weekly due times are the day at the local time, and the clocks going back keeps it") {
    assertEquals(weekly.next(at("2026-10-01T00:00:00Z")), at("2026-10-04T19:00:00Z"))
    // Strictly after: a due time is not its own next.
    assertEquals(weekly.next(at("2026-10-04T19:00:00Z")), at("2026-10-11T19:00:00Z"))
    // 25 October 2026 is when Europe/London goes back: 20:00 BST was 19:00Z, 20:00 GMT is 20:00Z.
    val across = weekly.next(at("2026-10-18T19:00:00Z"))
    assertEquals(across, at("2026-10-25T20:00:00Z"))
    assertEquals(ZonedDateTime.ofInstant(across, london).toLocalTime, LocalTime.of(20, 0))
    assertEquals(Duration.between(at("2026-10-18T19:00:00Z"), across).toHours, 169L)
    assertEquals(weekly.previous(across), at("2026-10-18T19:00:00Z"))
  }

  test("every so many hours is within each day from midnight, in the zone") {
    val sixHourly = Schedule.everyHours(6, london)
    // 00:00 BST on 1 October is 23:00Z the night before.
    assertEquals(sixHourly.next(at("2026-10-01T00:00:00Z")), at("2026-10-01T05:00:00Z"))
    assertEquals(sixHourly.next(at("2026-10-01T05:00:00Z")), at("2026-10-01T11:00:00Z"))
    assertEquals(sixHourly.previous(at("2026-10-01T05:00:00Z")), at("2026-09-30T23:00:00Z"))
    assertEquals(
      sixHourly.dueTimes(at("2026-09-30T23:00:00Z"), at("2026-10-01T23:00:00Z")).size,
      4
    )
  }

  test("every so many days is midnight in the zone, and the day the clocks change is 25 hours") {
    val daily = Schedule.everyDays(1, london)
    assertEquals(daily.next(at("2026-10-01T05:00:00Z")), at("2026-10-01T23:00:00Z"))
    val before = daily.next(at("2026-10-24T12:00:00Z")) // 25 October, 00:00 BST
    val after  = daily.next(before)                     // 26 October, 00:00 GMT
    assertEquals(before, at("2026-10-24T23:00:00Z"))
    assertEquals(after, at("2026-10-26T00:00:00Z"))
    assertEquals(Duration.between(before, after).toHours, 25L)
    val everyOther = Schedule.everyDays(2, ZoneId.of("UTC"))
    val dues       = everyOther.dueTimes(at("2026-10-01T00:00:00Z"), at("2026-10-07T00:00:00Z"))
    assertEquals(
      dues.map(_.toString),
      Vector("2026-10-02T00:00:00Z", "2026-10-04T00:00:00Z", "2026-10-06T00:00:00Z")
    )
    assert(dues.forall(d => d.getEpochSecond / 86400 % 2 == 0))
  }

  test("missed lists every due time passed since the last period ended") {
    assertEquals(
      weekly.dueTimes(after = at("2026-10-04T19:00:00Z"), upTo = at("2026-10-20T00:00:00Z")),
      Vector(at("2026-10-11T19:00:00Z"), at("2026-10-18T19:00:00Z"))
    )
    assertEquals(
      weekly.dueTimes(after = at("2026-10-04T19:00:00Z"), upTo = at("2026-10-05T00:00:00Z")),
      Vector.empty
    )
    // Up to and including: a due time reached exactly is passed.
    assertEquals(
      weekly.dueTimes(after = at("2026-10-04T19:00:00Z"), upTo = at("2026-10-11T19:00:00Z")).size,
      1
    )
  }

  test("one run covers every missed period; each gives one per due time, oldest first, meeting") {
    val lastEnd = at("2026-10-04T19:00:00Z")
    val missed  = weekly.dueTimes(lastEnd, at("2026-10-20T00:00:00Z"))
    assertEquals(
      weekly.periods(lastEnd, missed),
      Vector(Period(lastEnd, at("2026-10-18T19:00:00Z"), missed))
    )
    val each = weekly.perMissedPeriod.periods(lastEnd, missed)
    assertEquals(
      each,
      Vector(
        Period(lastEnd, at("2026-10-11T19:00:00Z"), Vector(at("2026-10-11T19:00:00Z"))),
        Period(
          at("2026-10-11T19:00:00Z"),
          at("2026-10-18T19:00:00Z"),
          Vector(at("2026-10-18T19:00:00Z"))
        )
      )
    )
    each.zip(each.tail).foreach((a, b) => assertEquals(a.to, b.from))
    assertEquals(weekly.periods(lastEnd, Vector.empty), Vector.empty)
  }

  test("a schedule's first period starts one cadence before its first due time") {
    val now   = at("2026-10-01T00:00:00Z")
    val first = weekly.next(now)
    assertEquals(weekly.previous(first), at("2026-09-27T19:00:00Z"))
    assertEquals(Duration.between(weekly.previous(first), first).toDays, 7L)
  }

  test("a period reads back from its JSON, and the check's example has the period shape") {
    val period = Period(
      at("2026-10-04T19:00:00Z"),
      at("2026-10-11T19:00:00Z"),
      Vector(at("2026-10-11T19:00:00Z"))
    )
    assertEquals(Period.fromJson(period.json), Some(period))
    assertEquals(Period.shape.check(Period.example.json), Vector.empty)
    assertEquals(Shape.obj().check(Period.example.json), Vector.empty)
  }
