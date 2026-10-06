package com.thinkmorestupidless.ankka.telemetry

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.DurationInt

/** What `ankka.telemetry.*` says, and what it is refused for. */
class TelemetrySettingsSuite extends munit.FunSuite:

  private def from(settings: String) =
    TelemetrySettings.from(ConfigFactory.parseString(settings).withFallback(ConfigFactory.load()))

  test("an empty address is no settings at all") {
    assertEquals(from("ankka.telemetry.endpoint = \"\""), Right(None))
    assertEquals(from("ankka.telemetry.endpoint = \"   \""), Right(None))
  }

  test("an address gives the two paths a collector serves, with or without a trailing slash") {
    for address <- Vector("http://c:4318", "http://c:4318/") do
      val settings = from(s"""ankka.telemetry.endpoint = "$address"""").toOption.flatten.get
      assertEquals(settings.tracesUrl, "http://c:4318/v1/traces")
      assertEquals(settings.metricsUrl, "http://c:4318/v1/metrics")
    assertEquals(
      from("ankka.telemetry.endpoint = \"https://collector.example\"").toOption.flatten
        .map(_.tracesUrl),
      Some("https://collector.example/v1/traces")
    )
  }

  test("headers are name=value pairs, trimmed") {
    assertEquals(
      TelemetrySettings.parseHeaders("authorization=Bearer t, x-scope = a"),
      Right(Vector("authorization" -> "Bearer t", "x-scope" -> "a"))
    )
    assertEquals(TelemetrySettings.parseHeaders(""), Right(Vector.empty))
    assertEquals(TelemetrySettings.parseHeaders("a=b=c"), Right(Vector("a" -> "b=c")))
  }

  test("a header that is not name=value is refused by its position, never by what it says") {
    val refused = TelemetrySettings.parseHeaders("a=1, secret-token-value")
    assertEquals(refused, Left("ANKKA_OTLP_HEADERS entry 2 is not name=value"))
    assert(!refused.left.toOption.get.contains("secret"))
    assert(TelemetrySettings.parseHeaders("=value").isLeft)
  }

  test("an address that is not http or https is refused, naming it") {
    val refused = from("""ankka.telemetry.endpoint = "collector:4318"""")
    assert(refused.left.toOption.exists(_.contains("collector:4318")), refused.toString)
  }

  test("the intervals and limits are read from configuration") {
    val settings = from(
      """ankka.telemetry.endpoint = "http://c:4318"
        |ankka.telemetry.interval = 50ms
        |ankka.telemetry.batch-size = 7
        |ankka.telemetry.max-backoff = 400ms
        |ankka.telemetry.service-name = orders
        |ankka.telemetry.project = shop""".stripMargin
    ).toOption.flatten.get
    assertEquals(settings.interval, 50.millis)
    assertEquals(settings.batchSize, 7)
    assertEquals(settings.maxBackoff, 400.millis)
    assertEquals(settings.exportTimeout, 5.seconds)
    assertEquals(settings.shutdownTimeout, 3.seconds)
    assertEquals((settings.serviceName, settings.project), (Some("orders"), Some("shop")))
  }
