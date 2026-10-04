package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.opentelemetry.sdk.common.`export`.HttpSenderProvider

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/**
 * The OTLP exporter sends with the JDK's own HTTP client (research R2, V1): its OkHttp sender is
 * excluded from the build, and the Anthropic client's OkHttp on the same classpath must not bring a
 * second sender back.
 */
class SenderSuite extends munit.FunSuite with LogCapturing:

  test("the only HTTP sender the exporter can find is the JDK's") {
    val providers =
      ServiceLoader
        .load(classOf[HttpSenderProvider])
        .iterator()
        .asScala
        .map(_.getClass.getName)
        .toVector
    assertEquals(
      providers,
      Vector("io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSenderProvider")
    )
  }

  test("OkHttp is on this classpath, so the case above is not passing by accident") {
    // The Anthropic client brings it through the test kit; the module's own compile classpath has
    // none (`sbt 'export telemetryOtlp/Compile/dependencyClasspath'`).
    assert(scala.util.Try(Class.forName("okhttp3.OkHttpClient")).isSuccess)
  }
