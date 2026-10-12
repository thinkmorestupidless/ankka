package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/** What a project may ask of its database (feature 041), checked the same way at both ends. */
class DatabaseSettingSuite extends munit.FunSuite:

  test("the defaults ask for nothing, and are valid") {
    assertEquals(DatabaseSetting().problems(30), Vector.empty)
    assertEquals(readFromString[DatabaseSetting]("{}"), DatabaseSetting())
  }

  test("replicas are between none and four, and a synchronous database needs one") {
    assertEquals(DatabaseSetting(replicas = 2, synchronous = true).problems(30), Vector.empty)
    assert(DatabaseSetting(replicas = 5).problems(30).exists(_.contains("between 0 and 4")))
    assert(DatabaseSetting(replicas = -1).problems(30).exists(_.contains("between 0 and 4")))
    assert(
      DatabaseSetting(synchronous = true).problems(30).exists(_.contains("at least one replica"))
    )
  }

  test("backups are kept no shorter than the installation's floor, which the refusal names") {
    assertEquals(DatabaseSetting(retentionDays = Some(90)).problems(30), Vector.empty)
    assertEquals(
      DatabaseSetting(retentionDays = Some(7)).problems(30),
      Vector("backups are kept at least 30 days on this installation, not 7")
    )
  }

  test("a rehearsal is daily or weekly, and every problem is named at once") {
    assertEquals(DatabaseSetting(rehearse = Some("weekly")).problems(30), Vector.empty)
    val all = DatabaseSetting(9, synchronous = false, Some(1), Some("hourly")).problems(30)
    assertEquals(all.size, 3, all.toString)
  }

  test("a setting round-trips as the CLI and the console send it") {
    val setting = DatabaseSetting(2, synchronous = true, Some(45), Some("daily"))
    assertEquals(readFromString[DatabaseSetting](writeToString(setting)), setting)
  }
