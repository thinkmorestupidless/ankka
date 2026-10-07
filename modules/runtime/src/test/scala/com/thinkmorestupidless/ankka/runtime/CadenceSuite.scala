package com.thinkmorestupidless.ankka.runtime

import java.time.Instant

class CadenceSuite extends munit.FunSuite:

  private val due    = Instant.parse("2026-10-04T12:00:00Z")
  private val period = 2_000L

  private def at(millis: Long): Instant = due.plusMillis(millis)

  test("a handler that finished within the period: the next due is one period on") {
    assertEquals(Cadence.next(due, period, at(500)), at(2_000))
    assertEquals(Cadence.skipped(due, period, at(500)), 0L)
  }

  test("now exactly on a cadence point: the next due is the point after it") {
    assertEquals(Cadence.next(due, period, at(2_000)), at(4_000))
    assertEquals(Cadence.skipped(due, period, at(2_000)), 1L)
  }

  test("a millisecond before a cadence point: that point") {
    assertEquals(Cadence.next(due, period, at(3_999)), at(4_000))
    assertEquals(Cadence.skipped(due, period, at(3_999)), 1L)
  }

  test("a millisecond after a cadence point: the point after it") {
    assertEquals(Cadence.next(due, period, at(4_001)), at(6_000))
    assertEquals(Cadence.skipped(due, period, at(4_001)), 2L)
  }

  test("several periods past: the first point still to come, and how many were passed over") {
    assertEquals(Cadence.next(due, period, at(10_500)), at(12_000))
    assertEquals(Cadence.skipped(due, period, at(10_500)), 5L)
  }

  test("now before the due itself, as a clock that moved back may make it: one period on") {
    assertEquals(Cadence.next(due, period, at(-5_000)), at(2_000))
    assertEquals(Cadence.skipped(due, period, at(-5_000)), 0L)
  }

  test("a period of one millisecond") {
    assertEquals(Cadence.next(due, 1L, at(0)), at(1))
    assertEquals(Cadence.next(due, 1L, at(7)), at(8))
  }

  test("the longest period there may be, from a due time in this century, does not overflow") {
    val century = 36_500L * 86_400_000L
    assertEquals(Cadence.next(due, century, at(1)), due.plusMillis(century))
    assertEquals(Cadence.next(due, century, due.plusMillis(century)), due.plusMillis(2 * century))
  }
