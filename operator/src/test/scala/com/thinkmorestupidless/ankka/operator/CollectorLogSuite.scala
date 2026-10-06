package com.thinkmorestupidless.ankka.operator

import java.nio.file.{Files, Paths}

/**
 * The k3s suites read what the platform's collector received from its log; this holds the reader to
 * what the collector actually prints. The sample was captured from
 * `otel/opentelemetry-collector:0.161.0` running the component's own configuration, sent two spans
 * of one trace from two services.
 */
class CollectorLogSuite extends munit.FunSuite:

  private val sample =
    Files.readString(Paths.get(getClass.getResource("/collector-debug-sample.txt").toURI))

  test("two resources and their spans are read back, with ids, parent, kind and attributes") {
    val spans = CollectorLog.spans(sample)
    assertEquals(spans.map(_.service), Vector("orders", "payments"))
    val Vector(orders, payments) = spans
    assertEquals(orders.resource.get("ankka.project"), Some("shop"))
    assertEquals(orders.traceId, "4bf92f3577b34da6a3ce929d0e0e4736")
    assertEquals(orders.spanId, "00f067aa0ba902b7")
    assertEquals(orders.parentId, "")
    assertEquals(orders.name, "http GET /http/{id}")
    assertEquals(orders.kind, "Server")
    assertEquals(orders.attributes.get("ankka.outcome"), Some("ok"))
    assertEquals(payments.parentId, orders.spanId)
    assertEquals(payments.traceId, orders.traceId)
  }

  test("a log with nothing received is no spans") {
    assertEquals(
      CollectorLog.spans("2026-10-04T19:25:22Z\tinfo\tEverything is ready."),
      Vector.empty
    )
  }
