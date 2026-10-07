package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.sidecar.conformance.ConformanceReference.*
import com.thinkmorestupidless.ankka.testkit.{KeyedViewTestKit, LogCapturing}

/** The reference's keyed view, its handlers tested with no runtime: the views guide's example. */
class JoinedRowsSuite extends munit.FunSuite with LogCapturing:

  // docs:start keyed-view-test
  test("the right's change notes every row holding it, found by the view's own query") {
    val kit = KeyedViewTestKit(JoinedRows)
    kit.change(JoinedRows.lefts, "a", Noted("r1|b"))
    kit.change(JoinedRows.lefts, "a", Noted("r2|b"))
    kit.change(JoinedRows.lefts, "a", Noted("r3|c"))
    // The query is the database's to run; the test says what it answers.
    kit.answering(JoinedRows.ofRight)(values =>
      kit.rows.values.filter(_.holding == values("holding")).toVector
    )
    kit.change(JoinedRows.rights, "b", Noted("anything"))
    assertEquals(kit.row("r1").map(_.notes), Some(Vector("left", "right")))
    assertEquals(kit.row("r2").map(_.notes), Some(Vector("left", "right")))
    assertEquals(kit.row("r3").map(_.notes), Some(Vector("left")))
  }
  // docs:end keyed-view-test
