package com.thinkmorestupidless.ankka.operator

import scala.concurrent.duration.*

/**
 * The installation's backup settings as the operator reads them (feature 041,
 * contracts/operator.md): five, each with a default, and a target that cannot be named without the
 * store it names.
 */
class BackupSettingsSuite extends munit.FunSuite:

  private def withProperties[A](properties: (String, String)*)(body: => A): A =
    val previous = properties.map((key, _) => key -> sys.props.get(key))
    properties.foreach((key, value) => sys.props(key) = value)
    try body
    finally
      previous.foreach {
        case (key, Some(value)) => sys.props(key) = value
        case (key, None)        => sys.props -= key: Unit
      }

  private val store = Vector(
    "ankka.operator.object-store.admin-url" -> "http://garage.garage-system.svc.cluster.local:3903",
    "ankka.operator.object-store.admin-token" -> "a-token",
    "ankka.operator.object-store.endpoint" -> "http://garage.garage-system.svc.cluster.local:3900",
    "ankka.operator.object-store.region"   -> "garage",
    "ankka.operator.object-store.service"  -> "garage-system/garage:3900"
  )

  test("unset, nothing is backed up and every other setting is its default") {
    val backups = Settings.fromEnvironment().backups
    assertEquals(backups, BackupSettings.none)
    assertEquals(backups.target, None)
    assertEquals(backups.retentionDays, 30)
    assertEquals(backups.schedule, "0 0 0 * * *")
    assertEquals(backups.copyRequired, false)
    assertEquals(backups.rehearsalTtl, 24.hours)
    assertEquals(Settings.default.backups, BackupSettings.none)
  }

  test("the installation's object store as the target, with its store") {
    withProperties((store :+ ("ankka.operator.backup.target" -> "object-store"))*) {
      assertEquals(Settings.fromEnvironment().backups.target, Some(BackupTarget.ObjectStore))
    }
  }

  test("none is no target") {
    withProperties("ankka.operator.backup.target" -> "none") {
      assertEquals(Settings.fromEnvironment().backups.target, None)
    }
  }

  test("the object store as the target with no object store refuses to start, naming both") {
    withProperties("ankka.operator.backup.target" -> "object-store") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_BACKUP_TARGET"), e.getMessage)
      assert(e.getMessage.contains("ANKKA_OBJECT_STORE_ADMIN_URL"), e.getMessage)
    }
  }

  test("Google Cloud Storage as the target refuses to start until the cloud provider exists") {
    withProperties("ankka.operator.backup.target" -> "gcs") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("044"), e.getMessage)
    }
  }

  test("an unknown target refuses to start, naming what it may be") {
    withProperties("ankka.operator.backup.target" -> "tape") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("'tape'"), e.getMessage)
      assert(e.getMessage.contains("object-store"), e.getMessage)
    }
  }

  test("each setting is read from its property") {
    withProperties(
      (store ++ Vector(
        "ankka.operator.backup.target"         -> "object-store",
        "ankka.operator.backup.retention-days" -> "45",
        "ankka.operator.backup.schedule"       -> "0 30 2 * * *",
        "ankka.operator.backup.copy-required"  -> "on",
        "ankka.operator.rehearsal.ttl-hours"   -> "6"
      ))*
    ) {
      assertEquals(
        Settings.fromEnvironment().backups,
        BackupSettings(
          target = Some(BackupTarget.ObjectStore),
          retentionDays = 45,
          schedule = "0 30 2 * * *",
          copyRequired = true,
          rehearsalTtl = 6.hours
        )
      )
    }
  }

  test("a schedule of five fields refuses to start: CNPG's has a seconds field first") {
    withProperties("ankka.operator.backup.schedule" -> "0 0 * * *") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_BACKUP_SCHEDULE"), e.getMessage)
      assert(e.getMessage.contains("six"), e.getMessage)
    }
  }

  test("a retention or a time to live that is not a positive number refuses to start") {
    for
      (key, variable) <- Vector(
        "ankka.operator.backup.retention-days" -> "ANKKA_BACKUP_RETENTION_DAYS",
        "ankka.operator.rehearsal.ttl-hours"   -> "ANKKA_REHEARSAL_TTL_HOURS"
      )
      bad <- Vector("0", "-3", "thirty")
    do
      withProperties(key -> bad) {
        val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
        assert(e.getMessage.contains(variable), e.getMessage)
      }
  }

  test("copy-required takes on or off and nothing else") {
    withProperties("ankka.operator.backup.copy-required" -> "yes") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_BACKUP_COPY_REQUIRED"), e.getMessage)
    }
  }
