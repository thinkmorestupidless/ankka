package com.thinkmorestupidless.ankka.runtime.replication

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*

class ReplicationSettingsSuite extends munit.FunSuite:

  private def read(text: String, env: Map[String, String] = Map.empty): ReplicationSettings =
    ReplicationSettings.from(
      ConfigFactory.parseString(text).withFallback(ConfigFactory.defaultReference()).resolve(),
      env
    )

  test("by default nothing is named and nothing replicates") {
    val s = read("")
    assertEquals(s.installation, None)
    assertEquals(s.installations, Set.empty[String])
    assert(!s.enabled)
    assertEquals(s.poll, 1.second)
    assert(!s.holdWrites)
    assertEquals(s.handoverTimeout, 30.seconds)
  }

  test("two siblings with their databases, and this installation left out of the siblings") {
    val s = read("""
      ankka.replication.installation = leipzig
      ankka.replication.installations {
        leipzig {}
        vienna.db { host = "h1", port = 6543, name = "fleet_v", user = "u", password = "p" }
        bratislava {}
      }
      ankka.replication.handover { hold-writes = on, timeout = 5s }
    """)
    assertEquals(s.installation, Some("leipzig"))
    assertEquals(s.installations, Set("vienna", "bratislava"))
    assertEquals(s.databases.keySet, Set("vienna"))
    assertEquals(s.databases("vienna"), InstallationDatabase("h1", 6543, "fleet_v", "u", "p"))
    assert(s.enabled && s.holdWrites)
    assertEquals(s.handoverTimeout, 5.seconds)
  }

  test("an installation and its database are read from the environment") {
    val s = read(
      "ankka.replication.installation = a",
      Map(
        "ANKKA_INSTALLATION_NEW_YORK_DB_HOST" -> "db",
        "ANKKA_INSTALLATION_NEW_YORK_DB_NAME" -> "fleet_ny",
        "ANKKA_INSTALLATION_B_DB_HOST"        -> "localhost",
        "ANKKA_INSTALLATION_B_DB_PORT"        -> "5433",
        "ANKKA_OTHER"                         -> "ignored"
      )
    )
    assertEquals(s.installations, Set("new-york", "b"))
    assertEquals(s.databases("new-york").name, "fleet_ny")
    assertEquals(s.databases("b").port, 5433)
  }

  test("ANKKA_INSTALLATIONS names installations with no database (spec 057)") {
    val s = read(
      "ankka.replication.installation = leipzig",
      Map("ANKKA_INSTALLATIONS" -> " Bratislava, vienna ,,leipzig")
    )
    assertEquals(s.installations, Set("bratislava", "vienna"))
    assertEquals(s.databases, Map.empty[String, InstallationDatabase])
    assert(s.enabled)
  }

  test("a name in ANKKA_INSTALLATIONS that also has a database is one installation, with it") {
    val s = read(
      "ankka.replication.installation = leipzig",
      Map(
        "ANKKA_INSTALLATIONS"               -> "bratislava,vienna",
        "ANKKA_INSTALLATION_VIENNA_DB_HOST" -> "db-v",
        "ANKKA_INSTALLATION_VIENNA_DB_NAME" -> "fleet_v"
      )
    )
    assertEquals(s.installations, Set("bratislava", "vienna"))
    assertEquals(s.databases.keySet, Set("vienna"))
    assertEquals(s.databases("vienna").host, "db-v")
  }

  test("a name in ANKKA_INSTALLATIONS that is not a DNS label is refused, naming the variable") {
    val e = intercept[IllegalArgumentException](
      read("ankka.replication.installation = leipzig", Map("ANKKA_INSTALLATIONS" -> "vienna,a|b"))
    )
    assert(e.getMessage.startsWith("ANKKA_INSTALLATIONS:"), e.getMessage)
    assert(e.getMessage.contains("'a|b'"), e.getMessage)
  }

  test("an empty installation name is no installation") {
    assertEquals(read("ankka.replication.installation = \"  \"").installation, None)
  }

  test("a name holding '|', '/' or more than 63 characters is refused naming the setting") {
    Vector("leip|zig", "a/b", "x" * 64, "Upper").foreach { bad =>
      val e = intercept[IllegalArgumentException](
        read(s"ankka.replication.installation = \"$bad\"")
      )
      assert(e.getMessage.startsWith("ankka.replication.installation:"), e.getMessage)
    }
    val e = intercept[IllegalArgumentException](
      read("ankka.replication.installations { \"a|b\" {} }")
    )
    assert(e.getMessage.startsWith("ankka.replication.installations:"), e.getMessage)
  }
