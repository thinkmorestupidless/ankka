package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.ControlPlaneLine

import scala.jdk.CollectionConverters.*

class ControlPlaneLineSuite extends munit.FunSuite:

  private val window = Map(
    "serverRecoveryWindow" -> Map(
      "ankka-controlplane-db" -> Map(
        "firstRecoverabilityPoint" -> "2026-10-09T16:56:59Z",
        "lastSuccessfulBackupTime" -> "2026-10-09T16:56:59Z"
      )
    )
  )
  private def cluster(archiving: String) =
    Map(
      "conditions" -> List(
        Map("type" -> "Ready", "status"               -> "True"),
        Map("type" -> "ContinuousArchiving", "status" -> archiving, "message" -> "it broke")
      )
    )

  /** The same document as fabric8's own mapper gives it: Java maps and lists all the way down. */
  private def java(value: Any): Any =
    value match
      case m: Map[?, ?] => m.map((k, v) => k -> java(v)).asJava
      case l: List[?]   => l.map(java).asJava
      case other        => other

  test("the line is read from Scala collections, as the platform's mapper gives them") {
    val line = ControlPlaneLine.from(window, cluster("True")).getOrElse(fail("no line"))
    assertEquals(line.phase, "BackingUp")
    assertEquals(line.lastBaseBackup, Some("2026-10-09T16:56:59Z"))
    assertEquals(line.firstRestorable, Some("2026-10-09T16:56:59Z"))
  }

  test("and from Java collections, as fabric8's own mapper gives them") {
    val line = ControlPlaneLine.from(java(window), java(cluster("True"))).getOrElse(fail("no line"))
    assertEquals(line.phase, "BackingUp")
  }

  test("an archive that stopped is failing, with CNPG's words; nothing read is no line") {
    val failing = ControlPlaneLine.from(window, cluster("False")).getOrElse(fail("no line"))
    assertEquals(failing.phase, "Failing")
    assertEquals(failing.failing, Some("it broke"))
    assertEquals(
      ControlPlaneLine.from(null, cluster("True")).map(_.phase),
      Some("NotBackedUp")
    )
    assertEquals(ControlPlaneLine.from(null, null), None)
  }
