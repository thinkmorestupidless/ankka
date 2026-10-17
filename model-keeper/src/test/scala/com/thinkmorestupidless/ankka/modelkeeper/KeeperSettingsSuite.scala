package com.thinkmorestupidless.ankka.modelkeeper

import scala.concurrent.duration.*

/** The keeper's environment: its defaults, and a refusal naming each problem. */
class KeeperSettingsSuite extends munit.FunSuite:

  private val address = "ANKKA_KEEPER_ADDRESS" -> "https://models.models-system.svc:8443"

  private def read(values: (String, String)*) =
    KeeperSettings.fromEnvironment(values.toMap.get)

  test("with only the address, everything else is the component's default") {
    val settings = read(address).fold(p => fail(p.mkString("; ")), identity)
    assertEquals(settings.ollama.toString, "http://127.0.0.1:11434")
    assertEquals(settings.declaration.toString, "/etc/ankka/models/models.json")
    assertEquals((settings.port, settings.statusPort, settings.probePort), (8443, 8080, 7627))
    assertEquals(settings.passInterval, 30.seconds)
    assertEquals(settings.parallel, 1)
    assertEquals(settings.otlpEndpoint, None)
  }

  test("a missing address is refused, naming it") {
    assertEquals(
      read(),
      Left(Vector("ANKKA_KEEPER_ADDRESS is not set: the address services call"))
    )
  }

  test("every problem is named at once") {
    val problems = read(
      "ANKKA_KEEPER_PORT"          -> "eighty",
      "ANKKA_KEEPER_PASS_INTERVAL" -> "often",
      "ANKKA_KEEPER_PARALLEL"      -> "0"
    ).left.getOrElse(fail("refused nothing"))
    assertEquals(
      problems,
      Vector(
        "ANKKA_KEEPER_ADDRESS is not set: the address services call",
        "ANKKA_KEEPER_PORT 'eighty' is not a port",
        "ANKKA_KEEPER_PARALLEL '0' is not a number of at least 1",
        "ANKKA_KEEPER_PASS_INTERVAL 'often' is not a duration such as 30s"
      )
    )
  }

  test("an interval is a whole number and a unit") {
    assertEquals(KeeperSettings.duration("500ms"), Some(500.millis))
    assertEquals(KeeperSettings.duration("5m"), Some(5.minutes))
    assertEquals(KeeperSettings.duration("0s"), None)
    assertEquals(KeeperSettings.duration("1.5s"), None)
  }

  test("the collector and the parallelism are read when given") {
    val settings = read(
      address,
      "ANKKA_OTLP_ENDPOINT"   -> "http://otel-collector.ankka-telemetry.svc:4318",
      "ANKKA_KEEPER_PARALLEL" -> "4",
      "ANKKA_INSTALLATION"    -> "arrakis"
    ).fold(p => fail(p.mkString), identity)
    assertEquals(settings.otlpEndpoint.map(_.getHost), Some("otel-collector.ankka-telemetry.svc"))
    assertEquals(settings.parallel, 4)
    assertEquals(settings.installation, Some("arrakis"))
  }
