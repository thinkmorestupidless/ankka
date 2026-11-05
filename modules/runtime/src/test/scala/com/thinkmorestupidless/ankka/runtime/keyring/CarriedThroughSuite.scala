package com.thinkmorestupidless.ankka.runtime.keyring

/** Feature 042, review: the mark a hello says, over a contiguous prefix of the notices handed. */
final class CarriedThroughSuite extends munit.FunSuite:

  test("the mark follows notices carried out in order, numbered with gaps") {
    val c = CarriedThrough()
    c.handed(5)
    c.handed(9)
    assertEquals(c.carried(5), 5L)
    assertEquals(c.carried(9), 9L)
  }

  test("a notice not carried out holds the mark back, however many later ones are") {
    val c = CarriedThrough()
    Seq(5L, 9L, 12L).foreach(c.handed)
    assertEquals(c.carried(5), 5L)
    assertEquals(c.carried(12), 5L, "9 is still waiting")
    assertEquals(c.value, 5L)
    assertEquals(c.carried(9), 12L, "once 9 is carried out, 12 counts too")
  }

  test("a notice at or below the mark changes nothing, and the start is a floor") {
    val c = CarriedThrough(10)
    c.handed(7)
    assertEquals(c.carried(7), 10L)
    c.handed(11)
    c.atLeast(11)
    assertEquals(c.value, 11L)
    c.handed(14)
    assertEquals(c.carried(14), 14L)
  }
