package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*

/** Feature 042, T059: the rerun window, thirty days unless `ANKKA_ERASURE_RERUN_WINDOW` says. */
class ErasureSettingsSuite extends munit.FunSuite:

  private def from(text: String) = ErasureSettings.from(ConfigFactory.parseString(text))

  test("the window is thirty days when nothing says otherwise") {
    assertEquals(from("").rerunWindow, 30.days)
  }

  test("the window is read as a duration") {
    assertEquals(from(s"""${ErasureSettings.RerunWindowKey} = 7d""").rerunWindow, 7.days)
    assertEquals(from(s"""${ErasureSettings.RerunWindowKey} = 36h""").rerunWindow, 36.hours)
  }

  test("a window that is nothing, negative or not a duration is refused, naming the variable") {
    for bad <- Seq("0d", "-1d", "soon") do
      val e = intercept[IllegalArgumentException](
        from(s"""${ErasureSettings.RerunWindowKey} = "$bad"""")
      )
      assert(e.getMessage.contains("ANKKA_ERASURE_RERUN_WINDOW"), e.getMessage)
      assert(e.getMessage.contains(bad), e.getMessage)
  }
