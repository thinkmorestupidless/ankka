package com.thinkmorestupidless.ankka.controlplane

import com.typesafe.config.ConfigFactory

/** The control plane's backup settings (feature 041): defaults, overrides, refusals at start. */
class BackupConfigSuite extends munit.FunSuite:

  private def read(overrides: String): BackupConfig =
    BackupConfig.from(
      ConfigFactory.parseString(overrides).withFallback(ConfigFactory.defaultReference()).resolve()
    )

  test("unset, nothing is backed up, the floor is 30 days and no copy is required") {
    assertEquals(read(""), BackupConfig.default)
    assertEquals(BackupConfig.default, BackupConfig("none", 30, copyRequired = false))
    assert(!read("").enabled)
  }

  test("each setting is read") {
    val config = read(
      """ankka.controlplane.backups { target = "object-store", retention-days = 45, copy-required = "on" }"""
    )
    assertEquals(config, BackupConfig("object-store", 45, copyRequired = true))
    assert(config.enabled)
  }

  test("a target, a retention or a copy requirement the operator would refuse is refused here") {
    for (overrides, variable) <- Vector(
        """ankka.controlplane.backups.target = "tape""""       -> "ANKKA_BACKUP_TARGET",
        """ankka.controlplane.backups.retention-days = 0"""    -> "ANKKA_BACKUP_RETENTION_DAYS",
        """ankka.controlplane.backups.retention-days = "x""""  -> "ANKKA_BACKUP_RETENTION_DAYS",
        """ankka.controlplane.backups.copy-required = "yes"""" -> "ANKKA_BACKUP_COPY_REQUIRED"
      )
    do
      val e = intercept[IllegalArgumentException](read(overrides))
      assert(e.getMessage.contains(variable), e.getMessage)
  }
