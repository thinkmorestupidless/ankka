package com.thinkmorestupidless.ankka.runtime

import java.nio.file.Files

class StartRefusalSuite extends munit.FunSuite:

  test("the reason is written where the operator reads it") {
    val path = Files.createTempFile("termination", ".log")
    val was  = sys.props.get(StartRefusal.PathProperty)
    sys.props(StartRefusal.PathProperty) = path.toString
    try
      val thrown = intercept[IllegalArgumentException] {
        StartRefusal.refuse(
          "cannot start ankka projections:\n  - consumer 'relay' publishes to 'orders' as 'order.v2'",
          IllegalArgumentException(_)
        )
      }
      assert(thrown.getMessage.contains("order.v2"))
      assertEquals(Files.readString(path).trim, thrown.getMessage)
    finally
      was.fold(sys.props.remove(StartRefusal.PathProperty): Unit)(
        sys.props(StartRefusal.PathProperty) = _
      )
  }
