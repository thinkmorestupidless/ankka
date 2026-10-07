package com.thinkmorestupidless.ankka.sdk

import scala.concurrent.duration.*

class TimerRulesSuite extends munit.FunSuite:

  private def accepted(period: FiniteDuration): Long =
    TimerRules.period("sweep-carts", period).fold(message => fail(message), identity)

  private def refused(period: FiniteDuration): String =
    TimerRules.period("sweep-carts", period).fold(identity, ms => fail(s"accepted as $ms ms"))

  test("a period of a millisecond, an hour and the longest there may be is accepted") {
    assertEquals(accepted(1.milli), 1L)
    assertEquals(accepted(1.hour), 3_600_000L)
    assertEquals(accepted(36_500.days), 36_500L * 86_400_000L)
  }

  test("a period of zero, of less, of under a millisecond or of more than the longest is refused") {
    for period <- Seq(Duration.Zero, (-1).second, 999.micros, 36_500.days + 1.milli) do
      val message = refused(period)
      assert(message.contains("sweep-carts"), s"'$message' does not name the timer")
  }

  test("the refusal says what a period may be") {
    val message = refused(Duration.Zero)
    assert(message.contains("1 millisecond"), message)
    assert(message.contains("36500 days"), message)
  }
